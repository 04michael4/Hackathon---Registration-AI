package io.github.michael04.registration.schedule;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.github.michael04.registration.common.preferences.SchedulePreferences;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/schedules/suggest-electives}: electives from the catalog that fit the schedule {@code crns}.
 * {@code q} and {@code subject} narrow the catalog like course search; {@code courseIds} are the courses of the
 * schedule besides those in {@code preferences}.
 */
public record SuggestElectivesRequest(@NotBlank String term,
		@NotNull @Valid SchedulePreferences preferences,
		@Size(max = CheckScheduleRequest.MAX_EXTRA_COURSES) List<@NotBlank String> courseIds,
		@NotNull @Size(max = 60) List<@NotBlank String> crns,
		@Size(max = 100) String q,
		@Size(max = 10) String subject,
		@Min(1) @Max(20) Integer limit,
		Boolean includeClosedSections) {

	public SuggestElectivesRequest {
		courseIds = courseIds == null ? List.of() : courseIds;
		limit = limit == null ? 5 : limit;
		includeClosedSections = includeClosedSections == null ? false : includeClosedSections;
	}

	@AssertTrue(message = "minCredits must not exceed maxCredits")
	@JsonIgnore
	public boolean isCreditRangeValid() {
		var hard = preferences == null ? null : preferences.hardConstraints();
		return hard == null || hard.minCredits() <= hard.maxCredits();
	}
}
