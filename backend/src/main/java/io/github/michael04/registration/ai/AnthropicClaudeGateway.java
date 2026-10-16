package io.github.michael04.registration.ai;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicInvalidDataException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicRetryableException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.CredentialResolutionException;
import com.anthropic.errors.NoCredentialsException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.StructuredOutputConfig;
import com.anthropic.models.messages.StructuredTextBlock;

import io.github.michael04.registration.common.AiUnavailableException;

/**
 * {@link ClaudeGateway} backed by the official Anthropic Java SDK, using structured output so the
 * response matches {@link ClaudeExtraction}'s JSON schema.
 *
 * <p>Retries: the SDK retries transient failures (429, 5xx, connection errors) up to
 * {@code maxRetries} times; this class never retries HTTP calls itself.
 *
 * <p>Logging records outcomes, status codes and token counts only, never the API key, the prompt,
 * or the student's text.
 */
final class AnthropicClaudeGateway implements ClaudeGateway {

	private static final Logger log = LoggerFactory.getLogger(AnthropicClaudeGateway.class);

	private final AnthropicClient client;

	private final String model;

	private final long maxTokens;

	private final StructuredOutputConfig<ClaudeExtraction> outputConfig;

	AnthropicClaudeGateway(AnthropicClient client, String model, long maxTokens) {
		this.client = client;
		this.model = model;
		this.maxTokens = maxTokens;
		// Extraction is a simple task: low effort keeps latency and cost down.
		this.outputConfig = StructuredOutputConfig.<ClaudeExtraction>builder()
			.effort(OutputConfig.Effort.LOW)
			.format(ClaudeExtraction.class)
			.build();
	}

	/** Builds one reusable client from configuration. Requires a non-blank API key. */
	static AnthropicClaudeGateway create(ClaudeProperties properties) {
		AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
			.apiKey(properties.apiKey())
			.timeout(properties.timeout())
			.maxRetries(properties.maxRetries());
		if (!properties.baseUrl().isEmpty()) {
			builder.baseUrl(properties.baseUrl());
		}
		return new AnthropicClaudeGateway(builder.build(), properties.model(), properties.maxTokens());
	}

	@Override
	public Reply send(String systemPrompt, String userMessage) {
		StructuredMessageCreateParams<ClaudeExtraction> params = MessageCreateParams.builder()
			.model(model)
			.maxTokens(maxTokens)
			.system(systemPrompt)
			.outputConfig(outputConfig)
			.addUserMessage(userMessage)
			.build();

		StructuredMessage<ClaudeExtraction> message;
		try {
			message = client.messages().create(params);
		}
		catch (AnthropicServiceException ex) {
			throw translate(ex);
		}
		catch (AnthropicIoException | AnthropicRetryableException ex) {
			log.warn("Claude request failed: {} (connection or timeout)", ex.getClass().getSimpleName());
			throw new AiUnavailableException(ex);
		}
		catch (NoCredentialsException | CredentialResolutionException ex) {
			log.warn("Claude request failed: credentials unavailable");
			throw new AiUnavailableException(ex);
		}
		catch (AnthropicInvalidDataException ex) {
			log.warn("Claude response could not be read");
			return Reply.of(Outcome.MALFORMED, "unreadable response");
		}
		catch (AnthropicException ex) {
			log.warn("Claude request failed: {}", ex.getClass().getSimpleName());
			throw new AiProviderException("AI provider error");
		}
		return interpret(message);
	}

	private Reply interpret(StructuredMessage<ClaudeExtraction> message) {
		Optional<StopReason> stopReason = message.stopReason();
		logUsage(message, stopReason);
		if (stopReason.isEmpty()) {
			return Reply.of(Outcome.MALFORMED, "no stop reason");
		}
		StopReason reason = stopReason.get();
		if (StopReason.REFUSAL.equals(reason)) {
			String category = message.stopDetails().flatMap(d -> d.category()).map(Object::toString).orElse("unspecified");
			return Reply.of(Outcome.REFUSED, "refusal: " + category);
		}
		if (StopReason.MAX_TOKENS.equals(reason) || StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED.equals(reason)) {
			return Reply.of(Outcome.TRUNCATED, "stop reason " + reason);
		}
		if (!StopReason.END_TURN.equals(reason)) {
			return Reply.of(Outcome.MALFORMED, "unexpected stop reason " + reason);
		}
		try {
			// Read blocks by type: thinking blocks may come before the text block.
			Optional<StructuredTextBlock<ClaudeExtraction>> block = message.content().stream()
				.flatMap(content -> content.text().stream())
				.findFirst();
			if (block.isEmpty()) {
				return Reply.of(Outcome.MALFORMED, "no text block");
			}
			// The SDK's typed parse turns a missing property into null, which would later look like an empty
			// answer. Presence can only be proven on the raw JSON, so check that first.
			Optional<String> problem = ClaudeExtractionValidator.firstProblem(block.get().rawTextBlock().text());
			if (problem.isPresent()) {
				// The problem names schema properties only, never content from the reply.
				log.warn("Claude reply rejected: {}", problem.get());
				return Reply.of(Outcome.MALFORMED, problem.get());
			}
			ClaudeExtraction extraction = block.get().text();
			return extraction == null ? Reply.of(Outcome.MALFORMED, "empty structured output") : Reply.complete(extraction);
		}
		catch (AnthropicException | IllegalArgumentException | IllegalStateException ex) {
			// The SDK parses the structured JSON lazily; invalid JSON surfaces here.
			return Reply.of(Outcome.MALFORMED, "unparseable structured output (" + ex.getClass().getSimpleName() + ")");
		}
	}

	private static RuntimeException translate(AnthropicServiceException ex) {
		int status = ex.statusCode();
		log.warn("Claude request failed with HTTP {}", status);
		if (status == 429) {
			return new AiRateLimitedException("AI provider rate limit reached");
		}
		if (status == 401 || status == 403) {
			return new AiUnavailableException(ex);
		}
		if (status == 503 || status == 529) {
			return new AiUnavailableException(ex);
		}
		return new AiProviderException("AI provider error");
	}

	private static void logUsage(StructuredMessage<ClaudeExtraction> message, Optional<StopReason> stopReason) {
		if (!log.isInfoEnabled()) {
			return;
		}
		try {
			log.info("Claude extraction response: stopReason={}, inputTokens={}, outputTokens={}",
					stopReason.map(Object::toString).orElse("none"), message.usage().inputTokens(),
					message.usage().outputTokens());
		}
		catch (AnthropicException ex) {
			log.info("Claude extraction response: stopReason={} (usage unavailable)", stopReason.map(Object::toString).orElse("none"));
		}
	}

	@Override
	public void close() {
		client.close();
	}

}
