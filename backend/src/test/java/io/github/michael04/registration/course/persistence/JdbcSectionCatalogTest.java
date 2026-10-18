package io.github.michael04.registration.course.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.michael04.registration.course.CatalogSection;
import io.github.michael04.registration.course.CatalogSection.Meeting;
import io.github.michael04.registration.course.CatalogSection.Status;

class JdbcSectionCatalogTest {

	private static final String TERM = "2027-SPRING";

	private final FakeCatalogDao dao = new FakeCatalogDao();

	private final JdbcSectionCatalog catalog = new JdbcSectionCatalog(dao, TermMapping.of(Map.of(TERM, "202710")));

	private CatalogSection section(String crn) {
		return catalog.sectionsFor(TERM, List.of("CSE-007")).stream().filter(s -> s.crn().equals(crn)).findFirst().orElseThrow();
	}

	@Test
	void cse007HasExactlyTheThreeExpectedSectionsInCrnOrder() {
		List<CatalogSection> sections = catalog.sectionsFor(TERM, List.of("CSE-007"));

		assertThat(sections).extracting(CatalogSection::crn).containsExactly("12275", "12638", "13132");
	}

	@Test
	void theLectureIsALectureWithFourCreditsAndNoLectureLink() {
		CatalogSection lecture = section("12275");

		assertThat(lecture.componentType()).isEqualTo("LECTURE");
		assertThat(lecture.credits()).isEqualTo(4);
		assertThat(lecture.lectureLink()).isNull();
		assertThat(lecture.sectionCode()).isEqualTo("010");
		assertThat(lecture.courseId()).isEqualTo("CSE-007");
	}

	@Test
	void bothLabsAreLabsWithZeroCreditsLinkedToLecture12275() {
		for (String crn : List.of("13132", "12638")) {
			CatalogSection lab = section(crn);

			assertThat(lab.componentType()).as(crn).isEqualTo("LAB");
			assertThat(lab.credits()).as(crn).isZero();
			assertThat(lab.lectureLink()).as(crn).isEqualTo("12275");
		}
		assertThat(section("13132").sectionCode()).isEqualTo("060");
		assertThat(section("12638").sectionCode()).isEqualTo("062");
	}

	@Test
	void everySectionCarriesTheApiTermNotTheBannerCode() {
		assertThat(catalog.sectionsFor(TERM, List.of("CSE-007"))).extracting(CatalogSection::term).containsOnly("2027-SPRING");
	}

	@Test
	void statusIsUnknownNeverOpenOrClosed() {
		assertThat(catalog.sectionsFor(TERM, List.of("CSE-007"))).extracting(CatalogSection::status).containsOnly(Status.UNKNOWN);
	}

	@Test
	void anUnrecognizedStatusIsUnknownNotAGuess() {
		dao.sections = List.of(new CatalogDao.SectionRow("CSE-007", "12275", "010", 4, "LECTURE", null, "WAITLIST"));

		assertThat(catalog.sectionsFor(TERM, List.of("CSE-007"))).extracting(CatalogSection::status).containsExactly(Status.UNKNOWN);
	}

