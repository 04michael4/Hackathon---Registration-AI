package io.github.michael04.registration.ai;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The AI provider returned an error we can't recover from (e.g. bad request, unknown model).
 * HTTP 502 as a Spring error response; the shared handler shows only a generic detail.
 */
public class AiProviderException extends ResponseStatusException {

	public AiProviderException(String reason) {
		super(HttpStatus.BAD_GATEWAY, reason);
	}

}
