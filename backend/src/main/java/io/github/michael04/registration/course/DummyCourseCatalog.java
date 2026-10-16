package io.github.michael04.registration.course;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * TEMPORARY dummy catalog with a few made-up courses for one made-up term.
 * Not real Lehigh data, and needs no database.
 *
 * <p>Active only when {@code app.catalog.source=dummy} (the default; env var {@code CATALOG_SOURCE}).
 * A database-backed catalog should be annotated
 * {@code @ConditionalOnProperty(name = "app.catalog.source", havingValue = "database")},
 * so exactly one {@link CourseCatalog} exists for any setting.
 */
@Component
@ConditionalOnProperty(name = CourseCatalog.SOURCE_PROPERTY, havingValue = "dummy", matchIfMissing = true)
public class DummyCourseCatalog implements CourseCatalog {

	public static final String TERM = "2027-SPRING";

	private static final List<CourseSummary> COURSES = List.of(
			new CourseSummary("CSE-017", "Programming and Data Structures", 3),
			new CourseSummary("CSE-109", "Systems Software", 3),
			new CourseSummary("CSE-140", "Foundations of Discrete Structures and Algorithms", 3),
			new CourseSummary("ECO-001", "Principles of Economics", 4),
			new CourseSummary("ENGL-001", "Composition and Literature", 3),
			new CourseSummary("MATH-021", "Calculus I", 4),
			new CourseSummary("MATH-022", "Calculus II", 4),
			new CourseSummary("PHY-011", "Introductory Physics I", 4))
		.stream()
		.sorted(Comparator.comparing(CourseSummary::courseId))
		.toList();

	@Override
	public boolean termExists(String term) {
		return TERM.equals(term);
	}

	@Override
	public Optional<CourseSummary> findCourse(String term, String courseId) {
		if (!termExists(term)) {
			return Optional.empty();
		}
		return COURSES.stream().filter(c -> c.courseId().equals(courseId)).findFirst();
	}

	@Override
	public List<CourseSummary> listCourses(String term) {
		return termExists(term) ? COURSES : List.of();
	}

}
