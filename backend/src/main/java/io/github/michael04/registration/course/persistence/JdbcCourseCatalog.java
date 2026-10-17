package io.github.michael04.registration.course.persistence;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import io.github.michael04.registration.course.CourseCatalog;
import io.github.michael04.registration.course.CourseSummary;

/**
 * {@link CourseCatalog} backed by PostgreSQL ({@code CATALOG_SOURCE=database}).
 *
 * <p>Callers use API terms ({@code 2027-SPRING}); the explicit {@link TermMapping} turns them into Banner codes
 * ({@code 202710}) for the queries. A term that isn't configured is simply unknown, with no query.
 *
 * <p><b>Known limitation (decision for Marpu):</b> this lists only <i>schedulable</i> courses, because
 * {@code catalog_api} certifies a course only when its lecture/lab links and credits are verified. That is right for
 * CSE 007 today. But {@code CourseCatalog} is also how the AI recognizes a course, so a course that is offered
 * but not yet certified is invisible: the AI would call "CSE 17" unresolved, and the scheduler would answer 422
 * "unknown". The recommended fix needs no interface change at first: drop {@code AND schedulable} from
 * {@link JdbcCatalogDao#COURSES} and {@link JdbcCatalogDao#COURSE}. {@link JdbcSectionCatalog} stays fail-closed, so an
 * uncertified course then yields "no sections available" instead of "unknown". See data/catalog-api/README.md.
 */
public final class JdbcCourseCatalog implements CourseCatalog {

	private final CatalogDao dao;

	private final TermMapping terms;

	JdbcCourseCatalog(CatalogDao dao, TermMapping terms) {
		this.dao = dao;
		this.terms = terms;
	}

	@Override
	public boolean termExists(String term) {
		return terms.banner(term).map(dao::termHasCourses).orElse(false);
	}

	@Override
	public Optional<CourseSummary> findCourse(String term, String courseId) {
		if (courseId == null || courseId.isBlank()) {
			return Optional.empty();
		}
		return terms.banner(term).flatMap(banner -> dao.course(banner, courseId.trim())).map(JdbcCourseCatalog::summary);
	}

	@Override
	public List<CourseSummary> listCourses(String term) {
		return terms.banner(term)
			.map(banner -> dao.courses(banner).stream()
				.map(JdbcCourseCatalog::summary)
				.sorted(Comparator.comparing(CourseSummary::courseId))
				.toList())
			.orElse(List.of());
	}

	private static CourseSummary summary(CatalogDao.CourseRow row) {
		return new CourseSummary(row.courseId(), row.title(), row.credits());
	}

}
