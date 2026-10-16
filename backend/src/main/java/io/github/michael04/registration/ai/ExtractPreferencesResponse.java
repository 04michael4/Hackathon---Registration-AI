package io.github.michael04.registration.ai;

import java.util.List;

import io.github.michael04.registration.common.preferences.SchedulePreferences;

/**
 * Response of {@code POST /api/preferences/extract}.
 *
 * @param unresolved course mentions that couldn't be matched to the catalog
 * @param warnings assumptions and corrections the student should review
 */
public record ExtractPreferencesResponse(
		SchedulePreferences preferences,
		List<String> unresolved,
		List<String> warnings) {
}
