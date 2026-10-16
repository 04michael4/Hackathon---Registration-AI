package io.github.michael04.registration.engine;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * One conflict-free schedule that satisfies every hard constraint.
 *
 * <p>{@code sections} holds every chosen CRN, including linked labs and recitations, ordered by
 * course ID, then each lecture before its linked sections. {@code breakdown} maps each active soft
 * preference ({@code SoftWeights.*_KEY}) to its penalty points, 0–20; {@code penalty} is their
 * sum and {@code score = 100 - penalty}. See docs/architecture.md § 7 for the formula.
 */
public record ScoredSchedule(List<SectionOption> sections, int totalCredits, int penalty, int score,
		SortedMap<String, Integer> breakdown) {

	public ScoredSchedule {
		sections = List.copyOf(Objects.requireNonNull(sections, "sections"));
		breakdown = Collections.unmodifiableSortedMap(new TreeMap<>(Objects.requireNonNull(breakdown, "breakdown")));
	}

}
