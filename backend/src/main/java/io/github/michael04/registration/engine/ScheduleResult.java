package io.github.michael04.registration.engine;

import java.util.List;
import java.util.Objects;

/**
 * Output of {@link ScheduleSolver#solve}.
 *
 * @param schedules best first; empty if no feasible schedule was found
 * @param diagnostics human-readable reasons when results are empty or limited
 * @param searchLimitReached {@code true} if the search stopped at its node cap; the schedules
 *        are still valid but may not be the global best
 */
public record ScheduleResult(List<ScoredSchedule> schedules, List<String> diagnostics,
		boolean searchLimitReached) {

	public ScheduleResult {
		schedules = List.copyOf(Objects.requireNonNull(schedules, "schedules"));
		diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
	}

}
