package io.github.michael04.registration.common.preferences;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;

/**
 * Weighted wishes used to rank schedules. A {@code null} field means the student
 * didn't express that preference, and it is omitted from the JSON.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SoftPreferences(
		@Valid Weighted<List<String>> optionalCourseIds,
		@Valid Weighted<LocalTime> earliestStart,
		@Valid Weighted<LocalTime> latestEnd,
		@Valid Weighted<List<DayOfWeek>> daysOff,
		@Valid GapsPreference minimizeGaps,
		@Valid Weighted<Integer> minBreakMinutes) {

	/** Without a minimum-break wish (the shape before {@code minBreakMinutes} existed). */
	public SoftPreferences(Weighted<List<String>> optionalCourseIds, Weighted<LocalTime> earliestStart,
			Weighted<LocalTime> latestEnd, Weighted<List<DayOfWeek>> daysOff, GapsPreference minimizeGaps) {
		this(optionalCourseIds, earliestStart, latestEnd, daysOff, minimizeGaps, null);
	}

	/** The wished-for break is 5 to 240 minutes (Hibernate Validator cannot constrain the value inside {@link Weighted}). */
	@AssertTrue(message = "minBreakMinutes must be between 5 and 240")
	@JsonIgnore
	public boolean isMinBreakInRange() {
		return minBreakMinutes == null || minBreakMinutes.value() == null
				|| (minBreakMinutes.value() >= 5 && minBreakMinutes.value() <= 240);
	}

	public static SoftPreferences none() {
		return new SoftPreferences(null, null, null, null, null, null);
	}

}
