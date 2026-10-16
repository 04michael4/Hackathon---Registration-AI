package io.github.michael04.registration.ai;

import java.util.List;

import io.github.michael04.registration.ai.RawPreferences.RawTimeBlock;

/**
 * Development stand-in for Claude: ignores the text and returns the example from
 * docs/api-contract.md (endpoint 5), so the frontend can be built without an API key.
 * Enable with {@code AI_PROVIDER=dummy}.
 */
class DummyPreferenceExtractor implements PreferenceExtractor {

	static final String NOTICE = "Dummy AI mode: returned a fixed example; your text was not analyzed.";

	@Override
	public RawPreferences extract(String studentText) {
		return new RawPreferences(
				List.of("CSE 17", "Calc 1"),
				List.of(),
				null,
				null,
				null,
				List.of(new RawTimeBlock(List.of("TUESDAY", "THURSDAY"), "15:00", "18:00")),
				"10:00", 4,
				null, null,
				List.of("FRIDAY"), 3,
				null,
				List.of(NOTICE));
	}

}
