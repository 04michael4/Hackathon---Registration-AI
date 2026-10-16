package io.github.michael04.registration.engine;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * A weekly time block: the same start and end time on each of {@code days}.
 * Used both for class meetings and for the student's blocked times.
 *
 * <p>{@code days} is stored as an {@link EnumSet}, so it always iterates MONDAY to SUNDAY.
 * A meeting cannot cross midnight: {@code start} must be before {@code end}.
 */
public record Meeting(Set<DayOfWeek> days, LocalTime start, LocalTime end) {

	public Meeting {
		Objects.requireNonNull(days, "days");
		Objects.requireNonNull(start, "start");
		Objects.requireNonNull(end, "end");
		if (days.isEmpty()) {
			throw new IllegalArgumentException("days must not be empty");
		}
		if (!start.isBefore(end)) {
			throw new IllegalArgumentException("start must be before end: " + start + "-" + end);
		}
		days = Collections.unmodifiableSet(EnumSet.copyOf(days));
	}

}
