package io.github.michael04.registration.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.michael04.registration.common.preferences.HardConstraints;
import io.github.michael04.registration.common.preferences.SchedulePreferences;
import io.github.michael04.registration.common.preferences.SoftPreferences;
import io.github.michael04.registration.common.preferences.TimeBlock;
import io.github.michael04.registration.common.preferences.Weighted;
import io.github.michael04.registration.course.CatalogSection;
import io.github.michael04.registration.course.CourseSummary;
import io.github.michael04.registration.schedule.integration.SchedulingEngine;

class EngineMapperTest {
	private final EngineMapper mapper = new EngineMapper();
	private final Map<String, CourseSummary> courses = Map.of("CSE-017", new CourseSummary("CSE-017", "Programming", 3));

	// lectureLink holds the CRN of the lecture a linked section belongs to (null for lectures).
	private List<CatalogSection> mixedStatusSections() {
		var lecture = section("1", "LECTURE", null, CatalogSection.Status.OPEN, List.of());
		var lab = section("2", "LAB", "1", CatalogSection.Status.OPEN, List.of());
		var closedLecture = section("3", "LECTURE", null, CatalogSection.Status.CLOSED, List.of());
		var closedLab = section("4", "LAB", "3", CatalogSection.Status.CLOSED, List.of());
		var closedRecitation = section("5", "RECITATION", "1", CatalogSection.Status.CLOSED, List.of());
		var unknown = section("6", "SEMINAR", null, CatalogSection.Status.UNKNOWN, List.of());
		return List.of(closedLab, unknown, lab, closedRecitation, closedLecture, lecture);
	}

	@Test
	void includingClosedKeepsEverySectionAndPreservesMetadataAndLectureLinks() {
		var input = mapper.prepare(request(true), Set.of("CSE-017"), courses, mixedStatusSections());
		var actual = input.problem().courses().getFirst().sections();
		assertThat(actual).extracting(SchedulingEngine.Section::crn).containsExactly("1", "2", "3", "4", "5", "6");
		assertThat(actual.get(0).componentType()).isEqualTo("LECTURE");
		assertThat(actual.get(0).lectureLink()).isNull();
		assertThat(actual.get(1).componentType()).isEqualTo("LAB");
		assertThat(actual.get(1).lectureLink()).isEqualTo("1");
		assertThat(actual.get(1).credits()).isEqualTo(0);
	}

	@Test
	void excludingClosedAlsoDropsLecturesThatLostAllSectionsOfALinkedComponent() {
		var input = mapper.prepare(request(false), Set.of("CSE-017"), courses, mixedStatusSections());
		// Lecture 1's only recitation (5) is closed, so lecture 1 can't be taken and its lab 2 goes too.
		// Lecture 3 and its lab 4 are closed. Section 6 has UNKNOWN status and stays.
		assertThat(input.problem().courses().getFirst().sections()).extracting(SchedulingEngine.Section::crn)
			.containsExactly("6");
	}

