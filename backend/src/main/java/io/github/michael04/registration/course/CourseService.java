package io.github.michael04.registration.course;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import io.github.michael04.registration.common.NotFoundException;

@Service
public class CourseService {
	private final ObjectProvider<CourseReadCatalog> catalogs;

	public CourseService(ObjectProvider<CourseReadCatalog> catalogs) {
		this.catalogs = catalogs;
	}

	public CourseResponses.TermsResponse terms() {
		return new CourseResponses.TermsResponse(available().terms());
	}

	public CourseResponses.PageResponse search(String term, String query, String subject, int page, int size) {
		var catalog = knownTerm(term);
		return catalog.search(term, query, subject, page, size);
	}

	public CourseResponses.DetailResponse detail(String term, String courseId) {
		return knownTerm(term).detail(term, courseId)
			.orElseThrow(() -> new NotFoundException("Course is not available in this term"));
	}

	private CourseReadCatalog knownTerm(String term) {
		var catalog = available();
		if (!catalog.termExists(term)) {
			throw new NotFoundException("Term is not available");
		}
		return catalog;
	}

	private CourseReadCatalog available() {
		var catalog = catalogs.getIfAvailable();
		if (catalog == null) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
		}
		return catalog;
	}
}
