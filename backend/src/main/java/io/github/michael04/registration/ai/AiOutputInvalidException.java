package io.github.michael04.registration.ai;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The provider answered, but with output we can't use. Maps to 502 (docs/api-contract.md). */
@ResponseStatus(HttpStatus.BAD_GATEWAY)
public class AiOutputInvalidException extends RuntimeException {

	public AiOutputInvalidException(String message) {
		super(message);
	}

}
