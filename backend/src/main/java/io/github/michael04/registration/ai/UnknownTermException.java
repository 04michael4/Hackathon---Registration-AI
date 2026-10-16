package io.github.michael04.registration.ai;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The requested term has no catalog data. Maps to 422 (docs/api-contract.md). */
@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
public class UnknownTermException extends RuntimeException {

	public UnknownTermException(String term) {
		super("Unknown term: " + term);
	}

}
