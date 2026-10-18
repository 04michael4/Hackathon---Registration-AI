package io.github.michael04.registration.engine;

import static io.github.michael04.registration.engine.EngineFixtures.crns;
import static io.github.michael04.registration.engine.EngineFixtures.lecture;
import static io.github.michael04.registration.engine.EngineFixtures.meeting;
import static io.github.michael04.registration.engine.EngineFixtures.required;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Three different requests, three different rules, one course with three sections:
 * A 09:20-10:35 (starts before 10, ends after), B 08:00-09:50 (ends before 10), C 10:00-11:00 (starts at 10).
 */
class LatestStartConstraintTest {

	private static final ScheduleSolver SOLVER = new BacktrackingScheduleSolver();

	private static final CourseOption COURSE = required("AAA-001",
			lecture("A", "AAA-001", 3, meeting("MWF", "09:20", "10:35")),
			lecture("B", "AAA-001", 3, meeting("MWF", "08:00", "09:50")),
			lecture("C", "AAA-001", 3, meeting("MWF", "10:00", "11:00")));

	private static List<String> allowed(Constraints constraints) {
		return SOLVER.solve(new ScheduleProblem(List.of(COURSE), constraints, SoftWeights.none(), 10)).schedules().stream()
			.map(s -> crns(s).get(0)).sorted().toList();
	}

	@Test
	void startBeforeTen_allowsASectionThatEndsAfterTen() {
		assertEquals(List.of("A", "B"), allowed(new Constraints(0, 21, List.of(), LocalTime.of(10, 0))));
	}

	@Test
	void endByTen_rejectsASectionThatStartsEarlyButEndsAfterTen() {
		assertEquals(List.of("B"), allowed(new Constraints(0, 21, List.of(meeting("MTWRF", "10:00", "23:59")))));
	}

	@Test
	void noClassesBeforeTen_rejectsEveryEarlyStartAndKeepsTheOneThatBeginsAtTen() {
		assertEquals(List.of("C"), allowed(new Constraints(0, 21, List.of(meeting("MTWRF", "00:00", "10:00")))));
	}

	@Test
	void aClassStartingExactlyAtTheLimitIsNotBeforeIt() {
		assertTrue(!allowed(new Constraints(0, 21, List.of(), LocalTime.of(10, 0))).contains("C"));
		assertEquals(List.of("A", "B", "C"), allowed(new Constraints(0, 21, List.of(), LocalTime.of(10, 1))));
	}

	@Test
	void theRuleAlsoGovernsManualPlacementAndSaysWhy() {
		var problem = new ScheduleProblem(List.of(COURSE), new Constraints(0, 21, List.of(), LocalTime.of(9, 0)), SoftWeights.none(), 10);
		var report = new SelectionChecker().check(problem, List.of("A"));

		assertTrue(report.issues().stream().anyMatch(i -> i.type() == SelectionIssue.Type.BLOCKED_TIME && i.message().contains("latest allowed start")),
				report.issues().toString());
	}
}
