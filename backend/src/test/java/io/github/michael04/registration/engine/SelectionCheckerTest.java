package io.github.michael04.registration.engine;

import static io.github.michael04.registration.engine.EngineFixtures.crns;
import static io.github.michael04.registration.engine.EngineFixtures.lecture;
import static io.github.michael04.registration.engine.EngineFixtures.linked;
import static io.github.michael04.registration.engine.EngineFixtures.meeting;
import static io.github.michael04.registration.engine.EngineFixtures.optional;
import static io.github.michael04.registration.engine.EngineFixtures.required;
import static java.time.DayOfWeek.FRIDAY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.michael04.registration.engine.SelectionIssue.Severity;
import io.github.michael04.registration.engine.SelectionIssue.Type;

class SelectionCheckerTest {

	private static final SelectionChecker CHECKER = new SelectionChecker();

	// CSE-007 Spring 2027 snapshot: lecture 010 plus exactly one of labs 060 / 062.
	private static final CourseOption CSE_007 = required("CSE-007",
			lecture("12275", "CSE-007", 4, meeting("M", "09:20", "10:35")),
			linked("13132", "CSE-007", "LAB", "12275", meeting("WF", "09:20", "10:35")),
			linked("12638", "CSE-007", "LAB", "12275", meeting("WF", "10:45", "12:00")));
	private static final CourseOption MATH_021 = required("MATH-021",
			lecture("20001", "MATH-021", 4, meeting("MWF", "11:00", "11:50")),
			lecture("20002", "MATH-021", 4, meeting("TR", "10:30", "11:45")));
	private static final CourseOption ECO_001 = optional("ECO-001",
			lecture("30001", "ECO-001", 4, meeting("T", "14:00", "14:50")));
	private static final SoftWeights START_AT_10 = new SoftWeights(LocalTime.of(10, 0), 3, null, 0, Set.of(), 0, 0, 0);

	private static ScheduleProblem problem(Constraints constraints, CourseOption... courses) {
		return new ScheduleProblem(List.of(courses), constraints, START_AT_10, 5);
	}

	private static ScheduleProblem problem(CourseOption... courses) {
		return problem(new Constraints(1, 18, List.of()), courses);
	}

	private static List<Type> types(SelectionReport report) {
		return report.issues().stream().map(SelectionIssue::type).toList();
	}

	private static SectionFit fit(SelectionReport report, String crn) {
		return report.candidates().stream().filter(c -> c.crn().equals(crn)).findFirst().orElseThrow();
	}

	@Test
	void completeSelectionHasNoIssuesAndIsScoredLikeTheSolver() {
		SelectionReport report = CHECKER.check(problem(CSE_007, MATH_021), List.of("20002", "12638", "12275"));

		assertTrue(report.complete());
		assertEquals(List.of("12275", "12638", "20002"), report.sections().stream().map(SectionOption::crn).toList());
		assertEquals(8, report.totalCredits());
		// Only the 09:20 Monday lecture starts before 10:00: 40 min -> 2 points.
		assertEquals(Map.of("earliestStart", 2), report.breakdown());
		assertEquals(98, report.score());
	}

	@Test
	void placedLectureAsksForItsLabAndHighlightsTheLabsThatFit() {
		SelectionReport report = CHECKER.check(problem(CSE_007), List.of("12275"));

		assertEquals(List.of(Type.MISSING_LINKED_SECTION), types(report));
		assertEquals("CSE-007 lecture 12275 needs a LAB.", report.issues().getFirst().message());
		assertEquals(Severity.INCOMPLETE, report.issues().getFirst().severity());
		assertFalse(report.hasConflicts());
		assertTrue(fit(report, "13132").fits() && fit(report, "13132").completesLecture());
		assertTrue(fit(report, "12638").fits() && fit(report, "12638").completesLecture());
	}

