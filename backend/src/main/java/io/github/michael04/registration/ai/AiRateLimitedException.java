package io.github.michael04.registration.ai;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The AI provider is rate-limiting us (after the SDK's own retry). Maps to 429 (docs/api-contract.md). */
@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
public class AiRateLimitedException extends RuntimeException {

	public AiRateLimitedException(String message) {
		super(message);
	}

}
