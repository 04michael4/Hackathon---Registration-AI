package io.github.michael04.registration.schedule;

import io.github.michael04.registration.common.preferences.SchedulePreferences;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@ValidScheduleRequest
public record GenerateScheduleRequest(@NotBlank String term,
		@NotNull @Valid SchedulePreferences preferences,
		@Min(1) @Max(10) Integer maxResults, Boolean includeClosedSections) {

	public GenerateScheduleRequest {
		maxResults = maxResults == null ? 5 : maxResults;
		includeClosedSections = includeClosedSections == null ? false : includeClosedSections;
	}
}
