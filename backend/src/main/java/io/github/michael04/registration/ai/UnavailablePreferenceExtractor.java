package io.github.michael04.registration.ai;

/** Used when no AI provider is configured: every call fails with 503 so the frontend can fall back to the manual form. */
class UnavailablePreferenceExtractor implements PreferenceExtractor {

	@Override
	public RawPreferences extract(String studentText) {
		throw new AiUnavailableException("AI preference extraction is not configured");
	}

}
