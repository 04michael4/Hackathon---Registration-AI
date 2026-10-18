package io.github.michael04.registration.ai;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import io.github.michael04.registration.ai.ExtractPreferencesResponse.CourseSuggestion;
import io.github.michael04.registration.common.preferences.TimeBlock;
import io.github.michael04.registration.course.CatalogSection;
import io.github.michael04.registration.course.CatalogSection.Meeting;
import io.github.michael04.registration.course.CourseCatalog;
import io.github.michael04.registration.course.CourseMetadataCatalog;
import io.github.michael04.registration.course.CourseSummary;
import io.github.michael04.registration.course.SectionCatalog;

/**
 * Finds real catalog courses for the interest keywords Claude extracted ("data science" becomes data, statistics,
 * analytics, ...). Claude only supplies words; this class reads the connected catalog for the term, so a course that is
 * not in the catalog can never be suggested, and the stated reason lists only the words that actually matched.
 *
 * <p><b>Ranking.</b> A multi-word topic that appears as a phrase in the course title ("data science" in "Introduction to
 * Data Science") scores far above a title that merely contains some of the words, which scores above a description-only
 * match. Ties are broken by course ID, so the order is stable.
 *
 * <p><b>Hard time rules.</b> When the student's hard constraints block times, a course is only suggested if at least
 * one complete combination of real sections (a lecture plus one of each linked lab or recitation) has meetings, is not
 * closed, and overlaps no blocked time. A TBA or meetingless section never counts as a verified fit. Courses with no such
 * combination are left out of the suggestions (they remain in the full course list), and the number left out is reported.
 */
@Component
public class CourseInterestSearch {

	static final int MAX_TOPICS = 12;

	static final int MAX_SUGGESTIONS = 10;

	private static final int PHRASE_POINTS = 10;

	private static final int TITLE_POINTS = 3;

	private static final int DESCRIPTION_POINTS = 1;

	private static final int MAX_COMBINATIONS = 500;

	private final CourseCatalog catalog;

	private final ObjectProvider<CourseMetadataCatalog> metadata;

	private final ObjectProvider<SectionCatalog> sections;

	public CourseInterestSearch(CourseCatalog catalog, ObjectProvider<CourseMetadataCatalog> metadata,
			ObjectProvider<SectionCatalog> sections) {
		this.catalog = catalog;
		this.metadata = metadata;
		this.sections = sections;
	}

	/** The suggestions, and how many related courses were left out because no section combination fits the time rules. */
	public record Found(List<CourseSuggestion> suggestions, int leftOutByTime) {
	}

	/** As below, with no latest-start rule. */
	public Found find(String term, List<String> rawTopics, Set<String> exclude, List<TimeBlock> blocked) {
		return find(term, rawTopics, exclude, blocked, null);
	}

	/**
	 * Courses of {@code term} that match at least one topic, best first, never including {@code exclude}. The hard time rules
	 * are the blocked times (no overlap) and, when not null, {@code latestStart} (every class must begin before it).
	 */
	public Found find(String term, List<String> rawTopics, Set<String> exclude, List<TimeBlock> blocked, LocalTime latestStart) {
		List<String> topics = clean(rawTopics);
		if (topics.isEmpty()) {
			return new Found(List.of(), 0);
		}
		CourseMetadataCatalog descriptions = metadata == null ? null : metadata.getIfAvailable();
		record Hit(CourseSummary course, int score, List<String> inTitle, List<String> inDescription) {
		}
		List<Hit> hits = new ArrayList<>();
		for (CourseSummary course : catalog.listCourses(term)) {
			if (exclude.contains(course.courseId())) {
				continue;
			}
			String head = (course.courseId() + " " + course.title()).toLowerCase(Locale.ROOT);
			int score = 0;
			List<String> inTitle = new ArrayList<>();
			for (String topic : topics) {
				int points = titlePoints(head, topic);
				if (points > 0) {
					score += points;
					inTitle.add(topic);
				}
			}
			List<String> inDescription = List.of();
			if (descriptions != null) {
				String text = description(descriptions, term, course.courseId());
				if (text != null) {
					inDescription = topics.stream().filter(t -> !inTitle.contains(t) && matches(text, t)).toList();
					score += inDescription.size() * DESCRIPTION_POINTS;
				}
			}
			if (score > 0) {
				hits.add(new Hit(course, score, inTitle, inDescription));
			}
		}
		hits.sort(Comparator.comparingInt(Hit::score).reversed().thenComparing(h -> h.course().courseId()));

		Map<String, List<CatalogSection>> byCourse = hits.isEmpty() ? Map.of() : sectionsByCourse(term, hits.stream().map(h -> h.course().courseId()).toList());
		List<CourseSuggestion> found = new ArrayList<>();
		int leftOut = 0;
		for (Hit hit : hits) {
			Optional<List<CatalogSection>> fit = firstFittingCombination(byCourse.getOrDefault(hit.course().courseId(), List.of()), blocked, latestStart);
			if ((!blocked.isEmpty() || latestStart != null) && fit.isEmpty()) {
				leftOut++;
				continue;
			}
			if (found.size() < MAX_SUGGESTIONS) {
				found.add(new CourseSuggestion(hit.course().courseId(), hit.course().title(), hit.course().credits(),
						reason(hit.inTitle(), hit.inDescription()), fit.map(CourseInterestSearch::describe).orElse(null)));
			}
		}
		return new Found(List.copyOf(found), leftOut);
	}

