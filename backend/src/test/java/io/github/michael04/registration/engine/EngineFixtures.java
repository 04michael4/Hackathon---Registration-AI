package io.github.michael04.registration.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Builders for engine test data, plus an independent checker and brute-force solver. */
final class EngineFixtures {

	private EngineFixtures() {
	}

	/** {@code days} uses registrar letters: M T W R F S U. */
	static Meeting meeting(String days, String start, String end) {
		Set<DayOfWeek> parsed = EnumSet.noneOf(DayOfWeek.class);
		for (char c : days.toCharArray()) {
			parsed.add(switch (c) {
				case 'M' -> DayOfWeek.MONDAY;
				case 'T' -> DayOfWeek.TUESDAY;
				case 'W' -> DayOfWeek.WEDNESDAY;
				case 'R' -> DayOfWeek.THURSDAY;
				case 'F' -> DayOfWeek.FRIDAY;
				case 'S' -> DayOfWeek.SATURDAY;
				case 'U' -> DayOfWeek.SUNDAY;
				default -> throw new IllegalArgumentException("unknown day letter " + c);
			});
		}
		return new Meeting(parsed, LocalTime.parse(start), LocalTime.parse(end));
	}

	static SectionOption lecture(String crn, String courseId, int credits, Meeting... meetings) {
		return new SectionOption(crn, courseId, "LEC", null, credits, List.of(meetings));
	}

	static SectionOption linked(String crn, String courseId, String component, String parentCrn, Meeting... meetings) {
		return new SectionOption(crn, courseId, component, parentCrn, 0, List.of(meetings));
	}

	static CourseOption required(String courseId, SectionOption... sections) {
		return new CourseOption(courseId, true, List.of(sections));
	}

	static CourseOption optional(String courseId, SectionOption... sections) {
		return new CourseOption(courseId, false, List.of(sections));
	}

	static List<String> crns(ScoredSchedule schedule) {
		return schedule.sections().stream().map(SectionOption::crn).toList();
	}

	/**
	 * Checks every hard rule on every returned schedule, independently of the solver's code:
	 * no overlaps, no blocked times, credit range, each required course exactly once, lectures
	 * with exactly one linked section per component, consistent scores, best first.
	 */
	static void assertValid(ScheduleProblem problem, ScheduleResult result) {
		assertTrue(result.schedules().size() <= problem.maxResults(), "too many schedules");
		Map<String, SectionOption> problemSections = new TreeMap<>();
		problem.courses().forEach(c -> c.sections().forEach(s -> problemSections.put(s.crn(), s)));

		int previousScore = Integer.MAX_VALUE;
		for (ScoredSchedule schedule : result.schedules()) {
			String label = "schedule " + crns(schedule);
			assertFalse(schedule.sections().isEmpty(), label + " is empty");
			Set<String> seen = new HashSet<>();
			for (SectionOption section : schedule.sections()) {
				assertEquals(problemSections.get(section.crn()), section, label + ": unknown section " + section.crn());
				assertTrue(seen.add(section.crn()), label + ": duplicate crn " + section.crn());
			}

			List<Meeting> meetings = schedule.sections().stream().flatMap(s -> s.meetings().stream()).toList();
			for (int i = 0; i < meetings.size(); i++) {
				for (int j = i + 1; j < meetings.size(); j++) {
					assertFalse(meetings.get(i).overlaps(meetings.get(j)), label + ": time conflict");
				}
				for (Meeting blocked : problem.constraints().blockedTimes()) {
					assertFalse(meetings.get(i).overlaps(blocked), label + ": overlaps a blocked time");
				}
			}

			int credits = schedule.sections().stream().mapToInt(SectionOption::credits).sum();
			assertEquals(credits, schedule.totalCredits(), label + ": totalCredits");
			assertTrue(credits >= problem.constraints().minCredits() && credits <= problem.constraints().maxCredits(),
					label + ": credits " + credits + " out of range");

			for (CourseOption course : problem.courses()) {
				List<SectionOption> chosen = schedule.sections().stream()
					.filter(s -> s.courseId().equals(course.courseId()))
					.toList();
				List<SectionOption> lectures = chosen.stream().filter(SectionOption::isPrimary).toList();
				assertTrue(lectures.size() <= 1, label + ": two lectures of " + course.courseId());
				if (course.required()) {
					assertEquals(1, lectures.size(), label + ": required " + course.courseId() + " missing");
				}
				if (lectures.isEmpty()) {
					assertTrue(chosen.isEmpty(), label + ": lab of " + course.courseId() + " without its lecture");
					continue;
				}
				SectionOption lecture = lectures.getFirst();
				Set<String> components = course.sections().stream()
					.filter(s -> lecture.crn().equals(s.parentCrn()))
					.map(SectionOption::component)
					.collect(Collectors.toCollection(TreeSet::new));
				for (String component : components) {
					long count = chosen.stream()
						.filter(s -> lecture.crn().equals(s.parentCrn()) && s.component().equals(component))
						.count();
					assertEquals(1, count, label + ": " + course.courseId() + " needs exactly one " + component);
				}
				for (SectionOption section : chosen) {
					assertTrue(section.isPrimary() || lecture.crn().equals(section.parentCrn()),
							label + ": " + section.crn() + " is linked to a lecture that wasn't chosen");
				}
			}

			int penalty = schedule.breakdown().values().stream().mapToInt(Integer::intValue).sum();
			assertEquals(penalty, schedule.penalty(), label + ": penalty");
			assertEquals(100 - penalty, schedule.score(), label + ": score");
			assertTrue(schedule.score() >= 0 && schedule.score() <= 100, label + ": score out of 0-100");
			schedule.breakdown().values().forEach(p -> assertTrue(p >= 0 && p <= 20, label + ": points out of 0-20"));
			assertTrue(schedule.score() <= previousScore, label + ": not ordered best first");
			previousScore = schedule.score();
		}
	}

