package io.github.michael04.registration.ai;

/**
 * The single place that talks to the Anthropic SDK. Keeps SDK types out of the extraction logic,
 * so {@link ClaudePreferenceExtractor} can be tested with a simple fake.
 *
 * <p>Implementations translate provider failures into {@link AiRateLimitedException} (429),
 * {@link AiUnavailableException} (503) or {@link AiProviderException} (502), and report a
 * response the model did produce as a {@link Reply}.
 */
interface ClaudeGateway extends AutoCloseable {

	Reply send(String systemPrompt, String userMessage);

	@Override
	default void close() {
	}

	enum Outcome {
		/** Finished normally with schema-valid output. */
		COMPLETE,
		/** The model declined (stop reason {@code refusal}). Retrying won't help. */
		REFUSED,
		/** Cut off ({@code max_tokens} or context window). Worth one retry. */
		TRUNCATED,
		/** Missing or unparseable output, or an unexpected stop reason. Worth one retry. */
		MALFORMED
	}

	/** @param detail short diagnostic for logs only; never sent to clients */
	record Reply(Outcome outcome, ClaudeExtraction extraction, String detail) {

		static Reply complete(ClaudeExtraction extraction) {
			return new Reply(Outcome.COMPLETE, extraction, "ok");
		}

		static Reply of(Outcome outcome, String detail) {
			return new Reply(outcome, null, detail);
		}

	}

}
