package io.github.michael04.registration.ai;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Real extraction with Claude ({@code AI_PROVIDER=claude}). Sends the system prompt plus the student's
 * text, and converts the structured reply to {@link RawPreferences}; course resolution and validation
 * then happen in {@link PreferenceAssembler} exactly as for the dummy.
 *
 * <p>Retry policy: truncated or malformed replies are re-asked once (HTTP-level retries are the SDK's
 * job). A refusal is final. After that, {@link AiOutputInvalidException} → 502.
 */
public final class ClaudePreferenceExtractor implements PreferenceExtractor, AutoCloseable {

	static final String SYSTEM_PROMPT_RESOURCE = "ai/preference-extraction-system-prompt.md";

	static final int MAX_ATTEMPTS = 2;

	private static final Logger log = LoggerFactory.getLogger(ClaudePreferenceExtractor.class);

	private final ClaudeGateway gateway;

	private final String systemPrompt;

	ClaudePreferenceExtractor(ClaudeGateway gateway, String systemPrompt) {
		this.gateway = gateway;
		this.systemPrompt = systemPrompt;
	}

	@Override
	public RawPreferences extract(String studentText) {
		String userMessage = userMessage(studentText);
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			ClaudeGateway.Reply reply = gateway.send(systemPrompt, userMessage);
			switch (reply.outcome()) {
				case COMPLETE -> {
					return reply.extraction().toRawPreferences();
				}
				case REFUSED -> {
					log.warn("Claude declined the extraction request ({})", reply.detail());
					throw new AiOutputInvalidException("AI declined to process this request");
				}
				case TRUNCATED, MALFORMED -> log.warn("Claude extraction attempt {}/{} unusable: {}", attempt, MAX_ATTEMPTS,
						reply.detail());
			}
		}
		throw new AiOutputInvalidException("AI returned unusable output");
	}

	/**
	 * Wraps the student's text in tags the prompt treats as data. Every {@code &}, {@code <} and {@code >}
	 * in the text is escaped, so no spelling of a tag (upper case, extra spaces, nested, repeated) can close
	 * the wrapper early; the only real tags left are the wrapper's own.
	 *
	 * <p>This is one layer of prompt-injection defense, not the whole of it: the reply is still constrained
	 * by the structured-output schema and validated in Java ({@link PreferenceAssembler}).
	 */
	static String userMessage(String studentText) {
		return "<student_text>\n" + escapeMarkup(studentText == null ? "" : studentText) + "\n</student_text>";
	}

	/** Escapes {@code &} first, so {@code &lt;} typed by a student stays literal and nothing is double-decoded. */
	static String escapeMarkup(String text) {
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	static String loadSystemPrompt() {
		try (InputStream in = ClaudePreferenceExtractor.class.getClassLoader().getResourceAsStream(SYSTEM_PROMPT_RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException("Missing classpath resource " + SYSTEM_PROMPT_RESOURCE);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Could not read " + SYSTEM_PROMPT_RESOURCE, ex);
		}
	}

	@Override
	public void close() {
		gateway.close();
	}

}
