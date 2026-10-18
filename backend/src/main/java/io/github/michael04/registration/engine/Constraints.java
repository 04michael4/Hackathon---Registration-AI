package io.github.michael04.registration.engine;

import java.time.LocalTime;
import java.util.List;
import java.util.Objects;

/**
 * Hard constraints. Every returned schedule satisfies all of them.
 *
 * <p>Policy limits such as the 21-credit cap are enforced by the API layer before the engine
 * is called; the engine only requires {@code 0 <= minCredits <= maxCredits}.
 */
public record Constraints(int minCredits, int maxCredits, List<Meeting> blockedTimes, LocalTime latestStart) {

	/** Without a latest-start rule. */
	public Constraints(int minCredits, int maxCredits, List<Meeting> blockedTimes) {
		this(minCredits, maxCredits, blockedTimes, null);
	}

	/** True when this meeting overlaps no blocked time and, if a latest start is set, begins before it. */
	public boolean allows(Meeting meeting) {
		if (latestStart != null && !meeting.start().isBefore(latestStart)) {
			return false;
		}
		return blockedTimes.stream().noneMatch(meeting::overlaps);
	}

	/** True when this meeting begins at or after the latest allowed start (so the start rule, not a blocked time, rejects it). */
	public boolean startsTooLate(Meeting meeting) {
		return latestStart != null && !meeting.start().isBefore(latestStart);
	}

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
