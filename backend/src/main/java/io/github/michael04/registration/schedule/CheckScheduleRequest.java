package io.github.michael04.registration.schedule;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.github.michael04.registration.common.preferences.SchedulePreferences;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Manual mode ({@code POST /api/schedules/check}): the sections the student has placed, checked against their
 * preferences. {@code courseIds} lists extra courses on the board (e.g. electives found by search) besides the
 * required and optional ones in {@code preferences}; every course in play gets fit hints for its sections.
 */
public record CheckScheduleRequest(@NotBlank String term,
		@NotNull @Valid SchedulePreferences preferences,
		@Size(max = CheckScheduleRequest.MAX_EXTRA_COURSES) List<@NotBlank String> courseIds,
		@NotNull @Size(max = 60) List<@NotBlank String> crns,
		Boolean includeClosedSections) {

	/** Bounds the catalog lookup; manual mode itself has no elective limit. */
	public static final int MAX_EXTRA_COURSES = 30;

	public CheckScheduleRequest {
		courseIds = courseIds == null ? List.of() : courseIds;
		includeClosedSections = includeClosedSections == null ? false : includeClosedSections;
	}

	@AssertTrue(message = "minCredits must not exceed maxCredits")
	@JsonIgnore
	public boolean isCreditRangeValid() {
		var hard = preferences == null ? null : preferences.hardConstraints();
		return hard == null || hard.minCredits() <= hard.maxCredits();
	}
}