	@Test
	void meetingsAreMappedExactly() {
		assertThat(section("12275").meetings()).containsExactly(
				new Meeting(List.of(DayOfWeek.MONDAY), LocalTime.of(9, 20), LocalTime.of(10, 35), "TBD"));
		assertThat(section("13132").meetings()).containsExactly(new Meeting(List.of(DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
				LocalTime.of(9, 20), LocalTime.of(10, 35), "Asa Packer Campus | 019 Packard Lab | Room 112"));
		assertThat(section("12638").meetings()).containsExactly(new Meeting(List.of(DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
				LocalTime.of(10, 45), LocalTime.of(12, 0), "Asa Packer Campus | 019 Packard Lab | Room 11"));
	}

	@Test
	void theFiveMeetingDaysAreAllPresent() {
		long days = catalog.sectionsFor(TERM, List.of("CSE-007")).stream().flatMap(s -> s.meetings().stream())
			.mapToLong(m -> m.days().size()).sum();

		assertThat(days).isEqualTo(5);
	}

	@Test
	void aSectionWithoutMeetingRowsHasAnEmptyMeetingListNotANullMeeting() {
		dao.meetings = List.of(FakeCatalogDao.LECTURE_MON);

		assertThat(section("13132").meetings()).isEmpty();
	}

	@Test
	void daysAreSortedAndDeduplicatedByTheMapping() {
		dao.meetings = List.of(new CatalogDao.MeetingRow("12275", List.of(5, 1, 5), "09:00", "10:00", null));

		assertThat(section("12275").meetings().get(0).days()).containsExactly(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
		assertThat(section("12275").meetings().get(0).location()).isNull();
	}

	@Test
	void aSectionWithSeveralTimeGroupsKeepsEachAsItsOwnMeeting() {
		dao.meetings = List.of(new CatalogDao.MeetingRow("12275", List.of(1), "09:20", "10:35", "A"),
				new CatalogDao.MeetingRow("12275", List.of(3), "13:00", "14:00", "B"));

		assertThat(section("12275").meetings()).hasSize(2);
	}

	@Test
	void unknownCoursesGiveNoSectionsAndTheDatabaseIsNotAskedForMeetings() {
		assertThat(catalog.sectionsFor(TERM, List.of("CSE-017"))).isEmpty();

		assertThat(dao.calls).containsExactly("sections[202710, [CSE-017]]");
	}

	@Test
	void unknownTermsGiveNoSectionsWithoutAnyQuery() {
		assertThat(catalog.sectionsFor("2027-FALL", List.of("CSE-007"))).isEmpty();
		assertThat(catalog.sectionsFor("202710", List.of("CSE-007"))).isEmpty();
		assertThat(catalog.sectionsFor(null, List.of("CSE-007"))).isEmpty();

		assertThat(dao.calls).isEmpty();
	}

	@Test
	void noCourseIdsMeansNoQuery() {
		assertThat(catalog.sectionsFor(TERM, List.of())).isEmpty();
		assertThat(catalog.sectionsFor(TERM, null)).isEmpty();
		assertThat(catalog.sectionsFor(TERM, java.util.Arrays.asList(null, " "))).isEmpty();

		assertThat(dao.calls).isEmpty();
	}

	@Test
	void courseIdsAreCleanedAndDeduplicatedBeforeTheQuery() {
		catalog.sectionsFor(TERM, List.of("CSE-007", " CSE-007 ", "CSE-001"));

		assertThat(dao.calls.get(0)).isEqualTo("sections[202710, [CSE-001, CSE-007]]");
	}

	/** Uncertified courses are absent from the view, so they can only ever come back as "no sections". */
	@Test
	void aCourseTheViewDoesNotCertifyNeverProducesSections() {
		dao.sections = List.of(); // what catalog_api returns for a course that fails any readiness rule

		assertThat(catalog.sectionsFor(TERM, List.of("CSE-007"))).isEmpty();
	}

	@Test
	void onlyRequestedCoursesAreReturned() {
		dao.sections = List.of(FakeCatalogDao.LECTURE, new CatalogDao.SectionRow("CSE-017", "99999", "010", 3, "LECTURE", null, "UNKNOWN"));

		assertThat(catalog.sectionsFor(TERM, List.of("CSE-007"))).extracting(CatalogSection::crn).containsExactly("12275");
	}

	// ---- data that can't be right is rejected, never repaired ----

	@Test
	void aLabWithoutALectureLinkIsRejectedNotTreatedAsStandalone() {
		dao.sections = List.of(FakeCatalogDao.LECTURE,
				new CatalogDao.SectionRow("CSE-007", "13132", "060", 0, "LAB", null, "UNKNOWN"));

		assertThatThrownBy(() -> catalog.sectionsFor(TERM, List.of("CSE-007"))).isInstanceOf(IllegalStateException.class)
			.hasMessage("Inconsistent catalog section data");
	}

	@Test
	void aLabLinkedToALectureThatIsNotReturnedIsRejected() {
		dao.sections = List.of(FakeCatalogDao.LAB_060);

		assertThatThrownBy(() -> catalog.sectionsFor(TERM, List.of("CSE-007"))).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void aLabLinkedToALectureOfAnotherCourseIsRejected() {
		dao.sections = List.of(FakeCatalogDao.LAB_060,
				new CatalogDao.SectionRow("CSE-017", "12275", "010", 3, "LECTURE", null, "UNKNOWN"));

		assertThatThrownBy(() -> catalog.sectionsFor(TERM, List.of("CSE-007", "CSE-017"))).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void aLabLinkedToAnotherLabIsRejected() {
		dao.sections = List.of(FakeCatalogDao.LECTURE, FakeCatalogDao.LAB_060,
				new CatalogDao.SectionRow("CSE-007", "12638", "062", 0, "LAB", "13132", "UNKNOWN"));

		assertThatThrownBy(() -> catalog.sectionsFor(TERM, List.of("CSE-007"))).isInstanceOf(IllegalStateException.class);
	}

	/**
	 * Review concern (Marpu): the engine adapter turns a blank or self-referencing lecture link into null, which would
	 * make a malformed lab look standalone. The catalog must never let such a value through.
	 */
	@Test
	void aLabWithABlankLectureLinkIsRejectedBeforeTheEngineCouldReadItAsStandalone() {
		for (String blank : List.of("", " ", "\t")) {
			dao.sections = List.of(FakeCatalogDao.LECTURE,
					new CatalogDao.SectionRow("CSE-007", "13132", "060", 0, "LAB", blank, "UNKNOWN"));

			assertThatThrownBy(() -> catalog.sectionsFor(TERM, List.of("CSE-007"))).as("'" + blank + "'")
				.isInstanceOf(IllegalStateException.class);
		}
	}

	@Test
	void aLabLinkedToItselfIsRejectedBeforeTheEngineCouldReadItAsStandalone() {
		dao.sections = List.of(FakeCatalogDao.LECTURE, new CatalogDao.SectionRow("CSE-007", "13132", "060", 0, "LAB", "13132", "UNKNOWN"));

		assertThatThrownBy(() -> catalog.sectionsFor(TERM, List.of("CSE-007"))).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void aLectureWithABlankLinkIsRejectedToo() {
		dao.sections = List.of(new CatalogDao.SectionRow("CSE-007", "12275", "010", 4, "LECTURE", "", "UNKNOWN"));

		assertThatThrownBy(() -> catalog.sectionsFor(TERM, List.of("CSE-007"))).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void aLectureWithABlankComponentTypeIsRejected() {
		dao.sections = List.of(new CatalogDao.SectionRow("CSE-007", "12275", "010", 4, " ", null, "UNKNOWN"));

		assertThatThrownBy(() -> catalog.sectionsFor(TERM, List.of("CSE-007"))).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void aLectureThatClaimsALinkIsRejected() {
		dao.sections = List.of(new CatalogDao.SectionRow("CSE-007", "12275", "010", 4, "LECTURE", "99999", "UNKNOWN"));

		assertThatThrownBy(() -> catalog.sectionsFor(TERM, List.of("CSE-007"))).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void duplicateCrnsAndNegativeCreditsAreRejected() {
		dao.sections = List.of(FakeCatalogDao.LECTURE, FakeCatalogDao.LECTURE);
		assertThatThrownBy(() -> catalog.sectionsFor(TERM, List.of("CSE-007"))).isInstanceOf(IllegalStateException.class);

		dao.sections = List.of(new CatalogDao.SectionRow("CSE-007", "12275", "010", -1, "LECTURE", null, "UNKNOWN"));
		assertThatThrownBy(() -> catalog.sectionsFor(TERM, List.of("CSE-007"))).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void invalidMeetingDataIsRejected() {
		for (CatalogDao.MeetingRow bad : List.of(
				new CatalogDao.MeetingRow("12275", List.of(), "09:20", "10:35", "x"),
				new CatalogDao.MeetingRow("12275", List.of(8), "09:20", "10:35", "x"),
				new CatalogDao.MeetingRow("12275", List.of(1), "9am", "10:35", "x"),
				new CatalogDao.MeetingRow("12275", List.of(1), "10:35", "09:20", "x"),
				new CatalogDao.MeetingRow("12275", List.of(1), "09:20", "09:20", "x"),
				new CatalogDao.MeetingRow("12275", List.of(1), null, "10:35", "x"))) {
			dao.meetings = List.of(bad);

			assertThatThrownBy(() -> catalog.sectionsFor(TERM, List.of("CSE-007"))).as(bad.toString())
				.isInstanceOf(IllegalStateException.class);
		}
	}

	@Test
	void aDatabaseFailureIsASafe503NotAnEmptyResult() {
		dao.failure = new CatalogUnavailableException();

		assertThatThrownBy(() -> catalog.sectionsFor(TERM, List.of("CSE-007"))).isInstanceOf(CatalogUnavailableException.class);
	}

}
