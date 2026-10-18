package io.github.michael04.registration.common;

/** Signals unavailable AI functionality without exposing provider details. */
public class AiUnavailableException extends RuntimeException {

	public AiUnavailableException() {
		this(null);
	}

	public AiUnavailableException(Throwable cause) {
		super("AI service is unavailable", cause);
	}

}
