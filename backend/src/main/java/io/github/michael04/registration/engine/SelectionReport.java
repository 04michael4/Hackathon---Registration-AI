package io.github.michael04.registration.engine;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Result of {@link SelectionChecker#check}: the state of a hand-built schedule.
 *
 * @param sections the placed sections that exist in the problem, ordered by course ID, then each
 *        lecture before its linked sections
 * @param totalCredits credits of {@code sections}
 * @param score and {@code breakdown}: the same soft-preference scoring as generated schedules,
 *        applied to what's placed so far
 * @param issues conflicts first, then missing pieces, each group in a fixed order
 * @param candidates one entry per section not placed yet, ordered by course ID, then CRN
 */
public record SelectionReport(List<SectionOption> sections, int totalCredits, int score,
		SortedMap<String, Integer> breakdown, List<SelectionIssue> issues, List<SectionFit> candidates) {

	public SelectionReport {
		sections = List.copyOf(Objects.requireNonNull(sections, "sections"));
		breakdown = Collections.unmodifiableSortedMap(new TreeMap<>(Objects.requireNonNull(breakdown, "breakdown")));
		issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
		candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
	}

	/** {@code true} when nothing conflicts and nothing is missing: a schedule the student can use. */
	public boolean complete() {
		return issues.isEmpty();
	}

	/** {@code true} when something placed breaks a hard rule. */
	public boolean hasConflicts() {
		return issues.stream().anyMatch(issue -> issue.severity() == SelectionIssue.Severity.CONFLICT);
	}

}
