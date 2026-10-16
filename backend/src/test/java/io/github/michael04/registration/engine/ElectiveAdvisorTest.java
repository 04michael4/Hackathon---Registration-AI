package io.github.michael04.registration.engine;

import static io.github.michael04.registration.engine.EngineFixtures.lecture;
import static io.github.michael04.registration.engine.EngineFixtures.linked;
import static io.github.michael04.registration.engine.EngineFixtures.meeting;
import static io.github.michael04.registration.engine.EngineFixtures.optional;
import static io.github.michael04.registration.engine.EngineFixtures.required;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.michael04.registration.engine.ElectiveAdvice.Status;

class ElectiveAdvisorTest {

	private static final ScheduleSolver SOLVER = new BacktrackingScheduleSolver();
	private static final ElectiveAdvisor ADVISOR = new ElectiveAdvisor(SOLVER);
	private static final SoftWeights START_AT_10 = new SoftWeights(LocalTime.of(10, 0), 3, null, 0, Set.of(), 0, 0, 0);

	private static final CourseOption CSE_007 = required("CSE-007",
			lecture("12275", "CSE-007", 4, meeting("M", "09:20", "10:35")),
			linked("13132", "CSE-007", "LAB", "12275", meeting("WF", "09:20", "10:35")),
			linked("12638", "CSE-007", "LAB", "12275", meeting("WF", "10:45", "12:00")));
	private static final CourseOption MATH_021 = required("MATH-021",
			lecture("20001", "MATH-021", 4, meeting("MWF", "13:00", "13:50")),
			lecture("20002", "MATH-021", 4, meeting("TR", "10:30", "11:45")));

	private static ElectiveAdvice adviceFor(ScheduleProblem problem, String courseId) {
		return ADVISOR.explainElectives(problem, SOLVER.solve(problem)).stream()
			.filter(a -> a.courseId().equals(courseId))
			.findFirst()
			.orElseThrow();
	}

	private static ScheduleProblem problem(Constraints constraints, int maxResults, CourseOption... courses) {
		return new ScheduleProblem(List.of(courses), constraints, START_AT_10, maxResults);
	}

	// --- explainElectives ---

	@Test
	void electiveInTheTopSuggestionIsReportedAsSuch() {
		CourseOption eco = optional("ECO-001", lecture("30001", "ECO-001", 4, meeting("TR", "14:00", "15:15")));
		ScheduleProblem problem = problem(new Constraints(1, 18, List.of()), 4, CSE_007, MATH_021, eco);

		ElectiveAdvice advice = adviceFor(problem, "ECO-001");

		assertEquals(Status.IN_TOP_SCHEDULE, advice.status());
		assertEquals("ECO-001 is in your top suggestion.", advice.summary());
	}

	@Test
	void sectionSwapIsSpelledOut() {
		// Weight 0 for electives, earliest start 10:00 weight 3: the top suggestion picks MATH-021 20002
		// (TR 10:30), which clashes with ECO-001. ECO-001 only fits with MATH-021 20001.
		CourseOption eco = optional("ECO-001", lecture("30001", "ECO-001", 3, meeting("TR", "10:00", "11:00")));
		CourseOption math = required("MATH-021",
				lecture("20001", "MATH-021", 4, meeting("MWF", "08:00", "08:50")),
				lecture("20002", "MATH-021", 4, meeting("TR", "10:30", "11:45")));
		ScheduleProblem problem = problem(new Constraints(1, 18, List.of()), 1, math, eco);

		ElectiveAdvice advice = adviceFor(problem, "ECO-001");

		assertEquals(Status.FITS_WITH_CHANGES, advice.status());
		assertEquals(List.of("Take MATH-021 20001 instead of 20002."), advice.fixes());
		assertEquals(List.of("30001", "20001"), advice.alternativeCrns()); // by course ID
	}

	@Test
	void electiveInALowerSuggestionPointsToIt() {
		CourseOption eco = optional("ECO-001", lecture("30001", "ECO-001", 3, meeting("TR", "10:00", "11:00")));
		CourseOption math = required("MATH-021",
				lecture("20001", "MATH-021", 4, meeting("MWF", "08:00", "08:50")),
				lecture("20002", "MATH-021", 4, meeting("TR", "10:30", "11:45")));
		ScheduleProblem problem = problem(new Constraints(1, 18, List.of()), 4, math, eco);

		ElectiveAdvice advice = adviceFor(problem, "ECO-001");

		assertEquals(Status.IN_OTHER_SUGGESTION, advice.status());
		assertTrue(advice.summary().startsWith("ECO-001 is in suggestion #"), advice.summary());
	}

	@Test
	void electiveBlockedByABlockedTimeSuggestsFreeingIt() {
		CourseOption eco = optional("ECO-001", lecture("30001", "ECO-001", 3, meeting("TR", "15:00", "16:15")));
		Constraints work = new Constraints(1, 18, List.of(meeting("TR", "15:00", "18:00"), meeting("F", "08:00", "09:00")));
		ScheduleProblem problem = problem(work, 4, CSE_007, eco);

		ElectiveAdvice advice = adviceFor(problem, "ECO-001");

		assertEquals(Status.CANNOT_FIT, advice.status());
		assertTrue(advice.summary().contains("ECO-001: every section overlaps your blocked times."), advice.summary());
		assertEquals(List.of("Free up your blocked time Tue/Thu 15:00–18:00."), advice.fixes());
	}

