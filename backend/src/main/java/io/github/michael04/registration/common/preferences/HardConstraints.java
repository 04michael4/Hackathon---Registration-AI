package io.github.michael04.registration.common.preferences;

import java.time.LocalTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Rules every generated schedule must satisfy. Credit bounds follow
 * docs/api-contract.md § Credit limits (hard cap 21).
 *
 * <p>{@code blockedTimes} forbid any overlap with a weekly range ("no classes before 10" is 00:00-10:00; "every class
 * must end by 10" is 10:00-23:59). {@code latestStart}, when set, requires every class to <em>begin</em> before that time,
 * though it may end later ("classes that start before 10").
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HardConstraints(
		@NotEmpty List<@NotBlank String> requiredCourseIds,
		@Min(1) @Max(CreditLimits.HARD_CAP) Integer minCredits,
		@Min(1) @Max(CreditLimits.HARD_CAP) Integer maxCredits,
		List<@NotNull @Valid TimeBlock> blockedTimes,
		LocalTime latestStart) {

	/** Without a latest-start rule (the shape before {@code latestStart} existed). */
	public HardConstraints(List<String> requiredCourseIds, Integer minCredits, Integer maxCredits, List<TimeBlock> blockedTimes) {
		this(requiredCourseIds, minCredits, maxCredits, blockedTimes, null);
	}

	public HardConstraints {
		minCredits = minCredits == null ? CreditLimits.DEFAULT_MIN : minCredits;
		maxCredits = maxCredits == null ? CreditLimits.DEFAULT_MAX : maxCredits;
		blockedTimes = blockedTimes == null ? List.of() : blockedTimes;
	}

}
