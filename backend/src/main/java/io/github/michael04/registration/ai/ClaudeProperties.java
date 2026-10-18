package io.github.michael04.registration.ai;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for {@code AI_PROVIDER=claude}, bound from {@code app.ai.anthropic.*} in application.properties.
 *
 * @param apiKey from env var {@code ANTHROPIC_API_KEY}; blank means Claude mode returns 503
 * @param model from env var {@code ANTHROPIC_MODEL}
 * @param timeout per HTTP attempt
 * @param maxRetries SDK retries for transient failures (429, 5xx, connection errors); the
 * application itself only re-asks once for truncated or malformed output
 * @param maxTokens output cap per request, including adaptive thinking
 * @param baseUrl optional API endpoint override, used by tests to point at a local fake server
 */
@ConfigurationProperties("app.ai.anthropic")
public record ClaudeProperties(
		String apiKey,
		String model,
		Duration timeout,
		Integer maxRetries,
		Long maxTokens,
		String baseUrl) {

	public ClaudeProperties {
		apiKey = apiKey == null ? "" : apiKey.trim();
		model = model == null ? "" : model.trim();
		timeout = timeout == null ? Duration.ofSeconds(20) : timeout;
		maxRetries = maxRetries == null ? 1 : maxRetries;
		maxTokens = maxTokens == null ? 4096L : maxTokens;
		baseUrl = baseUrl == null ? "" : baseUrl.trim();
		if (timeout.isNegative() || timeout.isZero()) {
			throw new IllegalArgumentException("app.ai.anthropic.timeout must be positive");
		}
		if (maxRetries < 0 || maxRetries > 3) {
			throw new IllegalArgumentException("app.ai.anthropic.max-retries must be between 0 and 3");
		}
		if (maxTokens < 256) {
			throw new IllegalArgumentException("app.ai.anthropic.max-tokens must be at least 256");
		}
	}

	public boolean hasApiKey() {
		return !apiKey.isEmpty();
	}

	/** Never prints the API key. */
	@Override
	public String toString() {
		return "ClaudeProperties[apiKey=" + (hasApiKey() ? "***" : "<missing>") + ", model=" + model
				+ ", timeout=" + timeout + ", maxRetries=" + maxRetries + ", maxTokens=" + maxTokens
				+ (baseUrl.isEmpty() ? "" : ", baseUrl=" + baseUrl) + "]";
	}

}
