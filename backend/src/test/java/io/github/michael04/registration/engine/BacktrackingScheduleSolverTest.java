package io.github.michael04.registration.engine;

import static io.github.michael04.registration.engine.EngineFixtures.assertValid;
import static io.github.michael04.registration.engine.EngineFixtures.bruteForceKeys;
import static io.github.michael04.registration.engine.EngineFixtures.crns;
import static io.github.michael04.registration.engine.EngineFixtures.lecture;
import static io.github.michael04.registration.engine.EngineFixtures.linked;
import static io.github.michael04.registration.engine.EngineFixtures.meeting;
import static io.github.michael04.registration.engine.EngineFixtures.optional;
import static io.github.michael04.registration.engine.EngineFixtures.required;
import static java.time.DayOfWeek.FRIDAY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

class BacktrackingScheduleSolverTest {

	private static final ScheduleSolver SOLVER = new BacktrackingScheduleSolver();
	private static final Constraints ANY_CREDITS = new Constraints(0, 21, List.of());

	// Worked example from the README: CSE-017 A/B, MATH-021 X/Y.
	private static final CourseOption CSE_017 = required("CSE-017",
			lecture("10001", "CSE-017", 3, meeting("MWF", "09:00", "09:50")),
			lecture("10002", "CSE-017", 3, meeting("MWF", "11:00", "11:50")));
	private static final CourseOption MATH_021 = required("MATH-021",
			lecture("20001", "MATH-021", 4, meeting("MWF", "11:00", "11:50")),
			lecture("20002", "MATH-021", 4, meeting("TR", "10:30", "11:45")));
	private static final SoftWeights START_10_FRIDAYS_OFF =
			new SoftWeights(LocalTime.of(10, 0), 3, null, 0, Set.of(FRIDAY), 4, 0, 0);

	private static ScheduleProblem workedExample() {
		return new ScheduleProblem(List.of(CSE_017, MATH_021), ANY_CREDITS, START_10_FRIDAYS_OFF, 10);
	}

	// --- Worked example and ranking ---

	@Test
	void workedExampleRanksBPlusYFirstAndNeverReturnsTheConflict() {
		ScheduleProblem problem = workedExample();

		ScheduleResult result = SOLVER.solve(problem);

		assertValid(problem, result);
		assertEquals(List.of("10002", "20002"), crns(result.schedules().getFirst()));
		assertEquals(Map.of("earliestStart", 0, "daysOff", 16), result.schedules().getFirst().breakdown());
		assertEquals(84, result.schedules().getFirst().score());
		assertEquals(3, result.schedules().size());
		assertFalse(result.schedules().stream().anyMatch(s -> crns(s).equals(List.of("10002", "20001"))));
		assertFalse(result.searchLimitReached());
	}

	@Test
	void equalScoresAreOrderedByCrns() {
		ScheduleResult result = SOLVER.solve(workedExample());

		// A+X and A+Y both score 77; A+X has the smaller CRN list.
		assertEquals(List.of("10001", "20001"), crns(result.schedules().get(1)));
		assertEquals(List.of("10001", "20002"), crns(result.schedules().get(2)));
		assertEquals(77, result.schedules().get(1).score());
		assertEquals(77, result.schedules().get(2).score());
	}

	@Test
	void noSoftPreferencesScoresEveryScheduleOneHundredWithEmptyBreakdown() {
		ScheduleProblem problem = new ScheduleProblem(List.of(CSE_017, MATH_021), ANY_CREDITS, SoftWeights.none(), 10);

		ScheduleResult result = SOLVER.solve(problem);

		assertValid(problem, result);
		assertEquals(3, result.schedules().size());
		result.schedules().forEach(s -> {
			assertEquals(100, s.score());
			assertTrue(s.breakdown().isEmpty());
		});
	}