	@Test
	void electiveOverTheCreditMaximumSuggestsRaisingIt() {
		CourseOption eco = optional("ECO-001", lecture("30001", "ECO-001", 4, meeting("TR", "14:00", "15:15")));
		ScheduleProblem problem = problem(new Constraints(1, 8, List.of()), 4, CSE_007, MATH_021, eco);

		ElectiveAdvice advice = adviceFor(problem, "ECO-001");

		assertEquals(Status.CANNOT_FIT, advice.status());
		assertTrue(advice.fixes().contains("Raise your maximum credits to 12."), advice.fixes().toString());
	}

	@Test
	void electiveThatClashesWithARequiredCourseSuggestsTakingItLater() {
		CourseOption eco = optional("ECO-001", lecture("30001", "ECO-001", 3, meeting("M", "09:00", "10:00")));
		ScheduleProblem problem = problem(new Constraints(1, 18, List.of()), 4, CSE_007, eco);

		ElectiveAdvice advice = adviceFor(problem, "ECO-001");

		assertEquals(Status.CANNOT_FIT, advice.status());
		assertTrue(advice.summary().contains("CSE-007 and ECO-001 conflict in every combination."), advice.summary());
		assertEquals(List.of("Take CSE-007 in another semester; it blocks ECO-001."), advice.fixes());
	}

	@Test
	void requiredCoursesOnlyGivesNoElectiveAdvice() {
		ScheduleProblem problem = problem(new Constraints(1, 18, List.of()), 4, CSE_007, MATH_021);

		assertTrue(ADVISOR.explainElectives(problem, SOLVER.solve(problem)).isEmpty());
	}

	// --- suggestElectives ---

	@Test
	void suggestsOnlyElectivesThatFitTheScheduleBestFirst() {
		ScheduleProblem problem = problem(new Constraints(1, 18, List.of(meeting("F", "13:00", "17:00"))), 4,
				CSE_007, MATH_021);
		List<String> schedule = List.of("12275", "12638", "20002"); // M 09:20, W/F 10:45, T/R 10:30
		List<CourseOption> catalog = List.of(
				optional("ART-001", lecture("40001", "ART-001", 3, meeting("TR", "13:00", "14:15"))),
				optional("BIO-001", lecture("40002", "BIO-001", 3, meeting("TR", "08:00", "09:15"))),
				optional("CHM-001", lecture("40003", "CHM-001", 3, meeting("TR", "11:00", "12:15"))), // clashes
				optional("DAN-001", lecture("40004", "DAN-001", 3, meeting("F", "14:00", "15:00"))), // blocked
				optional("ENG-001", lecture("40005", "ENG-001", 3, meeting("M", "13:00", "13:50")),
						lecture("40006", "ENG-001", 3, meeting("M", "08:00", "08:50"))),
				optional("MATH-021", lecture("49999", "MATH-021", 4, meeting("M", "16:00", "16:50")))); // in problem

		List<ElectiveSuggestion> suggestions = ADVISOR.suggestElectives(problem, schedule, catalog, 10);

		assertEquals(List.of("ART-001", "ENG-001", "BIO-001"), suggestions.stream().map(ElectiveSuggestion::courseId).toList());
		assertEquals(List.of("40005"), suggestions.get(1).crns()); // the 13:00 section, not the 08:00 one
		assertEquals(11, suggestions.getFirst().totalCredits());
		assertTrue(suggestions.get(0).score() > suggestions.get(2).score());
	}

	@Test
	void suggestedLabCoursesComeWithLectureAndLab() {
		ScheduleProblem problem = problem(new Constraints(1, 18, List.of()), 4, MATH_021);
		CourseOption chem = optional("CHM-030",
				lecture("50001", "CHM-030", 4, meeting("MWF", "11:00", "11:50")),
				linked("50002", "CHM-030", "LAB", "50001", meeting("R", "13:00", "15:50")),
				linked("50003", "CHM-030", "LAB", "50001", meeting("M", "13:00", "15:50"))); // clashes with 20001

		List<ElectiveSuggestion> suggestions = ADVISOR.suggestElectives(problem, List.of("20001"), List.of(chem), 5);

		assertEquals(1, suggestions.size());
		assertEquals(List.of("50001", "50002"), suggestions.getFirst().crns());
		assertEquals(8, suggestions.getFirst().totalCredits());
	}

	@Test
	void respectsLimitAndRejectsUnknownScheduleCrns() {
		ScheduleProblem problem = problem(new Constraints(1, 18, List.of()), 4, MATH_021);
		List<CourseOption> catalog = List.of(
				optional("A-001", lecture("1", "A-001", 3, meeting("T", "15:00", "15:50"))),
				optional("B-001", lecture("2", "B-001", 3, meeting("R", "15:00", "15:50"))));

		assertEquals(1, ADVISOR.suggestElectives(problem, List.of("20001"), catalog, 1).size());
		assertThrows(IllegalArgumentException.class,
				() -> ADVISOR.suggestElectives(problem, List.of("nope"), catalog, 1));
		assertThrows(IllegalArgumentException.class,
				() -> ADVISOR.suggestElectives(problem, List.of("20001"), catalog, 0));
	}

	@Test
	void suggestionsDoNotDependOnCandidateOrder() {
		ScheduleProblem problem = problem(new Constraints(1, 18, List.of()), 4, MATH_021);
		CourseOption a = optional("A-001", lecture("1", "A-001", 3, meeting("T", "15:00", "15:50")));
		CourseOption b = optional("B-001", lecture("2", "B-001", 3, meeting("R", "15:00", "15:50")));

		assertEquals(ADVISOR.suggestElectives(problem, List.of("20001"), List.of(a, b), 5),
				ADVISOR.suggestElectives(problem, List.of("20001"), List.of(b, a), 5));
	}

}
