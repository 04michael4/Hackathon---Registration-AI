package io.github.michael04.registration.demo;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request of the demo-only {@code POST /api/demo/schedules/generate}. {@code courseIds} are the courses the student wants,
 * {@code optionalCourseIds} are nice-to-have. The profile is optional; without it the timetable is still proposed and
 * eligibility stays unverified.
 */
public record DemoGenerateRequest(
		@NotEmpty @Size(max = 12) List<@NotBlank String> courseIds,
		@Size(max = 10) List<@NotBlank String> optionalCourseIds,
		@Min(1) @Max(21) Integer minCredits,
		@Min(1) @Max(21) Integer maxCredits,
		@Min(1) @Max(10) Integer maxResults,
		@Valid Profile profile) {

	public DemoGenerateRequest {
		optionalCourseIds = optionalCourseIds == null ? List.of() : optionalCourseIds;
		minCredits = minCredits == null ? 1 : minCredits;
		maxCredits = maxCredits == null ? 21 : maxCredits;
		maxResults = maxResults == null ? 5 : maxResults;
	}

	public record Profile(
			@Pattern(regexp = "FR|SO|JR|SR|GR") String classStanding,
			@Pattern(regexp = "UG|GR") String level,
			@Size(max = 6) String college,
			@Size(max = 6) List<@Size(max = 12) String> majors) {

		DemoProfile toProfile() {
			return new DemoProfile(classStanding, level, college, majors);
		}
	}

	@AssertTrue(message = "minCredits must not exceed maxCredits")
	@JsonIgnore
	public boolean isCreditRangeValid() {
		return minCredits <= maxCredits;
	}

}
