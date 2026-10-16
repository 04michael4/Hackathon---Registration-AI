package io.github.michael04.registration.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Depth-first search over lecture+lab bundles with pruning and a bounded top-K, as described in
 * docs/architecture.md § 7.
 *
 * <ol>
 * <li>Expand each course into bundles; drop bundles that conflict internally or overlap a blocked
 * time.</li>
 * <li>Search required courses first, then optional ones (each may also be skipped), courses with
 * the fewest bundles first. Branches are cut on a time conflict, on the credit range, and when
 * their lower-bound penalty can't beat the current K-th best.</li>
 * <li>Stop after {@code nodeLimit} search nodes and report {@code searchLimitReached}.</li>
 * </ol>
 *
 * <p>Deterministic: courses, bundles and sections are put in a canonical order first, so input
 * order doesn't matter. Among schedules with equal ranking value, the one found first in that
 * canonical order is kept. Stateless and thread-safe.
 */
public final class BacktrackingScheduleSolver implements ScheduleSolver {

	public static final int DEFAULT_NODE_LIMIT = 1_000_000;

	private final int nodeLimit;

	public BacktrackingScheduleSolver() {
		this(DEFAULT_NODE_LIMIT);
	}

	public BacktrackingScheduleSolver(int nodeLimit) {
		if (nodeLimit < 1) {
			throw new IllegalArgumentException("nodeLimit must be at least 1: " + nodeLimit);
		}
		this.nodeLimit = nodeLimit;
	}

	@Override
	public ScheduleResult solve(ScheduleProblem problem) {
		Objects.requireNonNull(problem, "problem");
		ScheduleResult result = new Search(problem, nodeLimit).run();
		if (!result.schedules().isEmpty()) {
			return result;
		}
		List<String> diagnostics = new ArrayList<>(result.diagnostics());
		diagnostics.addAll(suggestions(problem));
		return new ScheduleResult(List.of(), diagnostics, result.searchLimitReached());
	}

	/** For each required course, whether making it optional would give any schedule. */
	private List<String> suggestions(ScheduleProblem problem) {
		List<String> suggestions = new ArrayList<>();
		List<CourseOption> required = problem.courses().stream()
			.filter(CourseOption::required)
			.sorted(Comparator.comparing(CourseOption::courseId))
			.toList();
		for (CourseOption course : required) {
			List<CourseOption> relaxed = problem.courses().stream()
				.map(c -> c == course ? new CourseOption(c.courseId(), false, c.sections()) : c)
				.toList();
			ScheduleProblem relaxedProblem = new ScheduleProblem(relaxed, problem.constraints(), problem.soft(),
					problem.maxResults());
			if (!new Search(relaxedProblem, nodeLimit).run().schedules().isEmpty()) {
				suggestions.add("Suggestion: making " + course.courseId()
						+ " optional gives schedules without it.");
			}
		}
		return suggestions;
	}

	/** A bundle with its precomputed search data; {@code index} is its row in the conflict table. */
	private record Option(Bundle bundle, int index, int earlyMinutes, int lateMinutes, int dayMask) {
	}

	private record Slot(String courseId, boolean required, List<Option> options, int minCredits, int maxCredits) {
	}

	private record Candidate(long value, List<Option> chosen, int credits, int earlyMinutes, int lateMinutes,
			int dayMask, int gapMinutes, int skipped) {
	}

	/** One run of the search. Holds mutable state, so it is never shared. */
	private static final class Search {

		private final ScheduleProblem problem;
		private final int nodeLimit;
		private final ScheduleScorer scorer;
		private final Constraints constraints;
		private final List<String> diagnostics = new ArrayList<>();
		private final List<Slot> slots = new ArrayList<>();
		private final List<Candidate> top = new ArrayList<>();
		private final List<Option> chosen = new ArrayList<>();
		private boolean[][] conflicts;
		private int[] suffixMaxCredits;
		private int[] suffixRequiredMinCredits;
		// What the not-yet-placed required courses must add at least, for a tighter lower bound.
		private int[] suffixRequiredEarly;
		private int[] suffixRequiredLate;
		private int[] suffixRequiredDayMask;
		private int initialSkipped;
		private int nodes;
		private boolean limitReached;