	@Test
	void labsAreGreyedOutUntilTheirLectureIsPlaced() {
		SelectionReport report = CHECKER.check(problem(CSE_007), List.of());

		assertEquals(List.of(Type.REQUIRED_COURSE_MISSING, Type.UNDER_MIN_CREDITS), types(report));
		assertTrue(fit(report, "12275").fits());
		assertFalse(fit(report, "12275").completesLecture());
		assertEquals(List.of("Place its lecture 12275 first."), fit(report, "13132").reasons());
		assertFalse(fit(report, "13132").fits());
	}

	@Test
	void labPlacedWithoutItsLectureIsIncomplete() {
		SelectionReport report = CHECKER.check(problem(CSE_007), List.of("12638"));

		assertTrue(types(report).contains(Type.LINKED_WITHOUT_LECTURE));
		assertTrue(fit(report, "12275").fits());
	}

	@Test
	void secondLabForTheSameLectureIsGreyedOutAndFlaggedIfPlaced() {
		ScheduleProblem problem = problem(CSE_007);

		SectionFit otherLab = fit(CHECKER.check(problem, List.of("12275", "12638")), "13132");
		assertFalse(otherLab.fits());
		assertEquals(List.of("Lecture 12275 already has LAB 12638 placed."), otherLab.reasons());

		SelectionReport both = CHECKER.check(problem, List.of("12275", "12638", "13132"));
		assertEquals(List.of(Type.DUPLICATE_COMPONENT), types(both));
		assertEquals(List.of("12638", "13132"), both.issues().getFirst().crns());
	}

	@Test
	void secondLectureOfTheSameCourseIsGreyedOutAndFlaggedIfPlaced() {
		ScheduleProblem problem = problem(MATH_021);

		assertEquals(List.of("MATH-021 already has lecture 20002 placed."),
				fit(CHECKER.check(problem, List.of("20002")), "20001").reasons());
		assertTrue(types(CHECKER.check(problem, List.of("20001", "20002"))).contains(Type.DUPLICATE_COURSE));
	}

	@Test
	void timeConflictsAreGreyedOutWithTheReasonAndFlaggedIfPlaced() {
		ScheduleProblem problem = problem(CSE_007, MATH_021);

		// Lab 12638 (W/F 10:45-12:00) overlaps MATH-021 20001 (MWF 11:00-11:50).
		SectionFit lab = fit(CHECKER.check(problem, List.of("12275", "20001")), "12638");
		assertFalse(lab.fits());
		assertEquals(List.of("Meets at the same time as 20001 (MATH-021)."), lab.reasons());

		SelectionReport placed = CHECKER.check(problem, List.of("12275", "12638", "20001"));
		assertTrue(placed.hasConflicts());
		SelectionIssue conflict = placed.issues().getFirst();
		assertEquals(Type.TIME_CONFLICT, conflict.type());
		assertEquals(List.of("12638", "20001"), conflict.crns());
		assertEquals("12638 (CSE-007) and 20001 (MATH-021) meet at the same time.", conflict.message());
	}

	@Test
	void blockedTimesAreGreyedOutAndFlaggedIfPlaced() {
		Constraints workFriday = new Constraints(1, 18, List.of(meeting("F", "09:00", "10:40")));
		ScheduleProblem problem = problem(workFriday, CSE_007);

		SelectionReport report = CHECKER.check(problem, List.of("12275"));
		assertEquals(List.of("Overlaps one of your blocked times."), fit(report, "13132").reasons());
		assertTrue(fit(report, "12638").completesLecture());

		SelectionReport placed = CHECKER.check(problem, List.of("12275", "13132"));
		assertEquals(List.of(Type.BLOCKED_TIME), types(placed));
	}

	@Test
	void creditCapIsEnforcedForCandidatesAndPlacements() {
		ScheduleProblem problem = problem(new Constraints(1, 8, List.of()), CSE_007, MATH_021, ECO_001);

		SelectionReport report = CHECKER.check(problem, List.of("12275", "12638", "20002"));
		assertTrue(report.complete());
		assertEquals(List.of("Would bring you to 12 credits, above your maximum of 8."), fit(report, "30001").reasons());

		SelectionReport over = CHECKER.check(problem, List.of("12275", "12638", "20002", "30001"));
		assertEquals(List.of(Type.OVER_MAX_CREDITS), types(over));
	}

