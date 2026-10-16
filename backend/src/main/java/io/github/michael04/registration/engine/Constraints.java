package io.github.michael04.registration.engine;

import java.util.List;
import java.util.Objects;

/**
 * Hard constraints. Every returned schedule satisfies all of them.
 *
 * <p>Policy limits such as the 21-credit cap are enforced by the API layer before the engine
 * is called; the engine only requires {@code 0 <= minCredits <= maxCredits}.
 */
public record Constraints(int minCredits, int maxCredits, List<Meeting> blockedTimes) {

	public Constraints {
		if (minCredits < 0) {
			throw new IllegalArgumentException("minCredits must not be negative: " + minCredits);
		}
		if (maxCredits < minCredits) {
			throw new IllegalArgumentException(
					"maxCredits (" + maxCredits + ") must be >= minCredits (" + minCredits + ")");
		}
		blockedTimes = List.copyOf(Objects.requireNonNull(blockedTimes, "blockedTimes"));
	}

}
