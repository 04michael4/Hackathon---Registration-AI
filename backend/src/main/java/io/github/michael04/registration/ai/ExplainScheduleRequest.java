package io.github.michael04.registration.ai;

import java.util.List;

import io.github.michael04.registration.common.preferences.SchedulePreferences;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code crns} may be empty: the engine then searches itself and explains the best schedule, or why there is none. */
public record ExplainScheduleRequest(@NotBlank String term,
		@NotNull @Size(max = 100) List<@NotBlank String> crns,
		@NotNull @Valid SchedulePreferences preferences) {}