		Search(ScheduleProblem problem, int nodeLimit) {
			this.problem = problem;
			this.nodeLimit = nodeLimit;
			this.constraints = problem.constraints();
			int optionalCount = (int) problem.courses().stream().filter(c -> !c.required()).count();
			this.scorer = new ScheduleScorer(problem.soft(), optionalCount);
		}

		ScheduleResult run() {
			if (!buildSlots() || !checkCredits()) {
				return new ScheduleResult(List.of(), diagnostics, false);
			}
			buildConflictTable();
			buildSuffixSums();
			search(0, 0, 0, 0, 0, initialSkipped);

			if (top.isEmpty()) {
				explainNoSchedule();
			}
			else if (limitReached) {
				diagnostics.add("The search stopped at its limit of " + nodeLimit
						+ " steps. These are the best schedules found, not necessarily the best possible.");
			}
			List<ScoredSchedule> schedules = top.stream()
				.sorted(Comparator.comparingLong(Candidate::value).thenComparingInt(Candidate::skipped)
					.thenComparing(Search::sortedCrns, Search::compareLists))
				.map(this::toScoredSchedule)
				.toList();
			return new ScheduleResult(schedules, diagnostics, limitReached);
		}

		/** Returns false if a required course has no usable bundle, so no schedule can exist. */
		private boolean buildSlots() {
			boolean feasible = true;
			List<Slot> required = new ArrayList<>();
			List<Slot> optional = new ArrayList<>();
			List<CourseOption> courses = problem.courses().stream()
				.sorted(Comparator.comparing(CourseOption::courseId))
				.toList();
			for (CourseOption course : courses) {
				List<Bundle> all = Bundle.expand(course);
				List<Bundle> coherent = all.stream().filter(b -> !b.hasInternalConflict()).toList();
				List<Bundle> usable = coherent.stream().filter(b -> !b.overlapsAny(constraints.blockedTimes())).toList();
				if (usable.isEmpty()) {
					String reason = all.isEmpty() ? "no sections available"
							: coherent.isEmpty() ? "no lecture/lab combination fits together without a time conflict"
							: "every section overlaps your blocked times";
					if (course.required()) {
						diagnostics.add(course.courseId() + ": " + reason + ".");
						feasible = false;
					}
					else {
						diagnostics.add("Optional course " + course.courseId() + " can't be added: " + reason + ".");
						initialSkipped++;
					}
					continue;
				}
				List<Option> options = usable.stream()
					.map(this::toOption)
					.sorted(Comparator.comparingLong(this::standaloneValue).thenComparing(o -> o.bundle().key()))
					.toList();
				int min = options.stream().mapToInt(o -> o.bundle().credits()).min().orElseThrow();
				int max = options.stream().mapToInt(o -> o.bundle().credits()).max().orElseThrow();
				(course.required() ? required : optional)
					.add(new Slot(course.courseId(), course.required(), options, min, max));
			}
			// Fewest options first finds dead ends early; courseId breaks ties.
			Comparator<Slot> order = Comparator.<Slot>comparingInt(s -> s.options().size()).thenComparing(Slot::courseId);
			required.sort(order);
			optional.sort(order);
			slots.addAll(required);
			slots.addAll(optional);
			return feasible;
		}

		private Option toOption(Bundle bundle) {
			int early = 0;
			int late = 0;
			for (Meeting meeting : bundle.meetings()) {
				early += scorer.earlyMinutes(meeting);
				late += scorer.lateMinutes(meeting);
			}
			int mask = ScheduleScorer.dayMask(bundle.meetings().stream().flatMap(m -> m.days().stream()).toList());
			return new Option(bundle, -1, early, late, mask);
		}

		private long standaloneValue(Option option) {
			return scorer.rankKey(option.earlyMinutes(), option.lateMinutes(), option.dayMask(), 0, 0);
		}

