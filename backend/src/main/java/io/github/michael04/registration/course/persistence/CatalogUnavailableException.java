package io.github.michael04.registration.course.persistence;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The catalog database could not be reached or queried. The shared error handler renders this as a safe 503
 * ("Service is temporarily unavailable"). No reason text is set, so no connection detail can reach a client.
 */
public class CatalogUnavailableException extends ResponseStatusException {

	public CatalogUnavailableException() {
		super(HttpStatus.SERVICE_UNAVAILABLE);
	}

}
