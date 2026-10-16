package io.github.michael04.registration.course.persistence;

import java.util.List;
import java.util.Optional;

/**
 * The only queries the catalogs run, one method per query, in Banner terms. A seam so the mapping logic can be
 * tested without a database; {@link JdbcCatalogDao} is the PostgreSQL implementation.
 *
 * <p>Implementations throw {@link CatalogUnavailableException} when the database can't be used.
 */
interface CatalogDao {

	boolean termHasCourses(String bannerTerm);

	/** Schedulable courses of the term, ordered by course ID. */
	List<CourseRow> courses(String bannerTerm);

	Optional<CourseRow> course(String bannerTerm, String courseId);

	/** Sections of schedulable courses only, ordered by course ID then CRN. */
	List<SectionRow> sections(String bannerTerm, List<String> courseIds);

	/** One row per distinct (time, place) of a section; {@code days} are ISO numbers, 1 = Monday. */
	List<MeetingRow> meetings(String bannerTerm, List<String> crns);

	record CourseRow(String courseId, String title, int credits) {
	}

	/** {@code lectureCrn} is the parent lecture's CRN for linked sections and {@code null} for lectures. */
	record SectionRow(String courseId, String crn, String sectionCode, int credits, String componentType,
			String lectureCrn, String status) {
	}

	record MeetingRow(String crn, List<Integer> days, String start, String end, String location) {
	}

}