		private boolean checkCredits() {
			int requiredMin = slots.stream().filter(Slot::required).mapToInt(Slot::minCredits).sum();
			int possibleMax = slots.stream().mapToInt(Slot::maxCredits).sum();
			boolean ok = true;
			if (requiredMin > constraints.maxCredits()) {
				diagnostics.add("Your required courses need at least " + requiredMin
						+ " credits, above your maximum of " + constraints.maxCredits() + ".");
				ok = false;
			}
			if (possibleMax < constraints.minCredits()) {
				diagnostics.add("These courses add up to at most " + possibleMax
						+ " credits, below your minimum of " + constraints.minCredits() + ".");
				int shortfall = constraints.minCredits() - possibleMax;
				diagnostics.add("Suggestion: add courses worth at least " + shortfall + " more credits (for example as electives)"
						+ (possibleMax > 0 ? ", or lower your minimum credits to " + possibleMax : "") + ".");
				ok = false;
			}
			return ok;
		}

		private void buildConflictTable() {
			List<Option> all = new ArrayList<>();
			for (int s = 0; s < slots.size(); s++) {
				Slot slot = slots.get(s);
				List<Option> indexed = new ArrayList<>();
				for (Option option : slot.options()) {
					Option withIndex = new Option(option.bundle(), all.size(), option.earlyMinutes(),
							option.lateMinutes(), option.dayMask());
					indexed.add(withIndex);
					all.add(withIndex);
				}
				slots.set(s, new Slot(slot.courseId(), slot.required(), List.copyOf(indexed), slot.minCredits(),
						slot.maxCredits()));
			}
			conflicts = new boolean[all.size()][all.size()];
			for (int i = 0; i < all.size(); i++) {
				for (int j = i + 1; j < all.size(); j++) {
					boolean conflict = all.get(i).bundle().conflictsWith(all.get(j).bundle());
					conflicts[i][j] = conflict;
					conflicts[j][i] = conflict;
				}
			}
		}

		private void buildSuffixSums() {
			int n = slots.size();
			suffixMaxCredits = new int[n + 1];
			suffixRequiredMinCredits = new int[n + 1];
			suffixRequiredEarly = new int[n + 1];
			suffixRequiredLate = new int[n + 1];
			suffixRequiredDayMask = new int[n + 1];
			for (int i = n - 1; i >= 0; i--) {
				Slot slot = slots.get(i);
				suffixMaxCredits[i] = suffixMaxCredits[i + 1] + slot.maxCredits();
				suffixRequiredMinCredits[i] = suffixRequiredMinCredits[i + 1] + (slot.required() ? slot.minCredits() : 0);
				int early = 0;
				int late = 0;
				int sharedDays = 0;
				if (slot.required()) {
					early = slot.options().stream().mapToInt(Option::earlyMinutes).min().orElseThrow();
					late = slot.options().stream().mapToInt(Option::lateMinutes).min().orElseThrow();
					sharedDays = slot.options().stream().mapToInt(Option::dayMask).reduce(~0, (a, b) -> a & b);
				}
				suffixRequiredEarly[i] = suffixRequiredEarly[i + 1] + early;
				suffixRequiredLate[i] = suffixRequiredLate[i + 1] + late;
				suffixRequiredDayMask[i] = suffixRequiredDayMask[i + 1] | sharedDays;
			}
		}

		private void search(int depth, int credits, int early, int late, int mask, int skipped) {
			if (limitReached) {
				return;
			}
			if (++nodes > nodeLimit) {
				limitReached = true;
				return;
			}
			if (credits + suffixMaxCredits[depth] < constraints.minCredits()
					|| credits + suffixRequiredMinCredits[depth] > constraints.maxCredits()) {
				return;
			}
			// Gaps can shrink as classes are added, so a partial schedule's bound counts them as 0.
			long bound = scorer.rankKey(early + suffixRequiredEarly[depth], late + suffixRequiredLate[depth],
					mask | suffixRequiredDayMask[depth], 0, skipped);
			if (top.size() == problem.maxResults() && rank(bound, skipped, top.getLast()) >= 0) {
				return;
			}
			if (depth == slots.size()) {
				offer(credits, early, late, mask, skipped);
				return;
			}
			Slot slot = slots.get(depth);
			for (Option option : slot.options()) {
				if (credits + option.bundle().credits() > constraints.maxCredits() || conflictsWithChosen(option)) {
					continue;
				}
				chosen.add(option);
				search(depth + 1, credits + option.bundle().credits(), early + option.earlyMinutes(),
						late + option.lateMinutes(), mask | option.dayMask(), skipped);
				chosen.removeLast();
				if (limitReached) {
					return;
				}
			}
			if (!slot.required()) {
				search(depth + 1, credits, early, late, mask, skipped + 1);
			}
		}

