package io.github.michael04.registration.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Checks a schedule the student builds by hand (manual mode), with the same hard rules and scoring
 * as {@link BacktrackingScheduleSolver}. For the current selection it reports what conflicts and
 * what's still missing; for every section not placed yet it says whether it would fit, so the UI
 * can grey out sections and highlight the labs a placed lecture still needs.
 *
 * <p>Pure and deterministic: the order of {@code selectedCrns} and of the problem's courses and
 * sections doesn't change the report. Stateless and thread-safe.
 */
public final class SelectionChecker {

	private static final Comparator<SectionOption> PLACEMENT_ORDER = Comparator
		.comparing(SectionOption::courseId)
		.thenComparing(s -> s.isPrimary() ? s.crn() : s.parentCrn())
		.thenComparing(s -> s.isPrimary() ? 0 : 1)
		.thenComparing(SectionOption::component)
		.thenComparing(SectionOption::crn);

	public SelectionReport check(ScheduleProblem problem, Collection<String> selectedCrns) {
		Objects.requireNonNull(problem, "problem");
		Objects.requireNonNull(selectedCrns, "selectedCrns");
		SortedMap<String, SectionOption> sectionsByCrn = new TreeMap<>();
		SortedMap<String, CourseOption> coursesById = new TreeMap<>();
		for (CourseOption course : problem.courses()) {
			coursesById.put(course.courseId(), course);
			course.sections().forEach(section -> sectionsByCrn.put(section.crn(), section));
		}
		SortedSet<String> requested = new TreeSet<>();
		selectedCrns.forEach(crn -> requested.add(Objects.requireNonNull(crn, "selected crn")));

		List<SelectionIssue> conflicts = new ArrayList<>();
		List<SelectionIssue> missing = new ArrayList<>();
		List<SectionOption> placed = new ArrayList<>();
		for (String crn : requested) {
			SectionOption section = sectionsByCrn.get(crn);
			if (section == null) {
				conflicts.add(issue(SelectionIssue.Type.UNKNOWN_SECTION, List.of(crn),
						"Section " + crn + " isn't offered for these courses."));
			}
			else {
				placed.add(section);
			}
		}
		placed.sort(PLACEMENT_ORDER);
		Selection selection = new Selection(placed);
		Constraints constraints = problem.constraints();

		for (var entry : selection.lecturesByCourse.entrySet()) {
			if (entry.getValue().size() > 1) {
				List<String> crns = crns(entry.getValue());
				conflicts.add(issue(SelectionIssue.Type.DUPLICATE_COURSE, crns, entry.getKey() + " has "
						+ crns.size() + " lectures placed (" + String.join(", ", crns) + "). Keep one."));
			}
		}
		for (var entry : selection.linkedByLectureAndComponent.entrySet()) {
			if (entry.getValue().size() > 1) {
				SectionOption first = entry.getValue().getFirst();
				List<String> crns = crns(entry.getValue());
				conflicts.add(issue(SelectionIssue.Type.DUPLICATE_COMPONENT, crns, "Lecture " + first.parentCrn()
						+ " (" + first.courseId() + ") has " + crns.size() + " " + first.component()
						+ " sections placed (" + String.join(", ", crns) + "). Keep one."));
			}
		}
		for (int i = 0; i < placed.size(); i++) {
			for (int j = i + 1; j < placed.size(); j++) {
				if (overlaps(placed.get(i), placed.get(j))) {
					conflicts.add(issue(SelectionIssue.Type.TIME_CONFLICT,
							List.of(placed.get(i).crn(), placed.get(j).crn()),
							label(placed.get(i)) + " and " + label(placed.get(j)) + " meet at the same time."));
				}
			}
		}
		for (SectionOption section : placed) {
			if (overlapsBlocked(section, constraints)) {
				conflicts.add(issue(SelectionIssue.Type.BLOCKED_TIME, List.of(section.crn()),
						label(section) + " overlaps one of your blocked times."));
			}
			else if (section.meetings().stream().anyMatch(constraints::startsTooLate)) {
				conflicts.add(issue(SelectionIssue.Type.BLOCKED_TIME, List.of(section.crn()),
						label(section) + " starts at or after your latest allowed start of " + constraints.latestStart() + "."));
			}
		}
		if (selection.credits > constraints.maxCredits()) {
			conflicts.add(issue(SelectionIssue.Type.OVER_MAX_CREDITS, List.of(), "Placed sections total "
					+ selection.credits + " credits, above your maximum of " + constraints.maxCredits() + "."));
		}

		for (SectionOption section : placed) {
			if (!section.isPrimary() && !selection.isPlaced(section.parentCrn())) {
				missing.add(issue(SelectionIssue.Type.LINKED_WITHOUT_LECTURE, List.of(section.crn()),
						label(section) + " is a " + section.component() + " and needs its lecture "
								+ section.parentCrn() + "."));
			}
		}
		for (SectionOption lecture : placed) {
			if (!lecture.isPrimary()) {
				continue;
			}
			for (String component : linkedComponents(coursesById.get(lecture.courseId()), lecture.crn())) {
				if (!selection.linkedByLectureAndComponent.containsKey(lecture.crn() + "|" + component)) {
					missing.add(issue(SelectionIssue.Type.MISSING_LINKED_SECTION, List.of(lecture.crn()),
							lecture.courseId() + " lecture " + lecture.crn() + " needs a " + component + "."));
				}
			}
		}
		for (CourseOption course : coursesById.values()) {
			if (course.required() && !selection.lecturesByCourse.containsKey(course.courseId())) {
				missing.add(issue(SelectionIssue.Type.REQUIRED_COURSE_MISSING, List.of(),
						"Required course " + course.courseId() + " isn't placed yet."));
			}
		}
		if (selection.credits < constraints.minCredits()) {
			missing.add(issue(SelectionIssue.Type.UNDER_MIN_CREDITS, List.of(), "Placed sections total "
					+ selection.credits + " credits, below your minimum of " + constraints.minCredits() + "."));
		}

		List<SelectionIssue> issues = new ArrayList<>(conflicts);
		issues.addAll(missing);
		SortedMap<String, Integer> breakdown = breakdown(problem, coursesById, selection);
		int penalty = breakdown.values().stream().mapToInt(Integer::intValue).sum();
		return new SelectionReport(placed, selection.credits, 100 - penalty, breakdown, issues,
				candidates(coursesById, requested, selection, constraints));
	}

