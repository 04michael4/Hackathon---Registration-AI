package io.github.michael04.registration.common.preferences;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * A weekly time range, e.g. TUESDAY/THURSDAY 15:00–18:00.
 * The Schedule API sorts and de-duplicates days after validation.
 */
public record TimeBlock(
		@NotEmpty List<@NotNull DayOfWeek> days,
		@NotNull LocalTime start,
		@NotNull LocalTime end) {

	@AssertTrue(message = "start must be before end")
	@JsonIgnore
	public boolean isOrdered() {
		return start == null || end == null || start.isBefore(end);
	}

}
