package io.github.michael04.registration.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/preferences/extract}. */
public record ExtractPreferencesRequest(
		@NotBlank String term,
		@NotBlank @Size(max = 2000) String text) {
}
