package io.github.michael04.registration.ai;

import java.util.List;

import io.github.michael04.registration.common.preferences.SchedulePreferences;

/**
 * Response of {@code POST /api/preferences/extract}.
 *
 * @param unresolved course mentions that couldn't be matched to the catalog
 * @param warnings assumptions and corrections the student should review
 * @param suggestions catalog courses that match the student's named courses or stated interests; the student decides what to add
 */
public record ExtractPreferencesResponse(
		SchedulePreferences preferences,
		List<String> unresolved,
		List<String> warnings,
		List<CourseSuggestion> suggestions) {

	/** Without suggestions (the shape before interest search existed). */
	public ExtractPreferencesResponse(SchedulePreferences preferences, List<String> unresolved, List<String> warnings) {
		this(preferences, unresolved, warnings, List.of());
	}

	/** A real catalog course the student may want, with the honest reason it was suggested and, when the catalog has them, one real section combination that fits (e.g. "Lecture 010 Mon/Wed 09:20–10:35"). Never planned automatically. */
	public record CourseSuggestion(String courseId, String title, int credits, String reason, String qualifying) {

		/** Without a verified section summary. */
		public CourseSuggestion(String courseId, String title, int credits, String reason) {
			this(courseId, title, credits, reason, null);
		}
	}
}