	private static List<SectionFit> candidates(SortedMap<String, CourseOption> coursesById, SortedSet<String> requested,
			Selection selection, Constraints constraints) {
		List<SectionFit> candidates = new ArrayList<>();
		for (CourseOption course : coursesById.values()) {
			List<SectionOption> sections = course.sections().stream()
				.filter(s -> !requested.contains(s.crn()))
				.sorted(Comparator.comparing(SectionOption::crn))
				.toList();
			for (SectionOption section : sections) {
				List<String> reasons = new ArrayList<>();
				if (section.isPrimary()) {
					List<SectionOption> lectures = selection.lecturesByCourse.get(course.courseId());
					if (lectures != null) {
						reasons.add(course.courseId() + " already has lecture " + lectures.getFirst().crn() + " placed.");
					}
				}
				else if (!selection.isPlaced(section.parentCrn())) {
					reasons.add("Place its lecture " + section.parentCrn() + " first.");
				}
				else {
					List<SectionOption> same = selection.linkedByLectureAndComponent
						.get(section.parentCrn() + "|" + section.component());
					if (same != null) {
						reasons.add("Lecture " + section.parentCrn() + " already has " + section.component() + " "
								+ same.getFirst().crn() + " placed.");
					}
				}
				for (SectionOption other : selection.placed) {
					if (overlaps(section, other)) {
						reasons.add("Meets at the same time as " + label(other) + ".");
					}
				}
				if (overlapsBlocked(section, constraints)) {
					reasons.add("Overlaps one of your blocked times.");
				}
				int credits = selection.credits + section.credits();
				if (credits > constraints.maxCredits()) {
					reasons.add("Would bring you to " + credits + " credits, above your maximum of "
							+ constraints.maxCredits() + ".");
				}
				boolean fits = reasons.isEmpty();
				candidates.add(new SectionFit(section.crn(), section.courseId(), fits,
						fits && !section.isPrimary(), reasons));
			}
		}
		return candidates;
	}