	@Test
	void excludingClosedKeepsALectureWhileOneOfItsLabsIsOpen() {
		var lecture = section("1", "LECTURE", null, CatalogSection.Status.OPEN, List.of());
		var closedLab = section("2", "LAB", "1", CatalogSection.Status.CLOSED, List.of());
		var openLab = section("3", "LAB", "1", CatalogSection.Status.OPEN, List.of());
		var input = mapper.prepare(request(false), Set.of("CSE-017"), courses, List.of(lecture, closedLab, openLab));
		assertThat(input.problem().courses().getFirst().sections()).extracting(SchedulingEngine.Section::crn)
			.containsExactly("1", "3");
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void blankOrSelfLectureLinksAreIntegrationFailures(boolean includeClosed) {
		var lecture = section("1", "LECTURE", null, CatalogSection.Status.OPEN, List.of());
		var blankLink = section("2", "LAB", " ", CatalogSection.Status.OPEN, List.of());
		var selfLink = section("3", "LAB", "3", CatalogSection.Status.OPEN, List.of());
		assertThatThrownBy(() -> mapper.prepare(request(includeClosed), Set.of("CSE-017"), courses, List.of(lecture, blankLink)))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> mapper.prepare(request(includeClosed), Set.of("CSE-017"), courses, List.of(lecture, selfLink)))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void labLinkedToAnotherLabIsAnIntegrationFailure() {
		var lecture = section("1", "LECTURE", null, CatalogSection.Status.OPEN, List.of());
		var lab = section("2", "LAB", "1", CatalogSection.Status.OPEN, List.of());
		var labOfLab = section("3", "LAB", "2", CatalogSection.Status.CLOSED, List.of());
		assertThatThrownBy(() -> mapper.prepare(request(false), Set.of("CSE-017"), courses, List.of(lecture, lab, labOfLab)))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void tbaAndOnlineSectionsHaveEmptyMeetingsWithoutInventedLinks() {
		var tba = section("1", "LECTURE", null, CatalogSection.Status.OPEN, null);
		var online = section("2", "LECTURE", null, CatalogSection.Status.OPEN, List.of());
		var input = mapper.prepare(request(false), Set.of("CSE-017"), courses, List.of(tba, online));
		assertThat(input.problem().courses().getFirst().sections()).allSatisfy(section -> {
			assertThat(section.meetings()).isEmpty();
			assertThat(section.lectureLink()).isNull();
		});
	}

	@Test
	void preservesTimesAndBlockedTimesAndNormalizesDayOrdering() {
		var meeting = new CatalogSection.Meeting(List.of(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
			LocalTime.of(9, 0), LocalTime.of(10, 50), "Building A");
		var soft = new SoftPreferences(null, null, null,
			new Weighted<>(List.of(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.FRIDAY), 5), null);
		var block = new TimeBlock(List.of(DayOfWeek.THURSDAY, DayOfWeek.TUESDAY), LocalTime.of(15, 0), LocalTime.of(18, 0));
		var request = new GenerateScheduleRequest("2027-SPRING",
			new SchedulePreferences(new HardConstraints(List.of("CSE-017"), 1, 21, List.of(block)), soft), 10, false);
		var input = mapper.prepare(request, Set.of("CSE-017"), courses,
			List.of(section("1", "LECTURE", null, CatalogSection.Status.OPEN, List.of(meeting))));
		assertThat(input.problem().courses().getFirst().sections().getFirst().meetings()).containsExactly(
			new SchedulingEngine.Meeting(List.of(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), LocalTime.of(9, 0), LocalTime.of(10, 50)));
		assertThat(input.problem().constraints().blockedTimes()).containsExactly(
			new SchedulingEngine.Meeting(List.of(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY), LocalTime.of(15, 0), LocalTime.of(18, 0)));
		assertThat(input.problem().soft().daysOff()).containsExactly(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
		assertThat(input.problem().maxResults()).isEqualTo(10);
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void excludesWrongTermAndUnselectedCoursesEvenWhenIncludingClosed(boolean includeClosed) {
		var otherTerm = new CatalogSection("2026-FALL", "CSE-017", "2", "010", 3, "LECTURE", null, CatalogSection.Status.OPEN, List.of());
		var otherCourse = new CatalogSection("2027-SPRING", "OTHER-001", "3", "010", 3, "LECTURE", null, CatalogSection.Status.OPEN, List.of());
		var expected = section("1", "LECTURE", null, CatalogSection.Status.OPEN, List.of());
		var input = mapper.prepare(request(includeClosed), Set.of("CSE-017"), courses, List.of(otherTerm, otherCourse, expected));
		assertThat(input.problem().courses().getFirst().sections()).extracting(SchedulingEngine.Section::crn).containsExactly("1");
	}

	@Test
	void retainsRequiredCoursesWithNoEligibleSectionsForEngineDiagnostics() {
		var closed = section("1", "LECTURE", null, CatalogSection.Status.CLOSED, List.of());
		var input = mapper.prepare(request(false), Set.of("CSE-017"), courses, List.of(closed));
		assertThat(input.problem().courses()).containsExactly(new SchedulingEngine.Course("CSE-017", true, List.of()));
	}

	@Test
	void preparationIsStableUnderCatalogOrderingAndDuplicateRows() {
		var rows = new ArrayList<>(List.of(section("2", "LAB", "1", CatalogSection.Status.OPEN, List.of()),
			section("1", "LECTURE", null, CatalogSection.Status.OPEN, List.of())));
		var expected = mapper.prepare(request(false), Set.of("CSE-017"), courses, rows).problem();
		Collections.reverse(rows);
		rows.add(rows.getFirst());
		assertThat(mapper.prepare(request(false), Set.of("CSE-017"), courses, rows).problem()).isEqualTo(expected);
	}

	@Test
	void conflictingSectionRecordsAreNotSilentlyChosen() {
		var first = section("1", "LECTURE", null, CatalogSection.Status.OPEN, List.of());
		var second = section("1", "LAB", "2", CatalogSection.Status.OPEN, List.of());
		assertThatThrownBy(() -> mapper.prepare(request(false), Set.of("CSE-017"), courses, List.of(first, second)))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void preparationCannotPassMoreThanEightOptionalCoursesEvenOutsideTheController() {
		Map<String, CourseSummary> oversized = new LinkedHashMap<>(courses);
		for (int i = 0; i < 9; i++) {
			String id = "OPT-" + i;
			oversized.put(id, new CourseSummary(id, "Optional", 3));
		}
		assertThatThrownBy(() -> mapper.prepare(request(false), Set.of("CSE-017"), oversized, List.of()))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void malformedCatalogMeetingIsNotInventedOrSilentlyDiscarded() {
		var section = section("1", "LECTURE", null, CatalogSection.Status.OPEN,
			List.of(new CatalogSection.Meeting(List.of(DayOfWeek.MONDAY), null, null, null)));
		assertThatThrownBy(() -> mapper.prepare(request(false), Set.of("CSE-017"), courses, List.of(section)))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void responseUsesExactEngineNumbersAndOrderingAndDoesNotFillBreakdown() {
		var input = mapper.prepare(request(false), Set.of("CSE-017"), courses, List.of(
			section("1", "LECTURE", null, CatalogSection.Status.OPEN, List.of()),
			section("2", "LAB", "1", CatalogSection.Status.OPEN, List.of())));
		Map<String, BigDecimal> breakdown = new LinkedHashMap<>();
		breakdown.put("minimizeGaps", new BigDecimal("1.234567890123456789"));
		breakdown.put("optionalCourseIds", new BigDecimal("0.00"));
		var score = new BigDecimal("98.765432109876543211");
		var result = new SchedulingEngine.Result(List.of(
			new SchedulingEngine.ScoredSchedule(List.of("2", "1"), 19, score, breakdown)), List.of(), false);
		var response = mapper.response("2027-SPRING", input, result).schedules().getFirst();
		assertThat(response.score()).isSameAs(score);
		assertThat(response.scoreBreakdown()).isSameAs(breakdown);
		assertThat(response.scoreBreakdown().keySet()).containsExactly("minimizeGaps", "optionalCourseIds");
		assertThat(response.sections()).extracting(GenerateScheduleResponse.SectionResponse::crn).containsExactly("2", "1");
		assertThat(response.totalCredits()).isEqualTo(19);
		assertThat(response.overload()).isTrue();
	}

	@Test
	void unknownReturnedSectionsAndMissingScoresAreIntegrationFailures() {
		var input = mapper.prepare(request(false), Set.of("CSE-017"), courses,
			List.of(section("1", "LECTURE", null, CatalogSection.Status.OPEN, List.of())));
		var unknown = new SchedulingEngine.Result(List.of(
			new SchedulingEngine.ScoredSchedule(List.of("missing"), 3, BigDecimal.TEN, Map.of())), List.of(), false);
		assertThatThrownBy(() -> mapper.response("2027-SPRING", input, unknown)).isInstanceOf(IllegalStateException.class);
		var missingBreakdown = new SchedulingEngine.Result(List.of(
			new SchedulingEngine.ScoredSchedule(List.of("1"), 3, BigDecimal.TEN, null)), List.of(), false);
		assertThatThrownBy(() -> mapper.response("2027-SPRING", input, missingBreakdown)).isInstanceOf(NullPointerException.class);
	}

	private GenerateScheduleRequest request(boolean includeClosed) {
		return new GenerateScheduleRequest("2027-SPRING",
			new SchedulePreferences(new HardConstraints(List.of("CSE-017"), 1, 21, List.of()), null), 5, includeClosed);
	}

	private CatalogSection section(String crn, String component, String link, CatalogSection.Status status,
			List<CatalogSection.Meeting> meetings) {
		return new CatalogSection("2027-SPRING", "CSE-017", crn, "010",
			component.equals("LAB") ? 0 : 3, component, link, status, meetings);
	}
}