	@Test
	void maxResultsLimitsTheNumberOfSchedules() {
		ScheduleProblem problem = new ScheduleProblem(List.of(CSE_017, MATH_021), ANY_CREDITS, START_10_FRIDAYS_OFF, 1);

		ScheduleResult result = SOLVER.solve(problem);

		assertEquals(1, result.schedules().size());
		assertEquals(List.of("10002", "20002"), crns(result.schedules().getFirst()));
	}

	@Test
	void gapsPreferenceRanksTheCompactScheduleFirst() {
		CourseOption morning = required("ENG-001", lecture("1", "ENG-001", 3, meeting("MW", "09:00", "09:50")));
		CourseOption later = required("HIST-010",
				lecture("2", "HIST-010", 3, meeting("MW", "13:00", "13:50")),
				lecture("3", "HIST-010", 3, meeting("MW", "10:00", "10:50")));
		SoftWeights gaps = new SoftWeights(null, 0, null, 0, Set.of(), 0, 5, 0);
		ScheduleProblem problem = new ScheduleProblem(List.of(morning, later), ANY_CREDITS, gaps, 5);

		ScheduleResult result = SOLVER.solve(problem);

		assertValid(problem, result);
		assertEquals(List.of("1", "3"), crns(result.schedules().getFirst()));
		// 10 idle min on M and W = 20 min/week: round(4 x 5 x 20/600) = 1.
		assertEquals(1, result.schedules().getFirst().breakdown().get("minimizeGaps"));
		// 190 idle min on M and W = 380 min/week: round(4 x 5 x 380/600) = 13.
		assertEquals(13, result.schedules().get(1).breakdown().get("minimizeGaps"));
	}

	@Test
	void latestEndPreferenceRanksTheEarlierSectionFirst() {
		CourseOption course = required("ART-001",
				lecture("1", "ART-001", 3, meeting("TR", "18:00", "19:15")),
				lecture("2", "ART-001", 3, meeting("TR", "13:00", "14:15")));
		SoftWeights endBy17 = new SoftWeights(null, 0, LocalTime.of(17, 0), 5, Set.of(), 0, 0, 0);
		ScheduleProblem problem = new ScheduleProblem(List.of(course), ANY_CREDITS, endBy17, 5);

		ScheduleResult result = SOLVER.solve(problem);

		assertEquals(List.of("2"), crns(result.schedules().getFirst()));
		// 135 late min on T and R = 270 min/week: round(4 x 5 x 270/300) = 18.
		assertEquals(18, result.schedules().get(1).breakdown().get("latestEnd"));
	}

	// --- Optional courses and credits ---

	@Test
	void optionalCoursesAreAddedOnlyAsFarAsMaxCreditsAllows() {
		CourseOption base = required("BASE-001", lecture("1", "BASE-001", 12, meeting("M", "08:00", "08:50")));
		CourseOption a = optional("OPT-A", lecture("2", "OPT-A", 3, meeting("T", "09:00", "09:50")));
		CourseOption b = optional("OPT-B", lecture("3", "OPT-B", 3, meeting("W", "09:00", "09:50")));
		CourseOption c = optional("OPT-C", lecture("4", "OPT-C", 3, meeting("R", "09:00", "09:50")));
		SoftWeights wantOptional = new SoftWeights(null, 0, null, 0, Set.of(), 0, 0, 3);
		ScheduleProblem problem = new ScheduleProblem(List.of(base, a, b, c), new Constraints(12, 15, List.of()),
				wantOptional, 10);

		ScheduleResult result = SOLVER.solve(problem);

		assertValid(problem, result);
		ScoredSchedule best = result.schedules().getFirst();
		assertEquals(15, best.totalCredits());
		assertEquals(2, best.sections().size());
		// 2 of 3 optional courses left out: round(4 x 3 x 2/3) = 8.
		assertEquals(8, best.breakdown().get("optionalCourseIds"));
	}

