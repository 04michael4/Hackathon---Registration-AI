package io.github.michael04.registration.common.preferences;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Structured scheduling preferences shared by the AI, backend, engine and frontend.
 * Shape is defined in docs/api-contract.md (endpoint 5).
 *
 * <p>TEMPORARY STAND-IN created on the AI branch so AI work isn't blocked.
 * Owned by the Backend API module (Marpu): replace or extend freely, keeping the JSON contract.
 */
public record SchedulePreferences(
		@Valid @NotNull HardConstraints hardConstraints,
		@Valid SoftPreferences softPreferences) {
}
