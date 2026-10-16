package io.github.michael04.registration.engine;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * An elective that can be added to a schedule as it is: no conflicts, no blocked times, within the
 * credit maximum.
 *
 * @param crns the sections to add: the lecture first, then one linked section per component
 * @param totalCredits credits of the schedule after adding it
 * @param score and {@code breakdown}: the schedule's time-preference score after adding it (earliest
 *        start, latest end, days off, gaps), on the same 0–100 scale as generated schedules
 */
public record ElectiveSuggestion(String courseId, List<String> crns, int totalCredits, int score,
		SortedMap<String, Integer> breakdown) {

	public ElectiveSuggestion {
		Objects.requireNonNull(courseId, "courseId");
		crns = List.copyOf(Objects.requireNonNull(crns, "crns"));
		breakdown = Collections.unmodifiableSortedMap(new TreeMap<>(Objects.requireNonNull(breakdown, "breakdown")));
	}

}
