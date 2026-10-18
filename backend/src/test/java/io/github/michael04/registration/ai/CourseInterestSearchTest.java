package io.github.michael04.registration.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import io.github.michael04.registration.ai.ExtractPreferencesResponse.CourseSuggestion;
import io.github.michael04.registration.common.preferences.TimeBlock;
import io.github.michael04.registration.course.CatalogSection;
import io.github.michael04.registration.course.CatalogSection.Meeting;
import io.github.michael04.registration.course.CatalogSection.Status;
import io.github.michael04.registration.course.CourseCatalog;
import io.github.michael04.registration.course.CourseMetadataCatalog;
import io.github.michael04.registration.course.CourseSummary;
import io.github.michael04.registration.course.SectionCatalog;

/**
 * Claude supplies keywords; only courses that really are in the catalog can come back, ranked by how well the title
 * matches, and (with hard time rules) only if a complete real section combination fits. The sections of CSE-012, CSE-017,
 * CSE-160, CSE-347 and CSE-211 below are copied from the Spring 2027 catalog.
 */
class CourseInterestSearchTest {

	private static final String TERM = "2027-SPRING";

	private static final List<DayOfWeek> WEEKDAYS = List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);

	private static final List<CourseSummary> CATALOG = new ArrayList<>(List.of(
			new CourseSummary("CSE-012", "Introduction to Programming with Python", 3),
			new CourseSummary("CSE-017", "Programming and Data Structures", 3),
			new CourseSummary("CSE-160", "Introduction to Data Science", 3),
			new CourseSummary("CSE-347", "Data Mining", 3),
			new CourseSummary("CSE-211", "Data Management for Big Data", 3),
			new CourseSummary("CSE-241", "Database Systems and Applications", 3),
			new CourseSummary("BUAN-348", "Predictive Analytics in Business", 3),
			new CourseSummary("STAT-243", "Statistical Methods", 3),
			new CourseSummary("MATH-021", "Calculus I", 4),
			new CourseSummary("ART-101", "Drawing", 3)));

	private static final List<CatalogSection> SECTIONS = new ArrayList<>(List.of(
			// real: both CSE-012 sections are in the afternoon or end after 10:45, so no morning rule can be met
			section("CSE-012", "10963", "010", "LECTURE", null, "MW", "13:35", "14:50"),
			section("CSE-012", "14435", "011", "LECTURE", null, "TR", "10:45", "12:00"),
			// real: CSE-017 lecture 09:20-10:10 with two required labs on Thursday
			section("CSE-017", "13507", "010", "LECTURE", null, "MW", "09:20", "10:10"),
			section("CSE-017", "14855", "060", "LAB", "13507", "R", "10:45", "12:00"),
			section("CSE-017", "14856", "061", "LAB", "13507", "R", "13:35", "14:50"),
			section("CSE-160", "11416", "010", "LECTURE", null, "MW", "10:45", "12:00"),
			section("CSE-347", "12044", "010", "LECTURE", null, "TR", "10:45", "12:00"),
			section("CSE-211", "14867", "010", "LECTURE", null, "TR", "13:35", "14:50"),
			// real: MATH-021 lecture 210 (09:20-10:35) with its linked recitations
			section("MATH-021", "11181", "210", "LECTURE", null, "MW", "09:20", "10:35"),
			section("MATH-021", "11182", "211", "RECITATION", "11181", "T", "15:00", "15:50"),
			section("MATH-021", "12763", "212", "RECITATION", "11181", "R", "12:10", "13:00"),
			section("MATH-021", "12765", "214", "RECITATION", "11181", "F", "07:55", "08:45"),
			// invented only to give the rules something to find: an early lecture, a Friday-only lecture
			section("STAT-243", "30001", "010", "LECTURE", null, "MWF", "08:00", "08:50"),
			section("CSE-241", "30002", "010", "LECTURE", null, "F", "11:00", "12:15"),
			section("BUAN-348", "30003", "010", "LECTURE", null, "TR", "14:00", "15:15")));

	private static CatalogSection section(String course, String crn, String code, String type, String link, String days, String start, String end) {
		return new CatalogSection(TERM, course, crn, code, 3, type, link, Status.UNKNOWN,
				List.of(new Meeting(days(days), LocalTime.parse(start), LocalTime.parse(end), null)));
	}

	private static List<DayOfWeek> days(String codes) {
		List<DayOfWeek> days = new ArrayList<>();
		for (char c : codes.toCharArray()) {
			days.add(switch (c) { case 'M' -> DayOfWeek.MONDAY; case 'T' -> DayOfWeek.TUESDAY; case 'W' -> DayOfWeek.WEDNESDAY; case 'R' -> DayOfWeek.THURSDAY; default -> DayOfWeek.FRIDAY; });
		}
		return days;
	}

	private static CourseCatalog catalog(List<CourseSummary> courses) {
		return new CourseCatalog() {
			public boolean termExists(String term) { return TERM.equals(term); }
			public Optional<CourseSummary> findCourse(String term, String id) { return courses.stream().filter(c -> c.courseId().equals(id)).findFirst(); }
			public List<CourseSummary> listCourses(String term) { return courses; }
		};
	}

	@SuppressWarnings("unchecked")
	private static CourseInterestSearch search(List<CourseSummary> courses, List<CatalogSection> sections, Map<String, String> descriptions, boolean metadataFails) {
		ObjectProvider<CourseMetadataCatalog> meta = mock(ObjectProvider.class);
		when(meta.getIfAvailable()).thenReturn(new CourseMetadataCatalog() {
			public List<io.github.michael04.registration.course.CourseResponses.TermResponse> terms() { return List.of(); }
			public Detail detail(String term, String id) {
				if (metadataFails) throw new IllegalStateException("database down");
				return new Detail(descriptions.get(id), Map.of());
			}
		});
		ObjectProvider<SectionCatalog> sectionProvider = mock(ObjectProvider.class);
		SectionCatalog catalogSections = (term, ids) -> sections.stream().filter(s -> ids.contains(s.courseId())).toList();
		when(sectionProvider.getIfAvailable()).thenReturn(catalogSections);
		return new CourseInterestSearch(catalog(courses), meta, sectionProvider);
	}

	private static CourseInterestSearch search() {
		return search(CATALOG, SECTIONS, Map.of(), false);
	}

	private static List<String> ids(CourseInterestSearch.Found found) {
		return found.suggestions().stream().map(CourseSuggestion::courseId).toList();
	}

	private static TimeBlock block(String days, String start, String end) {
		return new TimeBlock(days.equals("MTWRF") ? WEEKDAYS : days(days), LocalTime.parse(start), LocalTime.parse(end));
	}

	private static final List<String> DATA_TOPICS = List.of("data science", "data", "statistics", "analytics", "machine learning", "programming", "database");

	// ---- Priority 1: hard time rules decide what may be recommended ----

	@Test
	void testA_dataScienceClassesThatEndBefore10AreOnlyThoseWithARealFittingCombination() {
		var endBy10 = List.of(block("MTWRF", "10:00", "23:59"));

		var found = search().find(TERM, DATA_TOPICS, Set.of(), endBy10);

		assertThat(ids(found)).containsExactly("STAT-243");
		assertThat(found.suggestions().get(0).qualifying()).isEqualTo("Lecture 010 Mon/Wed/Fri 08:00–08:50");
		assertThat(ids(found)).as("CSE-012 has only a 13:35 and a 10:45 section, so it can never fit").doesNotContain("CSE-012");
		assertThat(found.leftOutByTime()).as("the related courses that were held back are counted, not hidden").isGreaterThanOrEqualTo(6);
	}

	@Test
	void everyNoClassesBeforeTenSuggestionReallyStartsAtOrAfterTen() {
		var found = search().find(TERM, DATA_TOPICS, Set.of(), List.of(block("MTWRF", "00:00", "10:00")));

		assertThat(ids(found)).contains("CSE-160", "CSE-347", "CSE-211", "CSE-012", "CSE-241", "BUAN-348").doesNotContain("CSE-017", "STAT-243");
		for (var suggestion : found.suggestions()) {
			assertThat(startsAtOrAfter(suggestion.courseId(), LocalTime.of(10, 0))).as(suggestion.courseId()).isTrue();
		}
	}

	@Test
	void testB_eachKindOfHardRuleKeepsOnlyCoursesWhoseCombinationSatisfiesIt() {
		record Rule(String name, List<TimeBlock> blocks, java.util.function.Predicate<List<CatalogSection>> ok) {
		}
		var rules = List.of(
				new Rule("no classes before 10", List.of(block("MTWRF", "00:00", "10:00")), combo -> all(combo, m -> !m.start().isBefore(LocalTime.of(10, 0)))),
				new Rule("no classes after 3 PM", List.of(block("MTWRF", "15:00", "23:59")), combo -> all(combo, m -> !m.end().isAfter(LocalTime.of(15, 0)))),
				new Rule("no Friday classes", List.of(block("F", "00:00", "23:59")), combo -> all(combo, m -> !m.days().contains(DayOfWeek.FRIDAY))),
				new Rule("classes end before noon", List.of(block("MTWRF", "12:00", "23:59")), combo -> all(combo, m -> !m.end().isAfter(LocalTime.of(12, 0)))),
				new Rule("only Monday/Wednesday", List.of(block("TRF", "00:00", "23:59")), combo -> all(combo, m -> m.days().stream().allMatch(d -> d == DayOfWeek.MONDAY || d == DayOfWeek.WEDNESDAY))));
		for (Rule rule : rules) {
			var found = search().find(TERM, DATA_TOPICS, Set.of(), rule.blocks());
			assertThat(found.suggestions()).as(rule.name()).isNotEmpty();
			for (var suggestion : found.suggestions()) {
				var fitting = CourseInterestSearch.firstFittingCombination(sectionsOf(suggestion.courseId()), rule.blocks()).orElseThrow();
				assertThat(rule.ok().test(fitting)).as(rule.name() + " / " + suggestion.courseId()).isTrue();
			}
			// and nothing that fits was wrongly dropped
			for (var course : CATALOG) {
				boolean fits = CourseInterestSearch.firstFittingCombination(sectionsOf(course.courseId()), rule.blocks()).isPresent();
				if (fits && DATA_TOPICS.stream().anyMatch(t -> CourseInterestSearch.titlePoints((course.courseId() + " " + course.title()).toLowerCase(), t) > 0)) {
					assertThat(ids(found)).as(rule.name()).contains(course.courseId());
				}
			}
		}
	}

	@Test
	void testC_aLectureThatFitsDoesNotQualifyWhenItsRequiredLabCannot() {
		// CSE-017's lecture (09:20-10:10) is fine with "nothing after 11:00", but both labs (10:45-12:00 and 13:35-14:50) run past it.
		var after11 = List.of(block("MTWRF", "11:00", "23:59"));
		assertThat(CourseInterestSearch.firstFittingCombination(sectionsOf("CSE-017"), after11)).isEmpty();
		assertThat(ids(search().find(TERM, List.of("programming"), Set.of(), after11))).doesNotContain("CSE-017");

		// With the rule relaxed to "nothing after 13:00" the first lab works, and the summary names lecture AND lab.
		var after13 = List.of(block("MTWRF", "13:00", "23:59"));
		var found = search().find(TERM, List.of("programming"), Set.of(), after13);
		var cse017 = found.suggestions().stream().filter(s -> s.courseId().equals("CSE-017")).findFirst().orElseThrow();
		assertThat(cse017.qualifying()).isEqualTo("Lecture 010 Mon/Wed 09:20–10:10 + Lab 060 Thu 10:45–12:00");
	}

	@Test
	void aLabThatOverlapsItsOwnLectureIsNotACompleteCombination() {
		var sections = List.of(
				section("CSE-017", "1", "010", "LECTURE", null, "MW", "10:00", "11:00"),
				section("CSE-017", "2", "060", "LAB", "1", "M", "10:30", "11:30"));
		assertThat(CourseInterestSearch.firstFittingCombination(sections, List.of())).isEmpty();
	}

	@Test
	void tbaAndClosedSectionsAreNeverVerifiedMatches() {
		var tba = new CatalogSection(TERM, "CSE-012", "9", "010", 3, "LECTURE", null, Status.UNKNOWN, List.of());
		var closed = new CatalogSection(TERM, "CSE-012", "8", "011", 3, "LECTURE", null, Status.CLOSED,
				List.of(new Meeting(days("MW"), LocalTime.of(11, 0), LocalTime.of(12, 0), null)));
		var rule = List.of(block("MTWRF", "00:00", "08:00"));

		var withRule = search(CATALOG, List.of(tba, closed), Map.of(), false).find(TERM, List.of("python"), Set.of(), rule);
		assertThat(withRule.suggestions()).isEmpty();
		assertThat(withRule.leftOutByTime()).isEqualTo(1);

		// With no time rule the course is still suggested, just without a claimed section.
		var noRule = search(CATALOG, List.of(tba, closed), Map.of(), false).find(TERM, List.of("python"), Set.of(), List.of());
		assertThat(ids(noRule)).containsExactly("CSE-012");
		assertThat(noRule.suggestions().get(0).qualifying()).isNull();
	}

	@Test
	void withoutSectionDataAHardRuleVerifiesNothing() {
		var found = search(CATALOG, List.of(), Map.of(), false).find(TERM, DATA_TOPICS, Set.of(), List.of(block("MTWRF", "00:00", "10:00")));
		assertThat(found.suggestions()).isEmpty();
	}

	// ---- the three different time requests, on real MATH-021 sections ----

	private static String math021(List<TimeBlock> blocked, LocalTime latestStart) {
		var found = search().find(TERM, List.of("calculus"), Set.of(), blocked, latestStart);
		return found.suggestions().stream().filter(x -> x.courseId().equals("MATH-021")).map(CourseSuggestion::qualifying).findFirst().orElse("EXCLUDED");
	}

	@Test
	void classesThatStartBefore10_acceptTheLectureThatEndsAfter10_withARecitationThatAlsoStartsEarly() {
		// Lecture 09:20 starts before 10 even though it ends at 10:35; the other recitations start at 12:10 and 15:00, so only Friday 07:55 qualifies.
		assertThat(math021(List.of(), LocalTime.of(10, 0))).isEqualTo("Lecture 210 Mon/Wed 09:20–10:35 + Recitation 214 Fri 07:55–08:45");
	}

	@Test
	void classesThatEndBy10_excludeTheSameCourse() {
		assertThat(math021(List.of(block("MTWRF", "10:00", "23:59")), null)).isEqualTo("EXCLUDED");
	}

	@Test
	void noClassesBefore10_excludeTheSameCourseToo_becauseTheLectureStartsAt0920() {
		assertThat(math021(List.of(block("MTWRF", "00:00", "10:00")), null)).isEqualTo("EXCLUDED");
	}

	@Test
	void theStartRuleStillRequiresTheLinkedSectionsToStartInTime() {
		// CSE-017: lecture 09:20 begins before 10, but both labs begin at 10:45 or later, so the course cannot be taken under this rule.
		var found = search().find(TERM, List.of("programming"), Set.of(), List.of(), LocalTime.of(10, 0));
		assertThat(ids(found)).doesNotContain("CSE-017", "CSE-012");
		assertThat(found.leftOutByTime()).isPositive();
	}

	@Test
	void startAndBlockedRulesCombine() {
		// start before 11 AND nothing after 14:00 (blocked 14:00+): CSE-160 (10:45) fits, CSE-211 (13:35-14:50) does not.
		var found = search().find(TERM, List.of("data"), Set.of(), List.of(block("MTWRF", "14:00", "23:59")), LocalTime.of(11, 0));
		assertThat(ids(found)).contains("CSE-160", "CSE-347").doesNotContain("CSE-211");
	}

	// ---- Priority 2: ranking ----

	@Test
	void testD_aWholePhraseTitleMatchOutranksLooseKeywordMatchesAndResultsAreCappedAtTen() {
		var many = new ArrayList<>(CATALOG);
		var sections = new ArrayList<>(SECTIONS);
		for (int i = 0; i < 8; i++) {
			many.add(new CourseSummary("DAT-" + (100 + i), "Applied Data Topics " + i, 3));
			sections.add(section("DAT-" + (100 + i), "4000" + i, "010", "LECTURE", null, "MW", "12:00", "13:00"));
		}
		var found = search(many, sections, Map.of(), false).find(TERM, DATA_TOPICS, Set.of(), List.of());

		assertThat(found.suggestions()).hasSize(10);
		assertThat(ids(found).get(0)).isEqualTo("CSE-160");
		assertThat(ids(found).indexOf("CSE-160")).isLessThan(ids(found).indexOf("CSE-017"));
		assertThat(found.suggestions().get(0).qualifying()).isEqualTo("Lecture 010 Mon/Wed 10:45–12:00");
		// every suggestion is a real catalog course
		assertThat(found.suggestions()).allSatisfy(s -> assertThat(many.stream().map(CourseSummary::courseId)).contains(s.courseId()));
	}

	@Test
	void theBestMatchWinsWhenSomeAreInfeasible_andOrderIsStable() {
		var noMornings = List.of(block("MTWRF", "00:00", "10:00"));
		var first = ids(search().find(TERM, DATA_TOPICS, Set.of(), noMornings));
		var second = ids(search().find(TERM, DATA_TOPICS, Set.of(), noMornings));

		assertThat(first).isEqualTo(second);
		assertThat(first.get(0)).as("CSE-017 (no qualifying section) can never sit above CSE-160").isEqualTo("CSE-160");
	}

	@Test
	void phraseAndWordScoring() {
		assertThat(CourseInterestSearch.titlePoints("cse-160 introduction to data science", "data science")).isEqualTo(10);
		assertThat(CourseInterestSearch.titlePoints("cse-017 programming and data structures", "data science")).isZero();
		assertThat(CourseInterestSearch.titlePoints("cse-017 programming and data structures", "data")).isEqualTo(3);
		assertThat(CourseInterestSearch.titlePoints("cse-999 science of big data", "data science")).as("all words but not the phrase").isEqualTo(3);
	}

	// ---- earlier behavior that must keep working ----

	@Test
	void aTopicNotInTheCatalogReturnsNothingInsteadOfAnInventedCourse() {
		var found = search().find(TERM, List.of("astrophysics", "marine biology"), Set.of(), List.of());
		assertThat(found.suggestions()).isEmpty();
		assertThat(found.leftOutByTime()).isZero();
	}

	@Test
	void descriptionsAreSearchedAndSaidSo() {
		var found = search(CATALOG, SECTIONS, Map.of("MATH-021", "Limits, derivatives and an introduction to data modeling."), false).find(TERM, List.of("modeling"), Set.of(), List.of());
		assertThat(ids(found)).containsExactly("MATH-021");
		assertThat(found.suggestions().get(0).reason()).contains("description").contains("modeling");
	}

	@Test
	void aFailingDescriptionSourceNeverBlocksTheTitleSearchAndChosenCoursesAreNotRepeated() {
		assertThat(ids(search(CATALOG, SECTIONS, Map.of(), true).find(TERM, List.of("statistics"), Set.of(), List.of()))).containsExactly("STAT-243");
		assertThat(ids(search().find(TERM, List.of("statistics", "business"), Set.of("STAT-243"), List.of()))).doesNotContain("STAT-243");
	}

	@Test
	void keywordsAreCleanedAndCapped() {
		var many = new ArrayList<String>();
		for (int i = 0; i < 30; i++) many.add("topic" + i);
		many.add(0, "  Data  ");
		many.add(1, "DATA");
		many.add(2, "x");
		var cleaned = CourseInterestSearch.clean(many);

		assertThat(cleaned).hasSize(CourseInterestSearch.MAX_TOPICS).startsWith("data", "topic0");
		assertThat(cleaned).doesNotContain("x");
		assertThat(CourseInterestSearch.clean(null)).isEmpty();
	}

	// ---- Priority 3: warnings ----

	private PreferenceAssembler assembler() {
		return new PreferenceAssembler(new CourseMentionResolver(catalog(CATALOG)), search());
	}

	private static RawPreferences raw(List<String> required, List<TimeBlock> blocks, List<String> topics, List<String> assumptions) {
		var raw = blocks.stream().map(b -> new RawPreferences.RawTimeBlock(b.days().stream().map(Enum::name).toList(), b.start().toString(), b.end().toString())).toList();
		return new RawPreferences(required, List.of(), null, null, null, raw, null, null, null, null, List.of(), null, null, assumptions, null, null, topics);
	}

	@Test
	void testE_anInterestRequestIsNotAWarningAboutMissingRequiredCourses() {
		var response = assembler().assemble(TERM, raw(List.of(), List.of(), DATA_TOPICS,
				List.of("Student described a topic interest rather than naming a specific course, so no required courses were listed")));

		assertThat(response.suggestions()).isNotEmpty();
		assertThat(response.warnings()).noneMatch(w -> w.toLowerCase().contains("no required course"));
	}

	@Test
	void testE_realProblemsAreStillReported() {
		// nothing said at all: the original warning stays
		assertThat(assembler().assemble(TERM, raw(List.of(), List.of(), List.of(), List.of())).warnings()).anyMatch(w -> w.startsWith("No required courses"));
		// an unmatched course name is still unresolved
		assertThat(assembler().assemble(TERM, raw(List.of("Underwater Basket Weaving"), List.of(), List.of(), List.of())).unresolved()).containsExactly("Underwater Basket Weaving");
		// an interest nobody teaches
		assertThat(assembler().assemble(TERM, raw(List.of(), List.of(), List.of("astrophysics"), List.of())).warnings()).anyMatch(w -> w.contains("astrophysics") && w.contains("matched"));
		// an interpretation the model made is passed on for the student to see
		assertThat(assembler().assemble(TERM, raw(List.of("CSE 160"), List.of(), List.of(), List.of("Read 'only classes before 10' as: end by 10:00 AM"))).warnings()).contains("Read 'only classes before 10' as: end by 10:00 AM");
	}

	@Test
	void whenTheTimeRulesExcludeEveryRelatedCourseTheStudentIsToldAndTheRuleIsNotRelaxed() {
		var response = assembler().assemble(TERM, raw(List.of(), List.of(block("MTWRF", "00:00", "08:00"), block("MTWRF", "08:50", "23:59")), List.of("calculus"), List.of()));

		assertThat(response.suggestions()).isEmpty();
		assertThat(response.preferences().hardConstraints().blockedTimes()).hasSize(2);
		assertThat(response.warnings()).anyMatch(w -> w.contains("No course fits both") && w.contains("not relaxed"));
	}

	@Test
	void theAssemblerCarriesTheStartRuleIntoTheHardConstraintsAndTheSearch() {
		var raw = new RawPreferences(List.of(), List.of(), null, null, null, List.of(), null, null, null, null, List.of(), null, null,
				List.of("Read 'start before 10' as: every class must begin before 10:00 AM"), null, null, List.of("calculus"), "10:00");

		var response = assembler().assemble(TERM, raw);

		assertThat(response.preferences().hardConstraints().latestStart()).isEqualTo(LocalTime.of(10, 0));
		assertThat(response.preferences().hardConstraints().blockedTimes()).isEmpty();
		assertThat(response.suggestions()).extracting(CourseSuggestion::courseId).containsExactly("MATH-021");
		assertThat(response.warnings()).contains("Read 'start before 10' as: every class must begin before 10:00 AM");
	}

	@Test
	void anUnreadableStartTimeIsReportedNotApplied() {
		var raw = new RawPreferences(List.of("MATH 021"), List.of(), null, null, null, List.of(), null, null, null, null, List.of(), null, null,
				List.of(), null, null, List.of(), "ten o'clock");

		var response = assembler().assemble(TERM, raw);

		assertThat(response.preferences().hardConstraints().latestStart()).isNull();
		assertThat(response.warnings()).anyMatch(w -> w.contains("latest class start"));
	}

	// ---- helpers ----

	private static List<CatalogSection> sectionsOf(String courseId) {
		return SECTIONS.stream().filter(s -> s.courseId().equals(courseId)).toList();
	}

	private static boolean all(List<CatalogSection> combo, java.util.function.Predicate<Meeting> test) {
		return combo.stream().flatMap(s -> s.meetings().stream()).allMatch(test);
	}

	private static boolean startsAtOrAfter(String courseId, LocalTime time) {
		var combo = CourseInterestSearch.firstFittingCombination(sectionsOf(courseId), List.of(block("MTWRF", "00:00", time.toString()))).orElseThrow();
		return all(combo, m -> !m.start().isBefore(time));
	}
}
