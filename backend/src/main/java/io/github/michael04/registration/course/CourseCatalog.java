package io.github.michael04.registration.course;

import java.util.List;
import java.util.Optional;

/**
 * Read access to the course catalog for one term.
 *
 * <p>Owned by the Backend API module (Marpu). Section data comes separately from {@link SectionCatalog}.
 * Production implementation: {@link io.github.michael04.registration.course.persistence.JdbcCourseCatalog},
 * backed by the authoritative read-only catalog_api views. Synthetic implementations live only in test sources.
 */
public interface CourseCatalog {

	/**
	 * Selects {@code database} in production. Tests can explicitly select their test-only {@code dummy} fixture.
	 * Set via env var {@code CATALOG_SOURCE}; database failures never fall back to synthetic data.
	 */
	String SOURCE_PROPERTY = "app.catalog.source";

	boolean termExists(String term);

	Optional<CourseSummary> findCourse(String term, String courseId);

	/** All courses in the term, ordered by course ID. */
	List<CourseSummary> listCourses(String term);

}
