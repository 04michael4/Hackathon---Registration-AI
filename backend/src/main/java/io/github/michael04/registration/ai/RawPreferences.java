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
		List<String> assumptions,
		Integer minBreakMinutes,
		Integer minBreakWeight,
		List<String> interestTopics,
		String latestStart) {

	/** Without a latest-start rule (the shape before {@code latestStart} existed). */
	public RawPreferences(List<String> requiredCourses, List<String> optionalCourses, Integer optionalCoursesWeight,
			Integer minCredits, Integer maxCredits, List<RawTimeBlock> blockedTimes, String earliestStart,
			Integer earliestStartWeight, String latestEnd, Integer latestEndWeight, List<String> daysOff,
			Integer daysOffWeight, Integer minimizeGapsWeight, List<String> assumptions, Integer minBreakMinutes,
			Integer minBreakWeight, List<String> interestTopics) {
		this(requiredCourses, optionalCourses, optionalCoursesWeight, minCredits, maxCredits, blockedTimes, earliestStart,
				earliestStartWeight, latestEnd, latestEndWeight, daysOff, daysOffWeight, minimizeGapsWeight, assumptions,
				minBreakMinutes, minBreakWeight, interestTopics, null);
	}

	/** Without interest keywords (the shape before {@code interestTopics} existed). */
	public RawPreferences(List<String> requiredCourses, List<String> optionalCourses, Integer optionalCoursesWeight,
			Integer minCredits, Integer maxCredits, List<RawTimeBlock> blockedTimes, String earliestStart,
			Integer earliestStartWeight, String latestEnd, Integer latestEndWeight, List<String> daysOff,
			Integer daysOffWeight, Integer minimizeGapsWeight, List<String> assumptions, Integer minBreakMinutes,
			Integer minBreakWeight) {
		this(requiredCourses, optionalCourses, optionalCoursesWeight, minCredits, maxCredits, blockedTimes, earliestStart,
				earliestStartWeight, latestEnd, latestEndWeight, daysOff, daysOffWeight, minimizeGapsWeight, assumptions,
				minBreakMinutes, minBreakWeight, List.of(), null);
	}

	/** Without a minimum-break wish (the shape before {@code minBreakMinutes} existed). */
	public RawPreferences(List<String> requiredCourses, List<String> optionalCourses, Integer optionalCoursesWeight,
			Integer minCredits, Integer maxCredits, List<RawTimeBlock> blockedTimes, String earliestStart,
			Integer earliestStartWeight, String latestEnd, Integer latestEndWeight, List<String> daysOff,
			Integer daysOffWeight, Integer minimizeGapsWeight, List<String> assumptions) {
		this(requiredCourses, optionalCourses, optionalCoursesWeight, minCredits, maxCredits, blockedTimes, earliestStart,
				earliestStartWeight, latestEnd, latestEndWeight, daysOff, daysOffWeight, minimizeGapsWeight, assumptions,
				null, null, List.of(), null);
	}

	public record RawTimeBlock(List<String> days, String start, String end) {
	}

}
