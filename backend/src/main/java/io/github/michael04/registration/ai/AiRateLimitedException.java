package io.github.michael04.registration.ai;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The AI provider is rate-limiting us (after the SDK's own retry). HTTP 429 as a Spring error
 * response, so the shared handler renders a generic Problem Details body and never shows the reason.
 */
public class AiRateLimitedException extends ResponseStatusException {

	public AiRateLimitedException(String reason) {
		super(HttpStatus.TOO_MANY_REQUESTS, reason);
	}

}
