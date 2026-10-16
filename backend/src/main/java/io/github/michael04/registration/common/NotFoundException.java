package io.github.michael04.registration.common;

/** A missing resource. The message must be safe to display to clients. */
public class NotFoundException extends RuntimeException {

	public NotFoundException(String publicMessage) {
		super(publicMessage);
	}

}
