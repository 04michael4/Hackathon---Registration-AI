package io.github.michael04.registration.common.preferences;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * A weekly time range, e.g. TUESDAY/THURSDAY 15:00–18:00.
 * Days are de-duplicated and sorted MONDAY→SUNDAY so output order is deterministic.
 */
public record TimeBlock(
		@NotEmpty List<DayOfWeek> days,
		@NotNull LocalTime start,
		@NotNull LocalTime end) {

	public TimeBlock {
		days = days == null ? List.of() : days.stream().distinct().sorted().toList();
	}

}
