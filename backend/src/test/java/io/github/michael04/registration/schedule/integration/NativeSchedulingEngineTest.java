package io.github.michael04.registration.schedule.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.michael04.registration.schedule.integration.SchedulingEngine.Constraints;
import io.github.michael04.registration.schedule.integration.SchedulingEngine.Course;
import io.github.michael04.registration.schedule.integration.SchedulingEngine.Meeting;
import io.github.michael04.registration.schedule.integration.SchedulingEngine.Problem;
import io.github.michael04.registration.schedule.integration.SchedulingEngine.Section;
import io.github.michael04.registration.schedule.integration.SchedulingEngine.SoftWeights;

class NativeSchedulingEngineTest {

	private final NativeSchedulingEngine engine = new NativeSchedulingEngine();

	private static final SoftWeights START_AT_10 =
			new SoftWeights(0, LocalTime.of(10, 0), 3, null, 0, List.of(), 0, 0);
	private static final Constraints ANY = new Constraints(1, 21, List.of());

	private static Meeting meeting(String start, String end, DayOfWeek... days) {
		return new Meeting(List.of(days), LocalTime.parse(start), LocalTime.parse(end));
	}

	private static Course cse007(String lectureLinkForLabs) {
		return new Course("CSE-007", true, List.of(
				new Section("12275", "CSE-007", 4, "LECTURE", null, List.of(meeting("09:20", "10:35", DayOfWeek.MONDAY))),
				new Section("13132", "CSE-007", 0, "LAB", lectureLinkForLabs,
						List.of(meeting("09:20", "10:35", DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY))),
				new Section("12638", "CSE-007", 0, "LAB", lectureLinkForLabs,
						List.of(meeting("10:45", "12:00", DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)))));
	}

	@Test
	void mapsLectureLinksToLecturePlusExactlyOneLabWithExactScores() {
		var result = engine.solve(new Problem(List.of(cse007("12275")), ANY, START_AT_10, 5), 1_000_000);

		assertThat(result.schedules()).hasSize(2);
		var best = result.schedules().getFirst();
		assertThat(best.sectionCrns()).containsExactly("12275", "12638");
		assertThat(best.totalCredits()).isEqualTo(4);
		assertThat(best.score()).isEqualByComparingTo(BigDecimal.valueOf(98));
		assertThat(best.scoreBreakdown()).isEqualTo(Map.of("earliestStart", BigDecimal.valueOf(2)));
		assertThat(result.schedules().get(1).sectionCrns()).containsExactly("12275", "13132");
		assertThat(result.searchLimitReached()).isFalse();
	}

	@Test
	void labsWhoseLectureIsMissingAreNeverChosen() {
		var result = engine.solve(new Problem(List.of(cse007("99999")), ANY, START_AT_10, 5), 1_000_000);

		// The lecture has no linked sections in the input, so it's taken alone.
		assertThat(result.schedules()).extracting(SchedulingEngine.ScoredSchedule::sectionCrns)
			.containsExactly(List.of("12275"));
	}

	@Test
	void nodeCapIsPassedToTheSolver() {
		var result = engine.solve(new Problem(List.of(cse007("12275")), ANY, START_AT_10, 5), 1);

		assertThat(result.searchLimitReached()).isTrue();
	}

	@Test
	void catalogDataTheEngineRejectsIsAnIntegrationFailure() {
		var duplicateCrn = new Course("MATH-021", true, List.of(
				new Section("12275", "MATH-021", 4, "LECTURE", null, List.of())));

		assertThatThrownBy(() -> engine.solve(new Problem(List.of(cse007("12275"), duplicateCrn), ANY, START_AT_10, 5), 10))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void parentCrnKeepsNullAndLectureCrnsButRejectsBlankAndSelfLinks() {
		assertThat(NativeSchedulingEngine.parentCrn("1", null)).isNull();
		assertThat(NativeSchedulingEngine.parentCrn("2", "1")).isEqualTo("1");
		assertThatThrownBy(() -> NativeSchedulingEngine.parentCrn("1", " ")).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> NativeSchedulingEngine.parentCrn("1", "")).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> NativeSchedulingEngine.parentCrn("1", "1")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void labWithABlankLectureLinkIsRejectedNotScheduledAlone() {
		var malformed = new Course("CSE-007", true, List.of(
				new Section("13132", "CSE-007", 0, "LAB", " ",
						List.of(meeting("09:20", "10:35", DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)))));

		assertThatThrownBy(() -> engine.solve(new Problem(List.of(malformed), ANY, START_AT_10, 5), 1000))
			.isInstanceOf(IllegalStateException.class);
	}

}
