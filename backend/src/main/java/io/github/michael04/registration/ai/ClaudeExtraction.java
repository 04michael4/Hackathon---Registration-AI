package io.github.michael04.registration.ai;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * The JSON shape Claude must return (structured output). The SDK derives the schema from this record:
 * {@code Optional} fields become nullable, so the model answers {@code null} instead of inventing a value
 * the student never gave, and days are restricted to {@code MONDAY}…{@code SUNDAY}.
 *
 * <p>Every property here is required: {@link ClaudeExtractionValidator} derives the required names and types from
 * this record and rejects a reply that omits one (a nullable field must be sent as an explicit {@code null}).
 * Adding a component here therefore makes it required automatically.
 *
 * <p>Converted to {@link RawPreferences} and then validated by {@link PreferenceAssembler} like any
 * other extractor's output; nothing here is trusted.
 */
record ClaudeExtraction(
		@JsonPropertyDescription("Courses the student says they must take, copied exactly as written (e.g. \"CSE 17\", \"Calc 1\"). Never add, expand, or correct course names or codes.")
		List<String> requiredCourses,
		@JsonPropertyDescription("Courses the student would take only if they fit, copied exactly as written.")
		List<String> optionalCourses,
		@JsonPropertyDescription("Importance 0-5 of fitting the optional courses; null if there are none.")
		Optional<Integer> optionalCoursesWeight,
		@JsonPropertyDescription("Minimum total credits the student explicitly states; null if not stated.")
		Optional<Integer> minCredits,
		@JsonPropertyDescription("Maximum total credits the student explicitly states (report what they said, even if high); null if not stated.")
		Optional<Integer> maxCredits,
		@JsonPropertyDescription("Specific weekly times the student cannot attend class (work, practice, appointments). Empty if none.")
		List<ClaudeTimeBlock> blockedTimes,
		@JsonPropertyDescription("Preferred earliest class start, 24-hour HH:mm; null if not mentioned.")
		Optional<String> earliestStart,
		@JsonPropertyDescription("Importance 0-5 of earliestStart; null if earliestStart is null.")
		Optional<Integer> earliestStartWeight,
		@JsonPropertyDescription("Preferred latest class end, 24-hour HH:mm; null if not mentioned.")
		Optional<String> latestEnd,
		@JsonPropertyDescription("Importance 0-5 of latestEnd; null if latestEnd is null.")
		Optional<Integer> latestEndWeight,
		@JsonPropertyDescription("Days the student would like to have no classes. Empty if none.")
		List<DayOfWeek> daysOff,
		@JsonPropertyDescription("Importance 0-5 of daysOff; null if daysOff is empty.")
		Optional<Integer> daysOffWeight,
		@JsonPropertyDescription("Importance 0-5 of having classes close together with few gaps; null if not mentioned.")
		Optional<Integer> minimizeGapsWeight,
		@JsonPropertyDescription("Short notes on any interpretation, guess, contradiction, or ignored instruction. Empty if none.")
		List<String> assumptions) {

	/** A weekly time range the student can't attend. */
	record ClaudeTimeBlock(
			@JsonPropertyDescription("Days this applies to.") List<DayOfWeek> days,
			@JsonPropertyDescription("Start, 24-hour HH:mm.") String start,
			@JsonPropertyDescription("End, 24-hour HH:mm.") String end) {
	}

	RawPreferences toRawPreferences() {
		return new RawPreferences(
				copy(requiredCourses),
				copy(optionalCourses),
				orNull(optionalCoursesWeight),
				orNull(minCredits),
				orNull(maxCredits),
				blockedTimes == null ? List.of()
						: blockedTimes.stream()
							.filter(b -> b != null)
							.map(b -> new RawPreferences.RawTimeBlock(dayNames(b.days()), b.start(), b.end()))
							.toList(),
				orNull(earliestStart),
				orNull(earliestStartWeight),
				orNull(latestEnd),
				orNull(latestEndWeight),
				dayNames(daysOff),
				orNull(daysOffWeight),
				orNull(minimizeGapsWeight),
				copy(assumptions));
	}

	// Jackson leaves an Optional null (not empty) when the key is missing entirely, so handle both. Missing keys never
	// reach this point from a model reply: ClaudeExtractionValidator rejects them first. This stays as defense in depth.
	private static <T> T orNull(Optional<T> value) {
		return value == null ? null : value.orElse(null);
	}

	private static List<String> copy(List<String> values) {
		return values == null ? List.of() : values.stream().filter(v -> v != null).toList();
	}

	private static List<String> dayNames(List<DayOfWeek> days) {
		return days == null ? List.of() : days.stream().filter(d -> d != null).map(DayOfWeek::name).toList();
	}

}
