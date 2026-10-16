package io.github.michael04.registration.common.preferences;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Preference for back-to-back classes; only a weight, no value. */
public record GapsPreference(@JsonProperty(required = true) @JsonSetter(nulls = Nulls.FAIL)
		@Min(Weights.MIN) @Max(Weights.MAX) int weight) {
}