	/** The solver's ranking key, recomputed from a returned schedule. */
	static long rankKey(ScheduleProblem problem, ScoredSchedule schedule) {
		return rankKey(problem, schedule.sections());
	}

	/**
	 * Enumerates every combination of bundles (and skips, for optional courses), keeps the ones
	 * that satisfy all hard rules, and returns their sorted ranking keys. Exponential: small inputs only.
	 */
	static List<Long> bruteForceKeys(ScheduleProblem problem) {
		List<List<Bundle>> choices = new ArrayList<>();
		for (CourseOption course : problem.courses()) {
			List<Bundle> options = new ArrayList<>(Bundle.expand(course));
			if (!course.required()) {
				options.add(null);
			}
			choices.add(options);
		}
		List<Long> keys = new ArrayList<>();
		enumerate(problem, choices, 0, new ArrayList<>(), keys);
		keys.sort(null);
		return keys;
	}

	private static void enumerate(ScheduleProblem problem, List<List<Bundle>> choices, int i, List<Bundle> picked,
			List<Long> keys) {
		if (i == choices.size()) {
			List<SectionOption> sections = picked.stream().flatMap(b -> b.sections().stream()).toList();
			if (!sections.isEmpty() && isValid(problem, sections)) {
				keys.add(rankKey(problem, sections));
			}
			return;
		}
		for (Bundle bundle : choices.get(i)) {
			if (bundle != null) {
				picked.add(bundle);
			}
			enumerate(problem, choices, i + 1, picked, keys);
			if (bundle != null) {
				picked.removeLast();
			}
		}
	}

	private static boolean isValid(ScheduleProblem problem, List<SectionOption> sections) {
		List<Meeting> meetings = sections.stream().flatMap(s -> s.meetings().stream()).toList();
		for (int a = 0; a < meetings.size(); a++) {
			for (int b = a + 1; b < meetings.size(); b++) {
				if (meetings.get(a).overlaps(meetings.get(b))) {
					return false;
				}
			}
			for (Meeting blocked : problem.constraints().blockedTimes()) {
				if (meetings.get(a).overlaps(blocked)) {
					return false;
				}
			}
		}
		int credits = sections.stream().mapToInt(SectionOption::credits).sum();
		return credits >= problem.constraints().minCredits() && credits <= problem.constraints().maxCredits();
	}

	private static long rankKey(ScheduleProblem problem, List<SectionOption> sections) {
		int optionalCount = (int) problem.courses().stream().filter(c -> !c.required()).count();
		ScheduleScorer scorer = new ScheduleScorer(problem.soft(), optionalCount);
		List<Meeting> meetings = sections.stream().flatMap(s -> s.meetings().stream()).toList();
		int early = meetings.stream().mapToInt(scorer::earlyMinutes).sum();
		int late = meetings.stream().mapToInt(scorer::lateMinutes).sum();
		int mask = ScheduleScorer.dayMask(meetings.stream().flatMap(m -> m.days().stream()).toList());
		Set<String> chosenCourses = sections.stream().map(SectionOption::courseId).collect(Collectors.toSet());
		int skipped = (int) problem.courses().stream()
			.filter(c -> !c.required() && !chosenCourses.contains(c.courseId()))
			.count();
		return scorer.rankKey(early, late, mask, ScheduleScorer.gapMinutes(meetings), skipped);
	}

}
