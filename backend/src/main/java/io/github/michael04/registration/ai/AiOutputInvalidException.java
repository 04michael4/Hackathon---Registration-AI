package io.github.michael04.registration.ai;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The provider answered, but with output we can't use (refusal, truncated or malformed twice).
 * HTTP 502 as a Spring error response; the shared handler shows only a generic detail.
 */
public class AiOutputInvalidException extends ResponseStatusException {

	public AiOutputInvalidException(String reason) {
		super(HttpStatus.BAD_GATEWAY, reason);
	}

}