	private Map<String, List<CatalogSection>> sectionsByCourse(String term, List<String> courseIds) {
		SectionCatalog catalogSections = sections == null ? null : sections.getIfAvailable();
		Map<String, List<CatalogSection>> byCourse = new TreeMap<>();
		if (catalogSections == null) {
			return byCourse;
		}
		try {
			for (CatalogSection section : catalogSections.sectionsFor(term, courseIds)) {
				byCourse.computeIfAbsent(section.courseId(), id -> new ArrayList<>()).add(section);
			}
		}
		catch (RuntimeException ex) {
			return new TreeMap<>(); // nothing can be verified, so nothing is claimed
		}
		return byCourse;
	}

	/**
	 * The first complete combination (by lecture CRN, then linked CRNs) that can really be taken under the time rules: every
	 * section has meetings and is not closed, none overlaps a blocked time, and the sections do not overlap each other.
	 */
	static Optional<List<CatalogSection>> firstFittingCombination(List<CatalogSection> all, List<TimeBlock> blocked) {
		return firstFittingCombination(all, blocked, null);
	}

	static Optional<List<CatalogSection>> firstFittingCombination(List<CatalogSection> all, List<TimeBlock> blocked, LocalTime latestStart) {
		List<CatalogSection> primaries = all.stream().filter(s -> s.lectureLink() == null)
			.sorted(Comparator.comparing(CatalogSection::crn)).toList();
		int tried = 0;
		for (CatalogSection primary : primaries) {
			Map<String, List<CatalogSection>> linked = new TreeMap<>();
			all.stream().filter(s -> primary.crn().equals(s.lectureLink()))
				.sorted(Comparator.comparing(CatalogSection::crn))
				.forEach(s -> linked.computeIfAbsent(s.componentType(), k -> new ArrayList<>()).add(s));
			List<List<CatalogSection>> combos = new ArrayList<>();
			combos.add(List.of(primary));
			for (List<CatalogSection> options : linked.values()) {
				List<List<CatalogSection>> next = new ArrayList<>();
				for (List<CatalogSection> base : combos) {
					for (CatalogSection option : options) {
						List<CatalogSection> extended = new ArrayList<>(base);
						extended.add(option);
						next.add(extended);
					}
				}
				combos = next;
			}
			for (List<CatalogSection> combo : combos) {
				if (++tried > MAX_COMBINATIONS) {
					return Optional.empty();
				}
				if (fits(combo, blocked, latestStart)) {
					return Optional.of(combo);
				}
			}
		}
		return Optional.empty();
	}

