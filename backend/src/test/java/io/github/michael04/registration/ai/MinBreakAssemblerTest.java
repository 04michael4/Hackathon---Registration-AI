package io.github.michael04.registration.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.michael04.registration.ai.RawPreferences.RawTimeBlock;
import io.github.michael04.registration.common.preferences.HardConstraints;
import io.github.michael04.registration.common.preferences.SchedulePreferences;
import io.github.michael04.registration.common.preferences.SoftPreferences;
import io.github.michael04.registration.common.preferences.Weighted;
import io.github.michael04.registration.course.DummyCourseCatalog;
import jakarta.validation.Validation;

/** "At least an hour between classes" is a soft wish; "I cannot take classes before 10" arrives as a hard blocked time. */
class MinBreakAssemblerTest {

	private static final String TERM = DummyCourseCatalog.TERM;
	private final PreferenceAssembler assembler = new PreferenceAssembler(new CourseMentionResolver(new DummyCourseCatalog()));

	private static RawPreferences raw(Integer minBreak, Integer weight, List<RawTimeBlock> blocked) {
		return new RawPreferences(List.of("CSE 17", "Calc 1"), List.of(), null, null, null, blocked, null, null, null, null,
				List.of(), null, null, List.of(), minBreak, weight);
	}

	@Test
	void anHourBetweenClassesBecomesAWeightedSoftPreference() {
		var soft = assembler.assemble(TERM, raw(60, 4, List.of())).preferences().softPreferences();

		assertThat(soft.minBreakMinutes()).isEqualTo(new Weighted<>(60, 4));
		assertThat(soft.minimizeGaps()).as("the opposite wish (back to back) is not set").isNull();
	}

	@Test
	void anAbsoluteMorningLimitArrivesAsAHardBlockedTimeAndNotASoftWish() {
		var morning = new RawTimeBlock(List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"), "00:00", "10:00");

		var prefs = assembler.assemble(TERM, raw(60, 3, List.of(morning))).preferences();

		assertThat(prefs.hardConstraints().blockedTimes()).hasSize(1);
		assertThat(prefs.hardConstraints().blockedTimes().get(0).end()).isEqualTo(LocalTime.of(10, 0));
		assertThat(prefs.hardConstraints().blockedTimes().get(0).days()).contains(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
		assertThat(prefs.softPreferences().earliestStart()).isNull();
	}

	@Test
	void anOutOfRangeBreakIsDroppedWithAWarningRatherThanApplied() {
		var response = assembler.assemble(TERM, raw(500, 3, List.of()));

		assertThat(response.preferences().softPreferences().minBreakMinutes()).isNull();
		assertThat(response.warnings()).anyMatch(w -> w.contains("500 minutes"));
	}

	@Test
	void aZeroImportanceBreakWishIsDropped() {
		assertThat(assembler.assemble(TERM, raw(60, 0, List.of())).preferences().softPreferences().minBreakMinutes()).isNull();
	}

	@Test
	void requestValidationRejectsABreakOutsideFiveToTwoForty() {
		var validator = Validation.buildDefaultValidatorFactory().getValidator();
		var hard = new HardConstraints(List.of("CSE-017"), 3, 18, List.of());

		assertThat(validator.validate(new SchedulePreferences(hard, new SoftPreferences(null, null, null, null, null, new Weighted<>(300, 3))))).isNotEmpty();
		assertThat(validator.validate(new SchedulePreferences(hard, new SoftPreferences(null, null, null, null, null, new Weighted<>(60, 3))))).isEmpty();
	}
}