	private static SortedMap<String, Integer> breakdown(ScheduleProblem problem,
			SortedMap<String, CourseOption> coursesById, Selection selection) {
		List<CourseOption> optional = coursesById.values().stream().filter(c -> !c.required()).toList();
		ScheduleScorer scorer = new ScheduleScorer(problem.soft(), optional.size());
		List<Meeting> meetings = selection.placed.stream().flatMap(s -> s.meetings().stream()).toList();
		int early = meetings.stream().mapToInt(scorer::earlyMinutes).sum();
		int late = meetings.stream().mapToInt(scorer::lateMinutes).sum();
		int mask = ScheduleScorer.dayMask(meetings.stream().flatMap(m -> m.days().stream()).toList());
		int skipped = (int) optional.stream()
			.filter(c -> !selection.lecturesByCourse.containsKey(c.courseId()))
			.count();
		return scorer.breakdown(early, late, mask, ScheduleScorer.gapMinutes(meetings), skipped,
				ScheduleScorer.breakShortfallMinutes(meetings, problem.soft().minBreakMinutes()));
	}

	/** Component types linked to a lecture in the catalog, e.g. [LAB, REC]. */
	private static SortedSet<String> linkedComponents(CourseOption course, String lectureCrn) {
		SortedSet<String> components = new TreeSet<>();
		for (SectionOption section : course.sections()) {
			if (lectureCrn.equals(section.parentCrn())) {
				components.add(section.component());
			}
		}
		return components;
	}

	private static boolean overlaps(SectionOption a, SectionOption b) {
		for (Meeting x : a.meetings()) {
			for (Meeting y : b.meetings()) {
				if (x.overlaps(y)) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean overlapsBlocked(SectionOption section, Constraints constraints) {
		for (Meeting meeting : section.meetings()) {
			for (Meeting blocked : constraints.blockedTimes()) {
				if (meeting.overlaps(blocked)) {
					return true;
				}
			}
		}
		return false;
	}

	private static String label(SectionOption section) {
		return section.crn() + " (" + section.courseId() + ")";
	}

	private static List<String> crns(List<SectionOption> sections) {
		return sections.stream().map(SectionOption::crn).sorted().toList();
	}

	private static SelectionIssue issue(SelectionIssue.Type type, List<String> crns, String message) {
		return new SelectionIssue(type, crns, message);
	}

	/** Lookups over the placed sections, all in sorted maps so iteration order is fixed. */
	private static final class Selection {

		final List<SectionOption> placed;
		final SortedMap<String, List<SectionOption>> lecturesByCourse = new TreeMap<>();
		/** Key: lecture CRN + "|" + component. */
		final SortedMap<String, List<SectionOption>> linkedByLectureAndComponent = new TreeMap<>();
		final SortedSet<String> placedCrns = new TreeSet<>();
		final int credits;

		Selection(List<SectionOption> placed) {
			this.placed = placed;
			int total = 0;
			for (SectionOption section : placed) {
				placedCrns.add(section.crn());
				total += section.credits();
				Map<String, List<SectionOption>> target = section.isPrimary() ? lecturesByCourse
						: linkedByLectureAndComponent;
				String key = section.isPrimary() ? section.courseId() : section.parentCrn() + "|" + section.component();
				target.computeIfAbsent(key, k -> new ArrayList<>()).add(section);
			}
			this.credits = total;
		}

		boolean isPlaced(String crn) {
			return placedCrns.contains(crn);
		}
	}

}