	private static boolean fits(List<CatalogSection> combo, List<TimeBlock> blocked, LocalTime latestStart) {
		List<Meeting> placed = new ArrayList<>();
		for (CatalogSection section : combo) {
			if (section.status() == CatalogSection.Status.CLOSED || section.meetings().isEmpty()) {
				return false;
			}
			for (Meeting meeting : section.meetings()) {
				if (latestStart != null && !meeting.start().isBefore(latestStart)) {
					return false; // begins at or after the latest allowed start; ending later is fine
				}
				for (TimeBlock block : blocked) {
					if (overlaps(meeting.days(), meeting.start(), meeting.end(), block.days(), block.start(), block.end())) {
						return false;
					}
				}
				for (Meeting other : placed) {
					if (overlaps(meeting.days(), meeting.start(), meeting.end(), other.days(), other.start(), other.end())) {
						return false;
					}
				}
			}
			placed.addAll(section.meetings());
		}
		return true;
	}

	private static boolean overlaps(List<DayOfWeek> daysA, LocalTime startA, LocalTime endA, List<DayOfWeek> daysB,
			LocalTime startB, LocalTime endB) {
		return daysA.stream().anyMatch(daysB::contains) && startA.isBefore(endB) && startB.isBefore(endA);
	}

	/** "Lecture 010 Mon/Wed 09:20–10:35 + Recitation 211 Tue 15:00–15:50", from the real section data only. */
	static String describe(List<CatalogSection> combo) {
		List<String> parts = new ArrayList<>();
		for (CatalogSection section : combo) {
			String type = section.componentType().charAt(0) + section.componentType().substring(1).toLowerCase(Locale.ROOT);
			List<String> times = new ArrayList<>();
			for (Meeting meeting : section.meetings()) {
				String days = String.join("/", meeting.days().stream().map(d -> d.name().charAt(0) + d.name().substring(1, 3).toLowerCase(Locale.ROOT)).toList());
				times.add(days + " " + meeting.start() + "–" + meeting.end());
			}
			parts.add(type + " " + section.sectionCode() + " " + String.join(", ", times));
		}
		return String.join(" + ", parts);
	}

	private static String reason(List<String> inTitle, List<String> inDescription) {
		List<String> parts = new ArrayList<>();
		if (!inTitle.isEmpty()) {
			parts.add("its title relates to " + String.join(", ", inTitle));
		}
		if (!inDescription.isEmpty()) {
			parts.add("its catalog description mentions " + String.join(", ", inDescription));
		}
		return "Matches your interest: " + String.join("; ", parts);
	}

	/** Lower-cased, trimmed, de-duplicated keywords of sensible length; at most {@value #MAX_TOPICS}. */
	static List<String> clean(List<String> raw) {
		Set<String> topics = new LinkedHashSet<>();
		if (raw != null) {
			for (String topic : raw) {
				String t = topic == null ? "" : topic.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
				if (t.length() >= 2 && t.length() <= 40) {
					topics.add(t);
				}
				if (topics.size() == MAX_TOPICS) {
					break;
				}
			}
		}
		return List.copyOf(topics);
	}

	/** Phrase in the title: {@value #PHRASE_POINTS}; all the words somewhere in the title: {@value #TITLE_POINTS}; otherwise 0. */
	static int titlePoints(String head, String topic) {
		if (topic.contains(" ") && head.contains(stemmed(topic))) {
			return PHRASE_POINTS;
		}
		return matches(head, topic) ? TITLE_POINTS : 0;
	}

	/** The topic with its last word reduced to its stem, so "data science" also finds "Data Sciences". */
	private static String stemmed(String topic) {
		int space = topic.lastIndexOf(' ');
		return topic.substring(0, space + 1) + stem(topic.substring(space + 1));
	}

	private static String stem(String word) {
		return word.length() >= 7 ? word.substring(0, word.length() - 2) : word;
	}

	/**
	 * Every word of the topic must occur in the text. Long words match by stem (the word without its last two letters), so
	 * "statistics" finds "Statistical Methods" and "analytics" finds "Business Analytics" and "Data Analytic".
	 */
	static boolean matches(String text, String topic) {
		for (String word : topic.split(" ")) {
			if (!text.contains(stem(word))) {
				return false;
			}
		}
		return true;
	}

	private static String description(CourseMetadataCatalog descriptions, String term, String courseId) {
		try {
			var detail = descriptions.detail(term, courseId);
			return detail == null || detail.description() == null ? null : detail.description().toLowerCase(Locale.ROOT);
		}
		catch (RuntimeException ex) {
			return null;
		}
	}

}
