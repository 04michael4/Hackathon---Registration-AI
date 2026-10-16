package io.github.michael04.registration.course;

import java.util.List;
import java.util.Optional;

/**
 * Read access to the course catalog for one term.
 *
 * <p>TEMPORARY STAND-IN created on the AI branch with only the lookups the AI module needs.
 * Owned by the Backend API module (Marpu); the database-backed implementation comes from David.
 * Extend freely (search with paging, sections, etc.).
 */
public interface CourseCatalog {

	/**
	 * Selects the active implementation: {@code dummy} (default, {@link DummyCourseCatalog})
	 * or {@code database} (future PostgreSQL-backed catalog). Set via env var {@code CATALOG_SOURCE}.
	 */
	String SOURCE_PROPERTY = "app.catalog.source";

	boolean termExists(String term);

	Optional<CourseSummary> findCourse(String term, String courseId);

	/** All courses in the term, ordered by course ID. */
	List<CourseSummary> listCourses(String term);

}
