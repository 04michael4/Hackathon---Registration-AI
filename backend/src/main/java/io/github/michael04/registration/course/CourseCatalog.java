package io.github.michael04.registration.course;

import java.util.List;
import java.util.Optional;

/**
 * Read access to the course catalog for one term.
 *
 * <p>Owned by the Backend API module (Marpu). Section data comes separately from {@link SectionCatalog}.
 * Implementations: {@link DummyCourseCatalog} (made-up courses, no database) is the default. A
 * database-backed catalog and any {@link SectionCatalog} implementation are not built yet, so the
 * schedule endpoint returns 503 until one is registered.
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