	@Test
	void unknownCrnsAreReportedNotThrown() {
		SelectionReport report = CHECKER.check(problem(CSE_007), List.of("12275", "12638", "99999"));

		assertEquals(List.of(Type.UNKNOWN_SECTION), types(report));
		assertEquals(List.of("12275", "12638"), report.sections().stream().map(SectionOption::crn).toList());
	}

	@Test
	void selectionOrderAndInputOrderDoNotChangeTheReport() {
		ScheduleProblem problem = problem(CSE_007, MATH_021, ECO_001);
		ScheduleProblem reversed = new ScheduleProblem(List.of(ECO_001, MATH_021, CSE_007), problem.constraints(),
				problem.soft(), problem.maxResults());

		assertEquals(CHECKER.check(problem, List.of("12275", "20001", "12638")),
				CHECKER.check(reversed, List.of("12638", "12275", "20001", "12638")));
	}

	@Test
	void everyGeneratedScheduleChecksCompleteWithTheSameScoreAndCredits() {
		Random random = new Random(42);
		BacktrackingScheduleSolver solver = new BacktrackingScheduleSolver();
		int checked = 0;
		for (int i = 0; i < 150; i++) {
			ScheduleProblem problem = randomProblem(random);
			for (ScoredSchedule schedule : solver.solve(problem).schedules()) {
				SelectionReport report = CHECKER.check(problem, crns(schedule));
				assertTrue(report.complete(), "problem " + i + ": " + report.issues());
				assertEquals(schedule.score(), report.score(), "problem " + i);
				assertEquals(schedule.breakdown(), report.breakdown(), "problem " + i);
				assertEquals(schedule.totalCredits(), report.totalCredits(), "problem " + i);
				assertEquals(schedule.sections(), report.sections(), "problem " + i);
				checked++;
			}
		}
		assertTrue(checked > 100, "schedules checked: " + checked);
	}

	private static final String[] PATTERNS = { "MWF", "TR", "MW", "T", "R", "F" };

	private static Meeting randomMeeting(Random random, int minutes) {
		LocalTime start = LocalTime.of(8, 0).plusMinutes(30L * random.nextInt(19));
		return new Meeting(meeting(PATTERNS[random.nextInt(PATTERNS.length)], "08:00", "08:01").days(), start,
				start.plusMinutes(minutes));
	}

	private static ScheduleProblem randomProblem(Random random) {
		List<CourseOption> courses = new ArrayList<>();
		int crn = 10000;
		int count = 2 + random.nextInt(4);
		for (int c = 0; c < count; c++) {
			String id = "RND-" + (100 + c);
			List<SectionOption> sections = new ArrayList<>();
			for (int l = 0, lectures = 1 + random.nextInt(3); l < lectures; l++) {
				String lectureCrn = String.valueOf(crn++);
				sections.add(lecture(lectureCrn, id, 3, randomMeeting(random, 50)));
				for (int k = 0, labs = random.nextInt(3) == 0 ? 1 + random.nextInt(2) : 0; k < labs; k++) {
					sections.add(new SectionOption(String.valueOf(crn++), id, "LAB", lectureCrn, 0,
							List.of(randomMeeting(random, 110))));
				}
			}
			Collections.shuffle(sections, random);
			courses.add(new CourseOption(id, c < 2, sections));
		}
		SoftWeights soft = new SoftWeights(LocalTime.of(10, 0), random.nextInt(6), LocalTime.of(16, 0),
				random.nextInt(6), Set.of(FRIDAY), random.nextInt(6), random.nextInt(6), random.nextInt(6));
		List<Meeting> blocked = random.nextBoolean() ? List.of() : List.of(randomMeeting(random, 90));
		return new ScheduleProblem(courses, new Constraints(3, 15, blocked), soft, 4);
	}

}
