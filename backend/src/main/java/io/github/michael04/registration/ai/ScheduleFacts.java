package io.github.michael04.registration.ai;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.github.michael04.registration.common.preferences.SchedulePreferences;
import io.github.michael04.registration.common.preferences.TimeBlock;
import io.github.michael04.registration.course.CatalogSection.Meeting;

/**
 * Facts about a schedule that Java computes, so the explanation never depends on the model doing time arithmetic:
 * the real breaks between classes, whether each hard rule and each wish is met, and by how much a wish is missed.
 * Everything here is derived from the catalog meetings of the engine-checked sections.
 */
final class ScheduleFacts {

	/** One placed section as the explanation sees it. */
	record SectionFact(String crn, String courseId, String title, int credits, List<Meeting> meetings) {
	}

	private record Slot(DayOfWeek day, String courseId, LocalTime start, LocalTime end) {
	}

	private ScheduleFacts() {
	}

	/** The computed block of the facts: classes per day, every break between consecutive classes, and rule/wish results. */
	static Map<String, Object> compute(List<SectionFact> sections, SchedulePreferences preferences) {
		List<Slot> slots = new ArrayList<>();
		for (SectionFact section : sections) {
			for (Meeting meeting : section.meetings()) {
				for (DayOfWeek day : meeting.days()) {
					slots.add(new Slot(day, section.courseId(), meeting.start(), meeting.end()));
				}
			}
		}
		Map<DayOfWeek, List<Slot>> byDay = new TreeMap<>();
		slots.stream().sorted(Comparator.comparing(Slot::start)).forEach(s -> byDay.computeIfAbsent(s.day(), d -> new ArrayList<>()).add(s));

		List<Map<String, Object>> days = new ArrayList<>();
		List<Map<String, Object>> breaks = new ArrayList<>();
		Integer shortestBreak = null;
		for (var entry : byDay.entrySet()) {
			List<Slot> day = entry.getValue();
			days.add(Map.of("day", entry.getKey().name(), "firstClassStarts", day.get(0).start().toString(),
					"lastClassEnds", day.stream().map(Slot::end).max(Comparator.naturalOrder()).orElseThrow().toString()));
			for (int i = 1; i < day.size(); i++) {
				int minutes = Math.max(0, (int) Duration.between(day.get(i - 1).end(), day.get(i).start()).toMinutes());
				breaks.add(Map.of("day", entry.getKey().name(), "after", day.get(i - 1).courseId(), "before", day.get(i).courseId(),
						"minutes", minutes));
				shortestBreak = shortestBreak == null ? minutes : Math.min(shortestBreak, minutes);
			}
		}
		LocalTime earliest = slots.stream().map(Slot::start).min(Comparator.naturalOrder()).orElse(null);
		LocalTime latest = slots.stream().map(Slot::end).max(Comparator.naturalOrder()).orElse(null);

		Map<String, Object> computed = new LinkedHashMap<>();
		computed.put("daysWithClasses", days);
		computed.put("earliestClassStart", earliest == null ? null : earliest.toString());
		computed.put("latestClassEnd", latest == null ? null : latest.toString());
		computed.put("breaksBetweenClasses", breaks);
		computed.put("shortestBreakMinutes", shortestBreak);
		computed.put("hardConstraintResults", hardResults(slots, preferences));
		computed.put("preferenceResults", preferenceResults(sections, preferences, earliest, latest, byDay.keySet().stream().toList(), breaks));
		return computed;
	}

	private static List<Map<String, Object>> hardResults(List<Slot> slots, SchedulePreferences preferences) {
		List<Map<String, Object>> results = new ArrayList<>();
		for (TimeBlock block : preferences.hardConstraints().blockedTimes()) {
			boolean clear = slots.stream().noneMatch(s -> block.days().contains(s.day())
					&& s.start().isBefore(block.end()) && block.start().isBefore(s.end()));
			results.add(Map.of("rule", "No classes " + block.days() + " " + block.start() + "-" + block.end(), "satisfied", clear));
		}
		LocalTime latestStart = preferences.hardConstraints().latestStart();
		if (latestStart != null) {
			boolean ok = slots.stream().allMatch(s -> s.start().isBefore(latestStart));
			results.add(Map.of("rule", "Every class starts before " + latestStart + " (it may end later)", "satisfied", ok));
		}
		return results;
	}

	private static List<Map<String, Object>> preferenceResults(List<SectionFact> sections, SchedulePreferences preferences,
			LocalTime earliest, LocalTime latest, List<DayOfWeek> classDays, List<Map<String, Object>> breaks) {
		List<Map<String, Object>> results = new ArrayList<>();
		var soft = preferences.softPreferences();
		if (soft == null) {
			return results;
		}
		if (soft.earliestStart() != null && earliest != null) {
			boolean met = !earliest.isBefore(soft.earliestStart().value());
			results.add(result("earliestStart", "Classes start no earlier than " + soft.earliestStart().value(), met,
					"earliest class starts at " + earliest, soft.earliestStart().weight()));
		}
		if (soft.latestEnd() != null && latest != null) {
			boolean met = !latest.isAfter(soft.latestEnd().value());
			results.add(result("latestEnd", "Classes end by " + soft.latestEnd().value(), met, "latest class ends at " + latest,
					soft.latestEnd().weight()));
		}
		if (soft.daysOff() != null) {
			List<DayOfWeek> worked = soft.daysOff().value().stream().filter(classDays::contains).toList();
			results.add(result("daysOff", "No classes on " + soft.daysOff().value(), worked.isEmpty(),
					worked.isEmpty() ? "no classes on those days" : "classes remain on " + worked, soft.daysOff().weight()));
		}
		if (soft.minBreakMinutes() != null) {
			int wanted = soft.minBreakMinutes().value();
			List<Map<String, Object>> short_ = breaks.stream().filter(b -> (int) b.get("minutes") < wanted).toList();
			results.add(result("minBreakMinutes", "At least " + wanted + " minutes between classes on the same day", short_.isEmpty(),
					short_.isEmpty() ? "every break is " + wanted + " minutes or longer"
							: short_.size() + " break(s) are shorter: " + short_, soft.minBreakMinutes().weight()));
		}
		if (soft.optionalCourseIds() != null) {
			List<String> placed = sections.stream().map(SectionFact::courseId).distinct().toList();
			List<String> missing = soft.optionalCourseIds().value().stream().filter(id -> !placed.contains(id)).toList();
			results.add(result("optionalCourseIds", "Include optional courses " + soft.optionalCourseIds().value(), missing.isEmpty(),
					missing.isEmpty() ? "all included" : "not included: " + missing, soft.optionalCourseIds().weight()));
		}
		return results;
	}

	private static Map<String, Object> result(String preference, String wanted, boolean satisfied, String detail, int weight) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("preference", preference);
		map.put("wanted", wanted);
		map.put("satisfied", satisfied);
		map.put("detail", detail);
		map.put("importanceOutOf5", weight);
		return map;
	}

}
