package io.github.michael04.registration.common.preferences;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;

/**
 * Rules every generated schedule must satisfy. Credit bounds follow
 * docs/api-contract.md § Credit limits (hard cap 21).
 */
public record HardConstraints(
		@NotEmpty List<String> requiredCourseIds,
		@Min(1) @Max(CreditLimits.HARD_CAP) Integer minCredits,
		@Min(1) @Max(CreditLimits.HARD_CAP) Integer maxCredits,
		List<@Valid TimeBlock> blockedTimes) {

	public HardConstraints {
		requiredCourseIds = requiredCourseIds == null ? List.of() : List.copyOf(requiredCourseIds);
		blockedTimes = blockedTimes == null ? List.of() : List.copyOf(blockedTimes);
	}

}