	@Test
	void onAnEqualScoreTheScheduleWithMoreElectivesRanksFirst() {
		// Elective weight 0: with and without ECO-001 score the same, so the elective decides the tie.
		CourseOption eco = optional("ECO-001", lecture("3", "ECO-001", 3, meeting("TR", "14:00", "15:15")));
		ScheduleProblem problem = new ScheduleProblem(List.of(CSE_017, eco), ANY_CREDITS, SoftWeights.none(), 2);

		ScheduleResult result = SOLVER.solve(problem);

		assertEquals(List.of("10001", "3"), crns(result.schedules().get(0)));
		assertEquals(List.of("10002", "3"), crns(result.schedules().get(1)));
	}

	@Test
	void optionalCoursesAreUsedToReachMinCredits() {
		CourseOption base = required("BASE-001", lecture("1", "BASE-001", 9, meeting("M", "08:00", "08:50")));
		CourseOption extra = optional("OPT-A", lecture("2", "OPT-A", 3, meeting("T", "09:00", "09:50")));
		ScheduleProblem problem = new ScheduleProblem(List.of(base, extra), new Constraints(12, 18, List.of()),
				SoftWeights.none(), 10);

		ScheduleResult result = SOLVER.solve(problem);

		assertValid(problem, result);
		assertEquals(1, result.schedules().size());
		assertEquals(List.of("1", "2"), crns(result.schedules().getFirst()));
	}

	@Test
	void optionalCourseThatFitsNowhereIsReportedAndLeftOut() {
		CourseOption blockedOptional = optional("OPT-A", lecture("2", "OPT-A", 3, meeting("T", "15:00", "15:50")));
		Constraints workTueThu = new Constraints(0, 21, List.of(meeting("TR", "15:00", "18:00")));
		ScheduleProblem problem = new ScheduleProblem(List.of(CSE_017, blockedOptional), workTueThu,
				SoftWeights.none(), 5);

		ScheduleResult result = SOLVER.solve(problem);

		assertValid(problem, result);
		assertEquals(2, result.schedules().size());
		assertTrue(result.diagnostics()
			.contains("Optional course OPT-A can't be added: every section overlaps your blocked times."));
	}

	// --- Lecture / lab bundles ---

	private static final CourseOption CHM_030 = required("CHM-030",
			lecture("30001", "CHM-030", 4, meeting("MWF", "09:00", "09:50")),
			linked("30011", "CHM-030", "LAB", "30001", meeting("T", "13:00", "15:50")),
			linked("30012", "CHM-030", "LAB", "30001", meeting("R", "13:00", "15:50")),
			linked("30021", "CHM-030", "REC", "30001", meeting("F", "14:00", "14:50")),
			linked("30022", "CHM-030", "REC", "30001", meeting("F", "15:00", "15:50")));

	@Test
	void lectureIsAlwaysTakenWithExactlyOneLinkedSectionPerComponent() {
		ScheduleProblem problem = new ScheduleProblem(List.of(CHM_030), ANY_CREDITS, SoftWeights.none(), 10);

		ScheduleResult result = SOLVER.solve(problem);

		assertValid(problem, result);
		assertEquals(4, result.schedules().size());
		result.schedules().forEach(s -> {
			assertEquals(3, s.sections().size());
			assertEquals("30001", s.sections().getFirst().crn()); // lecture first
			assertEquals(4, s.totalCredits());
		});
	}

	@Test
	void labOverlappingABlockedTimeIsNeverChosen() {
		Constraints tuesdayBlocked = new Constraints(0, 21, List.of(meeting("T", "12:00", "17:00")));
		ScheduleProblem problem = new ScheduleProblem(List.of(CHM_030), tuesdayBlocked, SoftWeights.none(), 10);

		ScheduleResult result = SOLVER.solve(problem);

		assertValid(problem, result);
		assertEquals(2, result.schedules().size());
		result.schedules().forEach(s -> assertTrue(crns(s).contains("30012")));
	}

