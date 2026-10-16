package io.github.michael04.registration.course;

import java.util.List;
import java.util.Optional;

/**
 * Read access to the course catalog for one term.
 *
 * <p>Retains the interface introduced on the AI branch. Section data is provided
 * separately by {@link SectionCatalog}, so existing AI catalog implementations remain compatible.
 * No production catalog is registered on this branch.
 */
public interface CourseCatalog {

	/**
	 * Selects the active implementation: {@code dummy} (the AI branch's dummy catalog)
	 * or {@code database} (future PostgreSQL-backed catalog). Set via env var {@code CATALOG_SOURCE}.
	 */
	String SOURCE_PROPERTY = "app.catalog.source";

	boolean termExists(String term);

	Optional<CourseSummary> findCourse(String term, String courseId);

	/** All courses in the term, ordered by course ID. */
	List<CourseSummary> listCourses(String term);

}
