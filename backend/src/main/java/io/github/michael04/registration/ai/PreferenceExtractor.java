package io.github.michael04.registration.ai;

/**
 * Turns a student's free text into {@link RawPreferences}. Implementations only interpret
 * language; resolving course names and validating values happens afterwards in Java.
 */
public interface PreferenceExtractor {

	/**
	 * @throws AiUnavailableException if the AI provider isn't configured or can't be reached
	 */
	RawPreferences extract(String studentText);

}
