package io.github.michael04.registration.engine;

import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Recommendations about electives, on top of {@link ScheduleSolver}:
 * <ul>
 * <li>{@link #explainElectives}: for each elective the student typed in, whether it made the suggestions,
 * and if not, why and what would make it fit.</li>
 * <li>{@link #suggestElectives}: which candidate courses (e.g. from a catalog search) can be added to a
 * schedule as it is, best first.</li>
 * </ul>
 *
 * <p>To stay fast, "what would make it fit" re-solves only the required courses plus that one elective,
 * with one constraint relaxed at a time. Deterministic, like the solver; stateless and thread-safe.
 */
public final class ElectiveAdvisor {

	private final ScheduleSolver solver;

	public ElectiveAdvisor(ScheduleSolver solver) {
		this.solver = Objects.requireNonNull(solver, "solver");
	}

	/**
	 * One entry per optional course in {@code problem}, ordered by course ID. {@code result} must be the
	 * solver's result for {@code problem}.
	 */
	public List<ElectiveAdvice> explainElectives(ScheduleProblem problem, ScheduleResult result) {
		Objects.requireNonNull(problem, "problem");
		Objects.requireNonNull(result, "result");
		List<CourseOption> electives = problem.courses().stream()
			.filter(c -> !c.required())
			.sorted(Comparator.comparing(CourseOption::courseId))
			.toList();
		List<ElectiveAdvice> advice = new ArrayList<>();
		for (CourseOption elective : electives) {
			advice.add(explain(problem, result, elective));
		}
		return advice;
	}

	private ElectiveAdvice explain(ScheduleProblem problem, ScheduleResult result, CourseOption elective) {
		String id = elective.courseId();
		List<ScoredSchedule> schedules = result.schedules();
		for (int i = 0; i < schedules.size(); i++) {
			if (containsCourse(schedules.get(i), id)) {
				return i == 0
						? advice(id, ElectiveAdvice.Status.IN_TOP_SCHEDULE, id + " is in your top suggestion.")
						: new ElectiveAdvice(id, ElectiveAdvice.Status.IN_OTHER_SUGGESTION,
								id + " is in suggestion #" + (i + 1) + " (score " + schedules.get(i).score()
										+ "), not the top one.",
								List.of("Pick suggestion #" + (i + 1) + " to take it."), List.of());
			}
		}
		if (schedules.isEmpty()) {
			return advice(id, ElectiveAdvice.Status.CANNOT_FIT,
					"No schedule fits your required courses yet, so " + id + " can't be placed. Fix those first.");
		}

		ScheduleResult focused = solver.solve(focus(problem, elective, problem.constraints(), null));
		if (!focused.schedules().isEmpty()) {
			ScoredSchedule alternative = focused.schedules().getFirst();
			List<String> swaps = swaps(schedules.getFirst(), alternative, problem);
			List<String> fixes = new ArrayList<>(swaps);
			if (swaps.isEmpty()) {
				fixes.add("Your required courses can stay as they are. " + id
						+ " was left out for a better score or to make room for other electives; add it in manual mode.");
			}
			return new ElectiveAdvice(id, ElectiveAdvice.Status.FITS_WITH_CHANGES,
					id + " fits with your required courses (score " + alternative.score() + "), but not in your suggestions.",
					fixes, crns(alternative));
		}

		String summary = focused.diagnostics().stream()
			.filter(d -> !d.startsWith("Suggestion:"))
			.collect(Collectors.joining(" "));
		return new ElectiveAdvice(id, ElectiveAdvice.Status.CANNOT_FIT,
				"With " + id + " added, no schedule fits. " + summary, relaxations(problem, elective), List.of());
	}

	/** Fixes that each make the elective fit on their own: free one blocked time, raise credits, drop a course. */
	private List<String> relaxations(ScheduleProblem problem, CourseOption elective) {
		Constraints constraints = problem.constraints();
		List<String> fixes = new ArrayList<>();
		List<Meeting> blocked = constraints.blockedTimes();
		for (int i = 0; i < blocked.size(); i++) {
			List<Meeting> fewer = new ArrayList<>(blocked);
			Meeting freed = fewer.remove(i);
			Constraints relaxed = new Constraints(constraints.minCredits(), constraints.maxCredits(), fewer);
			if (fits(focus(problem, elective, relaxed, null))) {
				fixes.add("Free up your blocked time " + describe(freed) + ".");
			}
		}
		Constraints noCap = new Constraints(constraints.minCredits(), Integer.MAX_VALUE, blocked);
		ScheduleResult uncapped = solver.solve(focus(problem, elective, noCap, null));
		if (!uncapped.schedules().isEmpty() && uncapped.schedules().getFirst().totalCredits() > constraints.maxCredits()) {
			int needed = uncapped.schedules().stream().mapToInt(ScoredSchedule::totalCredits).min().orElseThrow();
			fixes.add("Raise your maximum credits to " + needed + ".");
		}
		problem.courses().stream()
			.filter(CourseOption::required)
			.sorted(Comparator.comparing(CourseOption::courseId))
			.forEach(required -> {
				if (fits(focus(problem, elective, constraints, required.courseId()))) {
					fixes.add("Take " + required.courseId() + " in another semester; it blocks " + elective.courseId() + ".");
				}
			});
		return fixes;
	}

	/** Whether a focused problem has a schedule (with the elective required, so it's always in it). */
	private boolean fits(ScheduleProblem focused) {
		return !solver.solve(focused).schedules().isEmpty();
	}

	/**
	 * The required courses plus {@code elective}, made required, without the other electives. {@code dropped}
	 * names a required course to make optional instead, or is {@code null}.
	 */
	private static ScheduleProblem focus(ScheduleProblem problem, CourseOption elective, Constraints constraints,
			String dropped) {
		List<CourseOption> courses = new ArrayList<>();
		for (CourseOption course : problem.courses()) {
			if (course == elective) {
				courses.add(new CourseOption(course.courseId(), true, course.sections()));
			}
			else if (course.required()) {
				boolean keep = !course.courseId().equals(dropped);
				courses.add(keep ? course : new CourseOption(course.courseId(), false, course.sections()));
			}
		}
		// The minimum is ignored: without the other electives it may be unreachable, which says nothing
		// about whether this elective fits.
		return new ScheduleProblem(courses, new Constraints(0, constraints.maxCredits(), constraints.blockedTimes()),
				problem.soft(), 1);
	}

	/** "Take MATH-021 20002 instead of 20001." for each required course whose sections differ. */
	private static List<String> swaps(ScoredSchedule top, ScoredSchedule alternative, ScheduleProblem problem) {
		Map<String, List<String>> before = crnsByCourse(top);
		Map<String, List<String>> after = crnsByCourse(alternative);
		List<String> swaps = new ArrayList<>();
		problem.courses().stream()
			.filter(CourseOption::required)
			.map(CourseOption::courseId)
			.sorted()
			.forEach(id -> {
				List<String> was = before.getOrDefault(id, List.of());
				List<String> now = after.getOrDefault(id, List.of());
				if (!was.equals(now)) {
					swaps.add("Take " + id + " " + String.join("+", now) + " instead of " + String.join("+", was) + ".");
				}
			});
		return swaps;
	}

	/**
	 * Candidate courses that can be added to the schedule {@code scheduleCrns} without any conflict, blocked
	 * time or going over the maximum credits. Each comes with its best-fitting sections. Ordered by the
	 * resulting time-preference score (best first), then course ID; at most {@code limit} entries.
	 * Candidates whose course is already in {@code problem} are skipped.
	 */
	public List<ElectiveSuggestion> suggestElectives(ScheduleProblem problem, Collection<String> scheduleCrns,
			List<CourseOption> candidates, int limit) {
		Objects.requireNonNull(problem, "problem");
		Objects.requireNonNull(scheduleCrns, "scheduleCrns");
		Objects.requireNonNull(candidates, "candidates");
		if (limit < 1) {
			throw new IllegalArgumentException("limit must be at least 1: " + limit);
		}
		SortedMap<String, SectionOption> byCrn = new TreeMap<>();
		problem.courses().forEach(c -> c.sections().forEach(s -> byCrn.put(s.crn(), s)));
		List<SectionOption> base = new ArrayList<>();
		for (String crn : scheduleCrns.stream().sorted().distinct().toList()) {
			SectionOption section = byCrn.get(crn);
			if (section == null) {
				throw new IllegalArgumentException("unknown crn in schedule: " + crn);
			}
			base.add(section);
		}
		List<Meeting> baseMeetings = base.stream().flatMap(s -> s.meetings().stream()).toList();
		int baseCredits = base.stream().mapToInt(SectionOption::credits).sum();
		Constraints constraints = problem.constraints();
		ScheduleScorer scorer = new ScheduleScorer(problem.soft(), 0);
		List<String> problemCourses = problem.courses().stream().map(CourseOption::courseId).toList();

		record Ranked(long key, ElectiveSuggestion suggestion) {
		}
		List<Ranked> ranked = new ArrayList<>();
		candidates.stream()
			.filter(c -> !problemCourses.contains(c.courseId()))
			.sorted(Comparator.comparing(CourseOption::courseId))
			.forEach(candidate -> {
				Ranked best = null;
				for (Bundle bundle : Bundle.expand(candidate)) {
					int credits = baseCredits + bundle.credits();
					if (credits > constraints.maxCredits() || bundle.hasInternalConflict()
							|| bundle.overlapsAny(constraints.blockedTimes()) || bundle.overlapsAny(baseMeetings)) {
						continue;
					}
					List<Meeting> meetings = new ArrayList<>(baseMeetings);
					meetings.addAll(bundle.meetings());
					int early = meetings.stream().mapToInt(scorer::earlyMinutes).sum();
					int late = meetings.stream().mapToInt(scorer::lateMinutes).sum();
					int mask = ScheduleScorer.dayMask(meetings.stream().flatMap(m -> m.days().stream()).toList());
					int gaps = ScheduleScorer.gapMinutes(meetings);
					long key = scorer.rankKey(early, late, mask, gaps, 0);
					if (best == null || key < best.key()) {
						SortedMap<String, Integer> breakdown = scorer.breakdown(early, late, mask, gaps, 0);
						int penalty = breakdown.values().stream().mapToInt(Integer::intValue).sum();
						best = new Ranked(key, new ElectiveSuggestion(candidate.courseId(),
								bundle.sections().stream().map(SectionOption::crn).toList(), credits, 100 - penalty,
								breakdown));
					}
				}
				if (best != null) {
					ranked.add(best);
				}
			});
		return ranked.stream()
			.sorted(Comparator.comparingLong(Ranked::key).thenComparing(r -> r.suggestion().courseId()))
			.limit(limit)
			.map(Ranked::suggestion)
			.toList();
	}

	private static boolean containsCourse(ScoredSchedule schedule, String courseId) {
		return schedule.sections().stream().anyMatch(s -> s.courseId().equals(courseId));
	}

	private static Map<String, List<String>> crnsByCourse(ScoredSchedule schedule) {
		Map<String, List<String>> map = new TreeMap<>();
		schedule.sections().forEach(s -> map.computeIfAbsent(s.courseId(), k -> new ArrayList<>()).add(s.crn()));
		return map;
	}

	private static List<String> crns(ScoredSchedule schedule) {
		return schedule.sections().stream().map(SectionOption::crn).toList();
	}

	/** e.g. "Mon/Wed 09:00–12:00". */
	private static String describe(Meeting meeting) {
		String days = meeting.days().stream()
			.map(d -> d.getDisplayName(TextStyle.SHORT, Locale.US))
			.collect(Collectors.joining("/"));
		return days + " " + meeting.start() + "–" + meeting.end();
	}

	private static ElectiveAdvice advice(String id, ElectiveAdvice.Status status, String summary) {
		return new ElectiveAdvice(id, status, summary, List.of(), List.of());
	}

}
