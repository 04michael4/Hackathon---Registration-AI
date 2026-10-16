package io.github.michael04.registration.ai;

import io.github.michael04.registration.common.UnprocessableException;

/**
 * The requested term has no catalog data. Maps to 422 via the shared {@code GlobalExceptionHandler}.
 *
 * <p>The client-facing message is fixed and never echoes the submitted term, following the
 * {@link UnprocessableException} rule that messages must be safe to display.
 */
public class UnknownTermException extends UnprocessableException {

	static final String PUBLIC_MESSAGE = "Term is not available";

	public UnknownTermException(String term) {
		super(PUBLIC_MESSAGE);
	}

}
