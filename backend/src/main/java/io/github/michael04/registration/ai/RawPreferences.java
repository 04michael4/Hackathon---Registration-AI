package io.github.michael04.registration.ai;

import java.util.List;

/**
 * What an extractor (Claude, or the dummy) returns <em>before</em> validation: course names
 * exactly as the student wrote them, times and days as plain strings, weights as given.
 * {@link PreferenceAssembler} turns this into validated {@code SchedulePreferences}.
 *
 * <p>Untrusted input: any field may be null, empty, or malformed.
 */
public record RawPreferences(
		List<String> requiredCourses,
		List<String> optionalCourses,
		Integer optionalCoursesWeight,
		Integer minCredits,
		Integer maxCredits,
		List<RawTimeBlock> blockedTimes,
		String earliestStart,
		Integer earliestStartWeight,
		String latestEnd,
		Integer latestEndWeight,
		List<String> daysOff,
		Integer daysOffWeight,
		Integer minimizeGapsWeight,
		List<String> assumptions) {

	public record RawTimeBlock(List<String> days, String start, String end) {
	}

}
