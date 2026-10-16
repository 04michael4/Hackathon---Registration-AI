package io.github.michael04.registration.common.preferences;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Preference for back-to-back classes; only a weight, no value. */
public record GapsPreference(@Min(Weights.MIN) @Max(Weights.MAX) int weight) {
}
