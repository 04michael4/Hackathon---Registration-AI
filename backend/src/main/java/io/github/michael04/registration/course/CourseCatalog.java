package io.github.michael04.registration.course;

import java.util.List;
import java.util.Optional;

/**
 * Read access to the course catalog for one term.
 *
 * <p>Owned by the Backend API module (Marpu). Section data comes separately from {@link SectionCatalog}.
 * Implementations: {@link DummyCourseCatalog} is the explicit AI development stand-in;
 * {@code persistence.DatabaseCourseCatalog} provides authoritative database reads and also implements
 * {@link SectionCatalog} and {@link CourseReadCatalog}. Course endpoints never use the dummy.
 */
public interface CourseCatalog {

	/**
	 * Selects the active implementation: {@code dummy} (default, {@link DummyCourseCatalog})
	 * or {@code database} (PostgreSQL-backed catalog). Set via env var {@code CATALOG_SOURCE}.
	 */
	String SOURCE_PROPERTY = "app.catalog.source";

	boolean termExists(String term);

	Optional<CourseSummary> findCourse(String term, String courseId);

	/** All courses in the term, ordered by course ID. */
	List<CourseSummary> listCourses(String term);

}