	@Test
	void lectureWhoseLabsAllOverlapBlockedTimesIsUnusable() {
		Constraints labsBlocked = new Constraints(0, 21, List.of(meeting("TR", "12:00", "17:00")));
		ScheduleProblem problem = new ScheduleProblem(List.of(CHM_030), labsBlocked, SoftWeights.none(), 10);

		ScheduleResult result = SOLVER.solve(problem);

		assertTrue(result.schedules().isEmpty());
		assertTrue(result.diagnostics().contains("CHM-030: every section overlaps your blocked times."));
	}

	@Test
	void labDuringItsOwnLectureMakesThatCombinationInvalid() {
		CourseOption broken = required("BIO-041",
				lecture("1", "BIO-041", 4, meeting("MWF", "09:00", "09:50")),
				linked("2", "BIO-041", "LAB", "1", meeting("M", "09:00", "11:50")));
		ScheduleProblem problem = new ScheduleProblem(List.of(broken), ANY_CREDITS, SoftWeights.none(), 10);

		ScheduleResult result = SOLVER.solve(problem);

		assertTrue(result.schedules().isEmpty());
		assertTrue(result.diagnostics()
			.contains("BIO-041: no lecture/lab combination fits together without a time conflict."));
	}

	@Test
	void linkedSectionWithoutItsLectureIsNeverChosen() {
		CourseOption orphanLab = required("PHY-011",
				lecture("1", "PHY-011", 4, meeting("MWF", "10:00", "10:50")),
				linked("9", "PHY-011", "LAB", "8", meeting("T", "13:00", "15:50")));
		ScheduleProblem problem = new ScheduleProblem(List.of(orphanLab), ANY_CREDITS, SoftWeights.none(), 10);

		ScheduleResult result = SOLVER.solve(problem);

		assertEquals(List.of(List.of("1")), result.schedules().stream().map(EngineFixtures::crns).toList());
	}

	// --- Infeasible input: diagnostics and suggestions ---

	@Test
	void requiredCoursesThatAlwaysConflictAreNamedWithSuggestions() {
		CourseOption cse = required("CSE-017", lecture("1", "CSE-017", 3, meeting("MWF", "11:00", "11:50")));
		CourseOption math = required("MATH-021", lecture("2", "MATH-021", 4, meeting("MWF", "11:00", "11:50")));
		ScheduleProblem problem = new ScheduleProblem(List.of(math, cse), ANY_CREDITS, SoftWeights.none(), 5);

		ScheduleResult result = SOLVER.solve(problem);

		assertTrue(result.schedules().isEmpty());
		assertEquals(List.of(
				"CSE-017 and MATH-021 conflict in every combination.",
				"Suggestion: making CSE-017 optional gives schedules without it.",
				"Suggestion: making MATH-021 optional gives schedules without it."), result.diagnostics());
	}

	@Test
	void requiredCreditsAboveMaximumAreReported() {
		ScheduleProblem problem = new ScheduleProblem(List.of(CSE_017, MATH_021), new Constraints(0, 6, List.of()),
				SoftWeights.none(), 5);

		ScheduleResult result = SOLVER.solve(problem);

		assertTrue(result.schedules().isEmpty());
		assertEquals("Your required courses need at least 7 credits, above your maximum of 6.",
				result.diagnostics().getFirst());
		assertTrue(result.diagnostics().contains("Suggestion: making CSE-017 optional gives schedules without it."));
	}

	@Test
	void unreachableMinimumCreditsIsReported() {
		ScheduleProblem problem = new ScheduleProblem(List.of(CSE_017, MATH_021), new Constraints(12, 18, List.of()),
				SoftWeights.none(), 5);

		ScheduleResult result = SOLVER.solve(problem);

		assertTrue(result.schedules().isEmpty());
		assertEquals("These courses add up to at most 7 credits, below your minimum of 12.",
				result.diagnostics().getFirst());
	}

