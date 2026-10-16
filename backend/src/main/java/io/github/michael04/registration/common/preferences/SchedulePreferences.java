package io.github.michael04.registration.common.preferences;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Structured scheduling preferences shared by the AI, backend, engine and frontend.
 * Shape is defined in docs/api-contract.md (endpoint 5).
 *
 * <p>Backend-owned (Marpu). The record shape was introduced on the AI branch; changes must stay
 * compatible with the AI assembler, the Schedule API and the frontend contract.
 */
public record SchedulePreferences(
		@Valid @NotNull HardConstraints hardConstraints,
		@Valid SoftPreferences softPreferences) {
}
