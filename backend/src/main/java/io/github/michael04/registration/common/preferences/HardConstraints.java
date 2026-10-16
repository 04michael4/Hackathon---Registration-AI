package io.github.michael04.registration.common.preferences;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Rules every generated schedule must satisfy. Credit bounds follow
 * docs/api-contract.md § Credit limits (hard cap 21).
 */
public record HardConstraints(
		@NotEmpty List<@NotBlank String> requiredCourseIds,
		@Min(1) @Max(CreditLimits.HARD_CAP) Integer minCredits,
		@Min(1) @Max(CreditLimits.HARD_CAP) Integer maxCredits,
		List<@NotNull @Valid TimeBlock> blockedTimes) {

	public HardConstraints {
		minCredits = minCredits == null ? CreditLimits.DEFAULT_MIN : minCredits;
		maxCredits = maxCredits == null ? CreditLimits.DEFAULT_MAX : maxCredits;
		blockedTimes = blockedTimes == null ? List.of() : blockedTimes;
	}

}
