package io.github.michael04.registration.engine;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Soft preferences: ranking signals, never validity rules. Each weight is 0–5, where 0 means
 * "ignore". {@code earliestStart} and {@code latestEnd} are {@code null} when the student
 * didn't set them. Which courses are optional is given by {@link CourseOption#required()};
 * {@code optionalCoursesWeight} says how much leaving them out costs.
 *
 * <p>The {@code *_KEY} constants are the keys of {@link ScoredSchedule#breakdown()}; they match
 * the {@code softPreferences} field names in docs/api-contract.md.
 */
public record SoftWeights(
		LocalTime earliestStart, int earliestStartWeight,
		LocalTime latestEnd, int latestEndWeight,
		Set<DayOfWeek> daysOff, int daysOffWeight,
		int gapsWeight,
		int optionalCoursesWeight) {

	public static final int MAX_WEIGHT = 5;

	public static final String EARLIEST_START_KEY = "earliestStart";
	public static final String LATEST_END_KEY = "latestEnd";
	public static final String DAYS_OFF_KEY = "daysOff";
	public static final String GAPS_KEY = "minimizeGaps";
	public static final String OPTIONAL_COURSES_KEY = "optionalCourseIds";

	public SoftWeights {
		requireWeight(earliestStartWeight, EARLIEST_START_KEY);
		requireWeight(latestEndWeight, LATEST_END_KEY);
		requireWeight(daysOffWeight, DAYS_OFF_KEY);
		requireWeight(gapsWeight, GAPS_KEY);
		requireWeight(optionalCoursesWeight, OPTIONAL_COURSES_KEY);
		Objects.requireNonNull(daysOff, "daysOff");
		daysOff = Collections.unmodifiableSet(
				daysOff.isEmpty() ? EnumSet.noneOf(DayOfWeek.class) : EnumSet.copyOf(daysOff));
	}

	/** No soft preferences: every feasible schedule scores 100. */
	public static SoftWeights none() {
		return new SoftWeights(null, 0, null, 0, Set.of(), 0, 0, 0);
	}

	private static void requireWeight(int weight, String name) {
		if (weight < 0 || weight > MAX_WEIGHT) {
			throw new IllegalArgumentException(name + " weight must be 0-" + MAX_WEIGHT + ": " + weight);
		}
	}

}
