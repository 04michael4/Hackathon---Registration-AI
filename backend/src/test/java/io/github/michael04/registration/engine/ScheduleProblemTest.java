package io.github.michael04.registration.engine;

import static java.time.DayOfWeek.MONDAY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

/** Input validation for the engine's records other than {@link Meeting}. */
class ScheduleProblemTest {

	private static final Meeting MON_9 = new Meeting(Set.of(MONDAY), LocalTime.of(9, 0), LocalTime.of(9, 50));
	private static final Constraints CONSTRAINTS = new Constraints(0, 18, List.of());

	private static SectionOption section(String crn, String courseId) {
		return new SectionOption(crn, courseId, "LEC", null, 3, List.of(MON_9));
	}

	private static SectionOption lab(String crn, String courseId, String parentCrn) {
		return new SectionOption(crn, courseId, "LAB", parentCrn, 0, List.of(MON_9));
	}

	@Test
	void acceptsWellFormedProblem() {
		CourseOption cse = new CourseOption("CSE-017", true, List.of(section("1", "CSE-017"), section("2", "CSE-017")));
		CourseOption math = new CourseOption("MATH-021", false, List.of(section("3", "MATH-021")));

		ScheduleProblem problem = new ScheduleProblem(List.of(cse, math), CONSTRAINTS, SoftWeights.none(), 5);

		assertEquals(2, problem.courses().size());
	}

	@Test
	void rejectsDuplicateCourseIds() {
		CourseOption a = new CourseOption("CSE-017", true, List.of(section("1", "CSE-017")));
		CourseOption b = new CourseOption("CSE-017", false, List.of(section("2", "CSE-017")));

		assertThrows(IllegalArgumentException.class,
				() -> new ScheduleProblem(List.of(a, b), CONSTRAINTS, SoftWeights.none(), 5));
	}

	@Test
	void rejectsDuplicateCrnsAcrossCourses() {
		CourseOption a = new CourseOption("CSE-017", true, List.of(section("1", "CSE-017")));
		CourseOption b = new CourseOption("MATH-021", true, List.of(section("1", "MATH-021")));

		assertThrows(IllegalArgumentException.class,
				() -> new ScheduleProblem(List.of(a, b), CONSTRAINTS, SoftWeights.none(), 5));
	}

	@Test
	void rejectsMaxResultsBelowOne() {
		assertThrows(IllegalArgumentException.class,
				() -> new ScheduleProblem(List.of(), CONSTRAINTS, SoftWeights.none(), 0));
	}

	@Test
	void courseRejectsSectionOfAnotherCourse() {
		assertThrows(IllegalArgumentException.class,
				() -> new CourseOption("CSE-017", true, List.of(section("1", "MATH-021"))));
	}

	@Test
	void courseAllowsNoSections() {
		assertTrue(new CourseOption("CSE-017", true, List.of()).sections().isEmpty());
	}

	@Test
	void courseAcceptsLecturesWithLinkedLabs() {
		CourseOption chm = new CourseOption("CHM-030", true,
				List.of(section("10", "CHM-030"), lab("11", "CHM-030", "10"), lab("12", "CHM-030", "10")));

		assertTrue(chm.sections().get(0).isPrimary());
		assertFalse(chm.sections().get(1).isPrimary());
	}

	@Test
	void courseAllowsLinkedSectionWhoseLectureWasFilteredOut() {
		CourseOption chm = new CourseOption("CHM-030", true, List.of(lab("11", "CHM-030", "10")));

		assertEquals(1, chm.sections().size());
	}

	@Test
	void courseRejectsSectionLinkedToAnotherLinkedSection() {
		assertThrows(IllegalArgumentException.class, () -> new CourseOption("CHM-030", true,
				List.of(section("10", "CHM-030"), lab("11", "CHM-030", "10"), lab("12", "CHM-030", "11"))));
	}

	@Test
	void sectionRejectsBlankIdsSelfLinkAndNegativeCredits() {
		assertThrows(IllegalArgumentException.class, () -> new SectionOption(" ", "CSE-017", "LEC", null, 3, List.of()));
		assertThrows(IllegalArgumentException.class, () -> new SectionOption("1", "", "LEC", null, 3, List.of()));
		assertThrows(IllegalArgumentException.class, () -> new SectionOption("1", "CSE-017", " ", null, 3, List.of()));
		assertThrows(IllegalArgumentException.class, () -> new SectionOption("1", "CSE-017", "LAB", "1", 0, List.of()));
		assertThrows(IllegalArgumentException.class, () -> new SectionOption("1", "CSE-017", "LAB", "", 0, List.of()));
		assertThrows(IllegalArgumentException.class, () -> new SectionOption("1", "CSE-017", "LEC", null, -1, List.of()));
	}

	@Test
	void sectionCopiesMeetingsDefensively() {
		List<Meeting> meetings = new ArrayList<>(List.of(MON_9));
		SectionOption section = new SectionOption("1", "CSE-017", "LEC", null, 3, meetings);

		meetings.clear();

		assertEquals(List.of(MON_9), section.meetings());
	}

	@Test
	void constraintsRejectInvalidCreditRange() {
		assertThrows(IllegalArgumentException.class, () -> new Constraints(-1, 18, List.of()));
		assertThrows(IllegalArgumentException.class, () -> new Constraints(12, 11, List.of()));
	}

	@Test
	void softWeightsRejectWeightsOutsideZeroToFive() {
		assertThrows(IllegalArgumentException.class,
				() -> new SoftWeights(null, 6, null, 0, Set.of(), 0, 0, 0));
		assertThrows(IllegalArgumentException.class,
				() -> new SoftWeights(null, 0, null, 0, Set.of(), 0, -1, 0));
	}

	@Test
	void scoredScheduleBreakdownIsSortedByKey() {
		ScoredSchedule schedule = new ScoredSchedule(List.of(), 0, 4, 96,
				new TreeMap<>(Map.of(SoftWeights.GAPS_KEY, 1, SoftWeights.DAYS_OFF_KEY, 3)));

		assertEquals(List.of(SoftWeights.DAYS_OFF_KEY, SoftWeights.GAPS_KEY),
				new ArrayList<>(schedule.breakdown().keySet()));
	}

}
