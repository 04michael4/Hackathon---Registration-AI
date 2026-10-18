package io.github.michael04.registration.course;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class DummySectionCatalogTest {

	private final DummyCourseCatalog courses = new DummyCourseCatalog();
	private final DummySectionCatalog sections = new DummySectionCatalog();
	private final List<String> courseIds = courses.listCourses(DummyCourseCatalog.TERM).stream()
		.map(CourseSummary::courseId).toList();
	private final List<CatalogSection> all = sections.sectionsFor(DummyCourseCatalog.TERM, courseIds);

	@Test
	void everyDummyCourseHasAtLeastOneLecture() {
		Set<String> withLecture = all.stream().filter(s -> s.lectureLink() == null)
			.map(CatalogSection::courseId).collect(Collectors.toSet());
		assertThat(withLecture).containsExactlyInAnyOrderElementsOf(courseIds);
	}

	@Test
	void everyLinkedSectionPointsAtALectureOfTheSameCourse() {
		for (CatalogSection linked : all.stream().filter(s -> s.lectureLink() != null).toList()) {
			assertThat(all).anySatisfy(lecture -> {
				assertThat(lecture.crn()).isEqualTo(linked.lectureLink());
				assertThat(lecture.courseId()).isEqualTo(linked.courseId());
				assertThat(lecture.lectureLink()).isNull();
			});
		}
	}

	@Test
	void crnsAreUniqueAndCreditsMatchTheCourse() {
		assertThat(all).extracting(CatalogSection::crn).doesNotHaveDuplicates();
		for (CatalogSection lecture : all.stream().filter(s -> s.lectureLink() == null).toList()) {
			assertThat(lecture.credits()).isEqualTo(courses.findCourse(DummyCourseCatalog.TERM, lecture.courseId())
				.orElseThrow().credits());
		}
	}

	@Test
	void onlyTheRequestedTermAndCoursesAreReturned() {
		assertThat(sections.sectionsFor("2030-FALL", courseIds)).isEmpty();
		assertThat(sections.sectionsFor(DummyCourseCatalog.TERM, List.of("CSE-017")))
			.extracting(CatalogSection::crn).containsExactly("41001", "41002", "41003");
	}

}
