package io.github.michael04.registration.course;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import io.github.michael04.registration.course.CatalogSection.Status;

/**
 * TEMPORARY made-up sections for the courses in {@link DummyCourseCatalog}, so the app can build schedules
 * with no database. Not real Lehigh data: CRNs, times and rooms are invented.
 *
 * <p>The data covers the cases a demo needs: a lecture with labs and recitations (PHY-011), a lecture with
 * recitations (ECO-001), a closed section (CSE-017 012), early classes, a Friday-only section, and sections that
 * clash with each other.
 *
 * <p>Active under the same switch as {@link DummyCourseCatalog} ({@code app.catalog.source=dummy}, the default).
 * In {@code database} mode the PostgreSQL-backed catalogs replace both.
 */
@Component
@ConditionalOnProperty(name = CourseCatalog.SOURCE_PROPERTY, havingValue = "dummy", matchIfMissing = true)
public class DummySectionCatalog implements SectionCatalog {

	private static final DayOfWeek M = DayOfWeek.MONDAY;
	private static final DayOfWeek T = DayOfWeek.TUESDAY;
	private static final DayOfWeek W = DayOfWeek.WEDNESDAY;
	private static final DayOfWeek R = DayOfWeek.THURSDAY;
	private static final DayOfWeek F = DayOfWeek.FRIDAY;

	private static final List<CatalogSection> SECTIONS = new SectionList()
		.lecture("CSE-017", "41001", "010", 3, Status.OPEN, "09:00", "09:50", M, W, F)
		.lecture("CSE-017", "41002", "011", 3, Status.OPEN, "13:00", "14:15", T, R)
		.lecture("CSE-017", "41003", "012", 3, Status.CLOSED, "14:00", "14:50", M, W, F)
		.lecture("CSE-109", "42001", "010", 3, Status.OPEN, "09:00", "10:15", T, R)
		.lecture("CSE-109", "42002", "011", 3, Status.OPEN, "15:00", "16:15", M, W)
		.lecture("CSE-140", "43001", "010", 3, Status.OPEN, "11:00", "11:50", M, W, F)
		.lecture("CSE-140", "43002", "011", 3, Status.OPEN, "10:45", "12:00", T, R)
		.lecture("ECO-001", "44001", "010", 4, Status.OPEN, "10:00", "10:50", M, W)
		.linked("ECO-001", "44011", "011", "RECITATION", "44001", "09:00", "09:50", R)
		.linked("ECO-001", "44012", "012", "RECITATION", "44001", "10:00", "10:50", F)
		.lecture("ECO-001", "44002", "020", 4, Status.OPEN, "14:30", "15:45", T, R)
		.linked("ECO-001", "44021", "021", "RECITATION", "44002", "13:00", "13:50", F)
		.lecture("ENGL-001", "45001", "010", 3, Status.OPEN, "08:00", "08:50", M, W, F)
		.lecture("ENGL-001", "45002", "011", 3, Status.OPEN, "16:00", "17:15", T, R)
		.lecture("ENGL-001", "45003", "012", 3, Status.OPEN, "09:00", "11:50", F)
		.lecture("MATH-021", "46001", "010", 4, Status.OPEN, "10:00", "10:50", M, W, F)
		.lecture("MATH-021", "46002", "011", 4, Status.OPEN, "10:30", "11:45", T, R)
		.lecture("MATH-021", "46003", "012", 4, Status.OPEN, "13:00", "13:50", M, W, F)
		.lecture("MATH-022", "47001", "010", 4, Status.OPEN, "09:00", "09:50", M, W, F)
		.lecture("MATH-022", "47002", "011", 4, Status.OPEN, "13:30", "14:45", T, R)
		.lecture("PHY-011", "48001", "010", 4, Status.OPEN, "10:00", "10:50", M, W, F)
		.linked("PHY-011", "48011", "011", "LAB", "48001", "13:00", "15:50", T)
		.linked("PHY-011", "48012", "012", "LAB", "48001", "13:00", "15:50", R)
		.linked("PHY-011", "48021", "013", "RECITATION", "48001", "15:00", "15:50", M)
		.linked("PHY-011", "48022", "014", "RECITATION", "48001", "15:00", "15:50", W)
		.lecture("PHY-011", "48002", "020", 4, Status.OPEN, "08:00", "09:15", T, R)
		.linked("PHY-011", "48013", "021", "LAB", "48002", "13:00", "15:50", W)
		.linked("PHY-011", "48023", "022", "RECITATION", "48002", "11:00", "11:50", F)
		.sections();

	@Override
	public List<CatalogSection> sectionsFor(String term, List<String> courseIds) {
		return SECTIONS.stream()
			.filter(s -> s.term().equals(term) && courseIds.contains(s.courseId()))
			.toList();
	}

	/** Small builder so the table above stays readable. */
	private static final class SectionList {

		private final List<CatalogSection> sections = new ArrayList<>();

		SectionList lecture(String courseId, String crn, String code, int credits, Status status, String start,
				String end, DayOfWeek... days) {
			sections.add(section(courseId, crn, code, credits, "LECTURE", null, status, start, end, days));
			return this;
		}

		SectionList linked(String courseId, String crn, String code, String component, String lectureCrn,
				String start, String end, DayOfWeek... days) {
			sections.add(section(courseId, crn, code, 0, component, lectureCrn, Status.OPEN, start, end, days));
			return this;
		}

		List<CatalogSection> sections() {
			return sections.stream().sorted(Comparator.comparing(CatalogSection::crn)).toList();
		}

		private static CatalogSection section(String courseId, String crn, String code, int credits, String component,
				String lectureCrn, Status status, String start, String end, DayOfWeek... days) {
			return new CatalogSection(DummyCourseCatalog.TERM, courseId, crn, code, credits, component, lectureCrn, status,
					List.of(new CatalogSection.Meeting(List.of(days), LocalTime.parse(start), LocalTime.parse(end),
							"TBD (demo data)")));
		}
	}

}
