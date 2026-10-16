package io.github.michael04.registration.common;

/** A semantic request error. The message must be safe to display to clients. */
public class UnprocessableException extends RuntimeException {

	public UnprocessableException(String publicMessage) {
		super(publicMessage);
	}

}
