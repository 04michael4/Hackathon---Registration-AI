package io.github.michael04.registration.common.preferences;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** A preference value with an importance weight from 0 (ignore) to 5 (very important). */
public record Weighted<T>(
		@NotNull T value,
		@JsonProperty(required = true) @JsonSetter(nulls = Nulls.FAIL)
		@Min(Weights.MIN) @Max(Weights.MAX) int weight) {
}
