package io.github.michael04.registration.course.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import io.github.michael04.registration.ai.CourseInterestSearch;
import io.github.michael04.registration.ai.ExtractPreferencesResponse.CourseSuggestion;
import io.github.michael04.registration.common.preferences.TimeBlock;
import io.github.michael04.registration.course.CatalogSection;
import io.github.michael04.registration.course.CatalogSection.Meeting;
import io.github.michael04.registration.course.CourseCatalog;
import io.github.michael04.registration.course.CourseSummary;
import io.github.michael04.registration.course.SectionCatalog;

/**
 * OPT-IN, READ-ONLY: runs the interest search and its time rules against the real {@code catalog_serving} views. Skipped unless
 * {@code RUN_SERVING_IT=true}. It reads only (no AI key is configured, and the session should be forced read-only in the URL):
 *
 * <pre>
 * RUN_SERVING_IT=true DB_URL='jdbc:postgresql://127.0.0.1:55437/registration_unified_spring2027?options=-c%20default_transaction_read_only%3Don' \
 *   DB_USERNAME=catalog_reader DB_PASSWORD=... ./mvnw test -Dtest=InterestSearchCatalogIntegrationTest
 * </pre>
 *
 * The expectations about particular courses (MATH-021, CSE-012, CSE-160) are facts of the Spring 2027 catalog as of 2026-10-18.
 */
@EnabledIfEnvironmentVariable(named = "RUN_SERVING_IT", matches = "true")
@SpringBootTest(properties = { "app.catalog.source=database", "app.catalog.database.url=${DB_URL}",
		"app.catalog.database.username=${DB_USERNAME}", "app.catalog.database.password=${DB_PASSWORD:}",
		"app.catalog.database.schema=catalog_serving", "app.ai.provider=none" })
class InterestSearchCatalogIntegrationTest {

	private static final String TERM = "2027-SPRING";

	private static final List<String> DATA_SCIENCE = List.of("data science", "data", "statistics", "analytics", "machine learning", "programming", "database");