		private boolean conflictsWithChosen(Option option) {
			for (Option other : chosen) {
				if (conflicts[option.index()][other.index()]) {
					return true;
				}
			}
			return false;
		}

		/**
		 * Keeps the best {@code maxResults} by rank key, then by fewer skipped electives (so on an equal score
		 * the schedule with more of the student's electives wins); on a full tie the earlier-found one stays.
		 */
		private void offer(int credits, int early, int late, int mask, int skipped) {
			if (chosen.isEmpty()) {
				return;
			}
			int gaps = ScheduleScorer.gapMinutes(chosen.stream().flatMap(o -> o.bundle().meetings().stream()).toList());
			long value = scorer.rankKey(early, late, mask, gaps, skipped);
			if (top.size() == problem.maxResults() && rank(value, skipped, top.getLast()) >= 0) {
				return;
			}
			int position = 0;
			while (position < top.size() && rank(value, skipped, top.get(position)) >= 0) {
				position++;
			}
			top.add(position, new Candidate(value, List.copyOf(chosen), credits, early, late, mask, gaps, skipped));
			if (top.size() > problem.maxResults()) {
				top.removeLast();
			}
		}

		/**
		 * Orders (value, skipped) against a kept candidate: negative if better. Both parts only grow during
		 * the search, so a partial schedule's pair is a valid lower bound in this order too.
		 */
		private static int rank(long value, int skipped, Candidate other) {
			int byValue = Long.compare(value, other.value());
			return byValue != 0 ? byValue : Integer.compare(skipped, other.skipped());
		}

		private void explainNoSchedule() {
			List<Slot> required = slots.stream()
				.filter(Slot::required)
				.sorted(Comparator.comparing(Slot::courseId))
				.toList();
			boolean explained = false;
			for (int a = 0; a < required.size(); a++) {
				for (int b = a + 1; b < required.size(); b++) {
					if (alwaysConflict(required.get(a), required.get(b))) {
						diagnostics.add(required.get(a).courseId() + " and " + required.get(b).courseId()
								+ " conflict in every combination.");
						explained = true;
					}
				}
			}
			if (limitReached) {
				diagnostics.add("The search stopped at its limit of " + nodeLimit
						+ " steps before finding a schedule.");
			}
			else if (!explained) {
				diagnostics.add("No combination of these courses fits together without a time conflict within "
						+ constraints.minCredits() + "–" + constraints.maxCredits() + " credits.");
			}
		}

		private boolean alwaysConflict(Slot a, Slot b) {
			for (Option x : a.options()) {
				for (Option y : b.options()) {
					if (!conflicts[x.index()][y.index()]) {
						return false;
					}
				}
			}
			return true;
		}

		private ScoredSchedule toScoredSchedule(Candidate candidate) {
			List<SectionOption> sections = candidate.chosen().stream()
				.map(Option::bundle)
				.sorted(Comparator.comparing(Bundle::courseId))
				.flatMap(bundle -> bundle.sections().stream())
				.toList();
			var breakdown = scorer.breakdown(candidate.earlyMinutes(), candidate.lateMinutes(), candidate.dayMask(),
					candidate.gapMinutes(), candidate.skipped());
			int penalty = breakdown.values().stream().mapToInt(Integer::intValue).sum();
			return new ScoredSchedule(sections, candidate.credits(), penalty, 100 - penalty, breakdown);
		}

		private static List<String> sortedCrns(Candidate candidate) {
			return candidate.chosen().stream()
				.flatMap(o -> o.bundle().sections().stream())
				.map(SectionOption::crn)
				.sorted()
				.toList();
		}

		private static int compareLists(List<String> a, List<String> b) {
			for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
				int c = a.get(i).compareTo(b.get(i));
				if (c != 0) {
					return c;
				}
			}
			return Integer.compare(a.size(), b.size());
		}

	}

}
