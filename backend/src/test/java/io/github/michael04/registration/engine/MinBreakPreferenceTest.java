package io.github.michael04.registration.engine;

import static io.github.michael04.registration.engine.EngineFixtures.assertValid;
import static io.github.michael04.registration.engine.EngineFixtures.crns;
import static io.github.michael04.registration.engine.EngineFixtures.lecture;
import static io.github.michael04.registration.engine.EngineFixtures.meeting;
import static io.github.michael04.registration.engine.EngineFixtures.required;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** The "at least N minutes between classes" wish: counted per same-day pair, ranks schedules, and never removes one. */
class MinBreakPreferenceTest {

	private static final ScheduleSolver SOLVER = new BacktrackingScheduleSolver();
	private static final Constraints ANY_CREDITS = new Constraints(0, 21, List.of());

	private static SoftWeights wantsBreak(int minutes, int weight) {
		return new SoftWeights(null, 0, null, 0, Set.of(), 0, 0, 0, minutes, weight);
	}

	@Test
	void shortfallCountsEveryPairBelowTheWantedBreak() {
		// 09:00-10:00 then 10:30-11:30 on MWF: a 30-minute break, 30 short of 60, three days.
		var meetings = List.of(meeting("MWF", "09:00", "10:00"), meeting("MWF", "10:30", "11:30"));
		assertEquals(90, ScheduleScorer.breakShortfallMinutes(meetings, 60));
		// Back to back misses the whole 60 on each of the three days.
		assertEquals(180, ScheduleScorer.breakShortfallMinutes(
				List.of(meeting("MWF", "09:00", "10:00"), meeting("MWF", "10:00", "11:00")), 60));
		// A break of exactly the wanted length, a longer one, different days, and "no wish" all count nothing.
		assertEquals(0, ScheduleScorer.breakShortfallMinutes(
				List.of(meeting("MWF", "09:00", "10:00"), meeting("MWF", "11:00", "12:00")), 60));
		assertEquals(0, ScheduleScorer.breakShortfallMinutes(
				List.of(meeting("MWF", "09:00", "10:00"), meeting("TR", "10:00", "11:00")), 60));
		assertEquals(0, ScheduleScorer.breakShortfallMinutes(meetings, 0));
	}

	@Test
	void theScheduleWithTheLongerBreakRanksFirstWhenTheWishIsSet() {
		CourseOption first = required("AAA-001", lecture("1", "AAA-001", 3, meeting("MWF", "09:00", "10:00")));
		// B1 starts 30 minutes after A ends; B2 starts 90 minutes after. CRN order alone would put B1 first.
		CourseOption second = required("BBB-001",
				lecture("2", "BBB-001", 3, meeting("MWF", "10:30", "11:30")),
				lecture("3", "BBB-001", 3, meeting("MWF", "11:30", "12:30")));
		ScheduleProblem withWish = new ScheduleProblem(List.of(first, second), ANY_CREDITS, wantsBreak(60, 5), 10);

		ScheduleResult result = SOLVER.solve(withWish);

		assertValid(withWish, result);
		assertEquals(2, result.schedules().size(), "a wish ranks schedules, it must not remove any");
		assertEquals(List.of("1", "3"), crns(result.schedules().get(0)));
		assertEquals(List.of("1", "2"), crns(result.schedules().get(1)));
		assertEquals(0, result.schedules().get(0).breakdown().get("minBreakMinutes"), "the 90-minute break meets the wish, so it costs no points");
		assertTrue(result.schedules().get(1).breakdown().get("minBreakMinutes") > 0);
		assertTrue(result.schedules().get(0).score() > result.schedules().get(1).score());
	}

	@Test
	void withoutTheWishTheOrderIsTheUsualCrnTieBreak() {
		CourseOption first = required("AAA-001", lecture("1", "AAA-001", 3, meeting("MWF", "09:00", "10:00")));
		CourseOption second = required("BBB-001",
				lecture("2", "BBB-001", 3, meeting("MWF", "10:30", "11:30")),
				lecture("3", "BBB-001", 3, meeting("MWF", "11:30", "12:30")));

		ScheduleResult result = SOLVER.solve(new ScheduleProblem(List.of(first, second), ANY_CREDITS, SoftWeights.none(), 10));

		assertEquals(List.of("1", "2"), crns(result.schedules().get(0)));
		assertTrue(result.schedules().get(0).breakdown().isEmpty());
	}

	@Test
	void aSoftBreakWishNeverOverridesAHardBlockedTime() {
		// The only fit for B at 09:00 is blocked ("no classes before 10"); the 08:00 section is blocked too. Only 10:30 remains.
		CourseOption first = required("AAA-001", lecture("1", "AAA-001", 3, meeting("MWF", "10:00", "11:00")));
		CourseOption second = required("BBB-001",
				lecture("2", "BBB-001", 3, meeting("MWF", "08:00", "09:00")),
				lecture("3", "BBB-001", 3, meeting("MWF", "11:00", "12:00")));
		Constraints noClassesBefore10 = new Constraints(0, 21, List.of(meeting("MTWRF", "00:00", "10:00")));

		ScheduleResult result = SOLVER.solve(new ScheduleProblem(List.of(first, second), noClassesBefore10, wantsBreak(60, 5), 10));

		assertEquals(1, result.schedules().size());
		assertEquals(List.of("1", "3"), crns(result.schedules().get(0)), "back-to-back is allowed because the break is only a wish");
		assertTrue(result.schedules().get(0).breakdown().get("minBreakMinutes") > 0);
	}

	@Test
	void whenNothingMeetsTheHardRuleThereIsNoScheduleInsteadOfASilentViolation() {
		CourseOption only = required("AAA-001", lecture("1", "AAA-001", 3, meeting("MWF", "09:00", "10:00")));
		Constraints noClassesBefore10 = new Constraints(0, 21, List.of(meeting("MTWRF", "00:00", "10:00")));

		ScheduleResult result = SOLVER.solve(new ScheduleProblem(List.of(only), noClassesBefore10, SoftWeights.none(), 10));

		assertTrue(result.schedules().isEmpty());
	}

	@Test
	void breakWeightAndLengthAreRangeChecked() {
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> wantsBreak(241, 3));
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> wantsBreak(60, 6));
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> wantsBreak(-1, 3));
	}
}
