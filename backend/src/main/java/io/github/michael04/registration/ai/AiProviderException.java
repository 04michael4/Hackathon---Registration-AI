package io.github.michael04.registration.ai;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The AI provider returned an error we can't recover from (e.g. bad request, unknown model). Maps to 502. */
@ResponseStatus(HttpStatus.BAD_GATEWAY)
public class AiProviderException extends RuntimeException {

	public AiProviderException(String message) {
		super(message);
	}

}