	@Test
	void requiredCourseWithNoSectionsIsReported() {
		ScheduleProblem problem = new ScheduleProblem(List.of(CSE_017, required("ECO-001")), ANY_CREDITS,
				SoftWeights.none(), 5);

		ScheduleResult result = SOLVER.solve(problem);

		assertTrue(result.schedules().isEmpty());
		assertEquals(List.of("ECO-001: no sections available.",
				"Suggestion: making ECO-001 optional gives schedules without it."), result.diagnostics());
	}

	@Test
	void requiredCourseEntirelyInsideBlockedTimeIsReported() {
		Constraints work = new Constraints(0, 21, List.of(meeting("MWF", "08:00", "12:00")));
		ScheduleProblem problem = new ScheduleProblem(List.of(CSE_017), work, SoftWeights.none(), 5);

		ScheduleResult result = SOLVER.solve(problem);

		assertTrue(result.schedules().isEmpty());
		assertEquals("CSE-017: every section overlaps your blocked times.", result.diagnostics().getFirst());
	}

	// --- Search limit ---

	@Test
	void nodeLimitStopsTheSearchAndIsReported() {
		ScheduleProblem problem = generatedProblem(new Random(42), 7, 10, 0);

		ScheduleResult result = new BacktrackingScheduleSolver(20).solve(problem);

		assertTrue(result.searchLimitReached());
		assertValid(problem, result);
		assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("limit of 20 steps")));
	}

	@Test
	void rejectsNodeLimitBelowOne() {
		assertThrows(IllegalArgumentException.class, () -> new BacktrackingScheduleSolver(0));
	}

	// --- Determinism ---

	@Test
	void solvingTwiceGivesEqualResults() {
		ScheduleProblem problem = generatedProblem(new Random(42), 6, 6, 2);

		assertEquals(SOLVER.solve(problem), SOLVER.solve(problem));
	}

	@Test
	void inputOrderDoesNotChangeTheResult() {
		Random random = new Random(42);
		for (int i = 0; i < 50; i++) {
			ScheduleProblem problem = randomSmallProblem(random);
			ScheduleResult expected = SOLVER.solve(problem);
			assertEquals(expected, SOLVER.solve(shuffled(problem, new Random(i))), "problem " + i);
		}
	}

	// --- Generated catalogs ---

	@Test
	void generatedCatalogsOnlyEverGetValidSchedulesAndTheTrueBest() {
		Random random = new Random(42);
		int withSchedules = 0;
		for (int i = 0; i < 200; i++) {
			ScheduleProblem problem = randomSmallProblem(random);
			ScheduleResult result = SOLVER.solve(problem);

			assertValid(problem, result);
			assertFalse(result.searchLimitReached(), "problem " + i);
			List<Long> best = bruteForceKeys(problem);
			List<Long> expected = best.subList(0, Math.min(problem.maxResults(), best.size()));
			List<Long> actual = result.schedules().stream().map(s -> EngineFixtures.rankKey(problem, s)).toList();
			assertEquals(expected, actual, "problem " + i);
			if (actual.isEmpty()) {
				assertFalse(result.diagnostics().isEmpty(), "problem " + i + " has no explanation");
			}
			else {
				withSchedules++;
			}
		}
		// Make sure the generator exercises both outcomes.
		assertTrue(withSchedules > 20 && withSchedules < 200, "feasible problems: " + withSchedules);
	}

	@Test
	void sevenCoursesWithTenSectionsEachSolveWellUnderASecond() {
		ScheduleProblem problem = generatedProblem(new Random(42), 7, 10, 0);

		ScheduleResult result = assertTimeout(Duration.ofSeconds(1), () -> SOLVER.solve(problem));

		assertValid(problem, result);
		assertFalse(result.schedules().isEmpty());
	}

	// --- Generators ---

	private static final String[] PATTERNS = { "MWF", "TR", "MW", "T", "R", "F" };

	private static Meeting randomMeeting(Random random, int minutes) {
		LocalTime start = LocalTime.of(8, 0).plusMinutes(30L * random.nextInt(19));
		return new Meeting(meeting(PATTERNS[random.nextInt(PATTERNS.length)], "08:00", "08:01").days(), start,
				start.plusMinutes(minutes));
	}

	/** {@code required} courses with {@code sections} 3-credit sections each, plus {@code optional} more. */
	private static ScheduleProblem generatedProblem(Random random, int required, int sections, int optional) {
		List<CourseOption> courses = new ArrayList<>();
		int crn = 10000;
		for (int c = 0; c < required + optional; c++) {
			String id = "GEN-" + (100 + c);
			List<SectionOption> list = new ArrayList<>();
			for (int s = 0; s < sections; s++) {
				list.add(lecture(String.valueOf(crn++), id, 3, randomMeeting(random, 50)));
			}
			courses.add(new CourseOption(id, c < required, list));
		}
		SoftWeights soft = new SoftWeights(LocalTime.of(10, 0), 3, LocalTime.of(17, 0), 2, Set.of(FRIDAY), 4, 2, 3);
		return new ScheduleProblem(courses, new Constraints(12, 21, List.of()), soft, 5);
	}

	/** 2–5 courses, 1–3 lectures each, a third of courses with 1–2 labs per lecture. */
	private static ScheduleProblem randomSmallProblem(Random random) {
		int courseCount = 2 + random.nextInt(4);
		int requiredCount = 1 + random.nextInt(Math.min(3, courseCount));
		int crn = 10000;
		List<CourseOption> courses = new ArrayList<>();
		for (int c = 0; c < courseCount; c++) {
			String id = "RND-" + (100 + c);
			boolean hasLabs = random.nextInt(3) == 0;
			int credits = 3 + random.nextInt(2);
			List<SectionOption> sections = new ArrayList<>();
			for (int l = 0, lectures = 1 + random.nextInt(3); l < lectures; l++) {
				String lectureCrn = String.valueOf(crn++);
				sections.add(lecture(lectureCrn, id, credits, randomMeeting(random, 50)));
				for (int k = 0, labs = hasLabs ? 1 + random.nextInt(2) : 0; k < labs; k++) {
					sections.add(new SectionOption(String.valueOf(crn++), id, "LAB", lectureCrn, 0,
							List.of(randomMeeting(random, 110))));
				}
			}
			courses.add(new CourseOption(id, c < requiredCount, sections));
		}
		List<Meeting> blocked = random.nextBoolean() ? List.of() : List.of(randomMeeting(random, 120));
		int min = random.nextInt(7);
		int max = Math.max(min, 6 + random.nextInt(16));
		Set<DayOfWeek> daysOff = EnumSet.noneOf(DayOfWeek.class);
		for (DayOfWeek day : List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
				FRIDAY)) {
			if (random.nextInt(5) == 0) {
				daysOff.add(day);
			}
		}
		SoftWeights soft = new SoftWeights(
				random.nextBoolean() ? LocalTime.of(9 + random.nextInt(3), 0) : null, random.nextInt(6),
				random.nextBoolean() ? LocalTime.of(15 + random.nextInt(4), 0) : null, random.nextInt(6),
				daysOff, random.nextInt(6), random.nextInt(6), random.nextInt(6));
		return new ScheduleProblem(courses, new Constraints(min, max, blocked), soft, 1 + random.nextInt(5));
	}

	private static ScheduleProblem shuffled(ScheduleProblem problem, Random random) {
		List<CourseOption> courses = new ArrayList<>();
		for (CourseOption course : problem.courses()) {
			List<SectionOption> sections = new ArrayList<>(course.sections());
			Collections.shuffle(sections, random);
			courses.add(new CourseOption(course.courseId(), course.required(), sections));
		}
		Collections.shuffle(courses, random);
		List<Meeting> blocked = new ArrayList<>(problem.constraints().blockedTimes());
		Collections.shuffle(blocked, random);
		Constraints constraints = new Constraints(problem.constraints().minCredits(),
				problem.constraints().maxCredits(), blocked);
		return new ScheduleProblem(courses, constraints, problem.soft(), problem.maxResults());
	}

}