	private static final List<DayOfWeek> WEEKDAYS = List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);

	@Autowired
	CourseInterestSearch search;

	@Autowired
	CourseCatalog courses;

	@Autowired
	SectionCatalog sections;

	private static TimeBlock block(String start, String end) {
		return new TimeBlock(WEEKDAYS, LocalTime.parse(start), LocalTime.parse(end));
	}

	private static List<String> ids(CourseInterestSearch.Found found) {
		return found.suggestions().stream().map(CourseSuggestion::courseId).toList();
	}

	private static void report(String title, CourseInterestSearch.Found found) {
		System.out.println("== " + title + " (left out by time: " + found.leftOutByTime() + ")");
		found.suggestions().forEach(s -> System.out.println("   " + s.courseId() + "  " + s.title() + "  <-  " + s.qualifying() + "  [" + s.reason() + "]"));
	}

	/** An independent, simpler check than the production code: every linked component type has some section that satisfies the rule. */
	private boolean oracleFits(String courseId, List<TimeBlock> blocked, LocalTime latestStart) {
		List<CatalogSection> all = sections.sectionsFor(TERM, List.of(courseId));
		for (CatalogSection lecture : all.stream().filter(s -> s.lectureLink() == null).toList()) {
			if (!sectionOk(lecture, blocked, latestStart)) {
				continue;
			}
			Map<String, List<CatalogSection>> linked = new TreeMap<>();
			all.stream().filter(s -> lecture.crn().equals(s.lectureLink())).forEach(s -> linked.computeIfAbsent(s.componentType(), k -> new ArrayList<>()).add(s));
			if (linked.values().stream().allMatch(options -> options.stream().anyMatch(o -> sectionOk(o, blocked, latestStart)))) {
				return true;
			}
		}
		return false;
	}

	private static boolean sectionOk(CatalogSection section, List<TimeBlock> blocked, LocalTime latestStart) {
		if (section.status() == CatalogSection.Status.CLOSED || section.meetings().isEmpty()) {
			return false;
		}
		for (Meeting m : section.meetings()) {
			if (latestStart != null && !m.start().isBefore(latestStart)) {
				return false;
			}
			for (TimeBlock b : blocked) {
				if (m.days().stream().anyMatch(b.days()::contains) && m.start().isBefore(b.end()) && b.start().isBefore(m.end())) {
					return false;
				}
			}
		}
		return true;
	}

	@Test
	void everySuggestionUnderEveryRuleIsARealCourseWhoseRealSectionsSatisfyTheRule() {
		Set<String> catalogIds = new java.util.HashSet<>(courses.listCourses(TERM).stream().map(CourseSummary::courseId).toList());
		record Rule(String name, List<TimeBlock> blocked, LocalTime latestStart) {
		}
		var rules = List.of(
				new Rule("no classes before 10 (overlap 00:00-10:00)", List.of(block("00:00", "10:00")), null),
				new Rule("classes end by 10 (overlap 10:00-23:59)", List.of(block("10:00", "23:59")), null),
				new Rule("classes start before 10 (latestStart 10:00)", List.of(), LocalTime.of(10, 0)),
				new Rule("no classes after 3 PM", List.of(block("15:00", "23:59")), null),
				new Rule("classes end before noon", List.of(block("12:00", "23:59")), null),
				new Rule("no Friday classes", List.of(new TimeBlock(List.of(DayOfWeek.FRIDAY), LocalTime.MIDNIGHT, LocalTime.of(23, 59))), null));
		for (Rule rule : rules) {
			var found = search.find(TERM, DATA_SCIENCE, Set.of(), rule.blocked(), rule.latestStart());
			report(rule.name(), found);
			assertThat(found.suggestions().size()).isLessThanOrEqualTo(10);
			for (var suggestion : found.suggestions()) {
				assertThat(catalogIds).as(rule.name() + " / invented course?").contains(suggestion.courseId());
				assertThat(oracleFits(suggestion.courseId(), rule.blocked(), rule.latestStart())).as(rule.name() + " / " + suggestion.courseId()).isTrue();
				assertThat(suggestion.qualifying()).isNotBlank();
			}
		}
	}

	@Test
	void theThreeDifferentRulesGiveThreeDifferentAnswersForMath021() {
		long before = search.find(TERM, List.of("calculus"), Set.of(), List.of(), LocalTime.of(10, 0)).suggestions().stream().filter(s -> s.courseId().equals("MATH-021")).count();
		long endBy10 = search.find(TERM, List.of("calculus"), Set.of(), List.of(block("10:00", "23:59")), null).suggestions().stream().filter(s -> s.courseId().equals("MATH-021")).count();
		long noneBefore10 = search.find(TERM, List.of("calculus"), Set.of(), List.of(block("00:00", "10:00")), null).suggestions().stream().filter(s -> s.courseId().equals("MATH-021")).count();
		System.out.println("== MATH-021: starts-before-10=" + before + " ends-by-10=" + endBy10 + " no-classes-before-10=" + noneBefore10);
		assertThat(before).as("lecture 09:20-10:35 starts before 10 and a Friday 07:55 recitation exists").isEqualTo(1);
		assertThat(endBy10).as("the lecture ends at 10:35").isZero();
		assertThat(noneBefore10).as("the lecture starts at 09:20").isZero();
		var fit = search.find(TERM, List.of("calculus"), Set.of(), List.of(), LocalTime.of(10, 0)).suggestions().stream().filter(s -> s.courseId().equals("MATH-021")).findFirst().orElseThrow();
		assertThat(fit.qualifying()).startsWith("Lecture").contains("Recitation");
	}

	@Test
	void cse012CannotEndBy10ButDoesMeetNoClassesBefore10() {
		var endBy10 = search.find(TERM, List.of("python", "programming"), Set.of(), List.of(block("10:00", "23:59")), null);
		var noMorning = search.find(TERM, List.of("python", "programming"), Set.of(), List.of(block("00:00", "10:00")), null);
		report("CSE-012 / end by 10", endBy10);
		report("CSE-012 / no classes before 10", noMorning);
		assertThat(ids(endBy10)).doesNotContain("CSE-012");
		assertThat(ids(noMorning)).contains("CSE-012");
		// both of its sections (Mon/Wed 13:35 and Tue/Thu 10:45) begin after 10:00; the first by CRN is named
		assertThat(noMorning.suggestions().stream().filter(s -> s.courseId().equals("CSE-012")).findFirst().orElseThrow().qualifying()).matches(".*(13:35|10:45).*");
	}

	@Test
	void dataScienceRanksTheWholePhraseTitleFirstFromRealData() {
		var found = search.find(TERM, DATA_SCIENCE, Set.of(), List.of(), null);
		report("Data Science, no time rule", found);
		assertThat(found.suggestions()).hasSize(10);
		assertThat(ids(found).get(0)).isEqualTo("CSE-160");
		assertThat(ids(found).indexOf("CSE-160")).isLessThan(ids(found).indexOf("CSE-017"));
	}
}
