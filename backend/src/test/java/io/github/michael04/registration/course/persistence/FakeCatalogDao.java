package io.github.michael04.registration.course.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * An in-memory {@link CatalogDao} holding what the {@code catalog_api} views return for CSE 007 (Banner term
 * 202710), which records every call so tests can prove which queries ran.
 */
final class FakeCatalogDao implements CatalogDao {

	static final String BANNER = "202710";

	static final CourseRow CSE_007 = new CourseRow("CSE-007", "Introduction to Programming", 4);

	static final SectionRow LECTURE = new SectionRow("CSE-007", "12275", "010", 4, "LECTURE", null, "UNKNOWN");

	static final SectionRow LAB_060 = new SectionRow("CSE-007", "13132", "060", 0, "LAB", "12275", "UNKNOWN");

	static final SectionRow LAB_062 = new SectionRow("CSE-007", "12638", "062", 0, "LAB", "12275", "UNKNOWN");

	static final MeetingRow LECTURE_MON = new MeetingRow("12275", List.of(1), "09:20", "10:35", "TBD");

	static final MeetingRow LAB_060_WF = new MeetingRow("13132", List.of(3, 5), "09:20", "10:35",
			"Asa Packer Campus | 019 Packard Lab | Room 112");

	static final MeetingRow LAB_062_WF = new MeetingRow("12638", List.of(3, 5), "10:45", "12:00",
			"Asa Packer Campus | 019 Packard Lab | Room 11");

	List<CourseRow> courses = List.of(CSE_007);

	/** In the order the SQL returns them: by course, then CRN. */
	List<SectionRow> sections = List.of(LECTURE, LAB_062, LAB_060);

	List<MeetingRow> meetings = List.of(LECTURE_MON, LAB_062_WF, LAB_060_WF);

	RuntimeException failure;

	final List<String> calls = new ArrayList<>();

	@Override
	public boolean termHasCourses(String bannerTerm) {
		record("termHasCourses", bannerTerm);
		return BANNER.equals(bannerTerm) && !courses.isEmpty();
	}

	@Override
	public List<CourseRow> courses(String bannerTerm) {
		record("courses", bannerTerm);
		return BANNER.equals(bannerTerm) ? courses : List.of();
	}

	@Override
	public Optional<CourseRow> course(String bannerTerm, String courseId) {
		record("course", bannerTerm, courseId);
		return BANNER.equals(bannerTerm) ? courses.stream().filter(c -> c.courseId().equals(courseId)).findFirst() : Optional.empty();
	}

	@Override
	public List<SectionRow> sections(String bannerTerm, List<String> courseIds) {
		record("sections", bannerTerm, courseIds);
		return BANNER.equals(bannerTerm) ? sections.stream().filter(s -> courseIds.contains(s.courseId())).toList() : List.of();
	}

	@Override
	public List<MeetingRow> meetings(String bannerTerm, List<String> crns) {
		record("meetings", bannerTerm, crns);
		return BANNER.equals(bannerTerm) ? meetings.stream().filter(m -> crns.contains(m.crn())).toList() : List.of();
	}

	private void record(String method, Object... args) {
		calls.add(method + List.of(args));
		if (failure != null) {
			throw failure;
		}
	}

}
