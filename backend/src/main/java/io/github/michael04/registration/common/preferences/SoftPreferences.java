package io.github.michael04.registration.common.preferences;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.Valid;

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
		@Valid GapsPreference minimizeGaps) {

	public static SoftPreferences none() {
		return new SoftPreferences(null, null, null, null, null);
	}

}
