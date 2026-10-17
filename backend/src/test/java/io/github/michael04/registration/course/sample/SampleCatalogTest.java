package io.github.michael04.registration.course.sample;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.michael04.registration.course.CatalogSection;
import io.github.michael04.registration.course.CourseSummary;

class SampleCatalogTest {

	private final SampleCatalog catalog = new SampleCatalog();
	private final List<String> ids = catalog.listCourses(SampleCatalog.TERM).stream().map(CourseSummary::courseId).toList();
	private final List<CatalogSection> all = catalog.sectionsFor(SampleCatalog.TERM, ids);

	@Test
	void everyCourseHasALectureAndEveryLinkPointsAtALectureOfTheSameCourse() {
		assertThat(ids).hasSizeGreaterThanOrEqualTo(15);
		for (String id : ids) {
			assertThat(all).anyMatch(s -> s.courseId().equals(id) && s.lectureLink() == null);
		}
		for (CatalogSection linked : all.stream().filter(s -> s.lectureLink() != null).toList()) {
			assertThat(all).anyMatch(l -> l.crn().equals(linked.lectureLink()) && l.courseId().equals(linked.courseId())
					&& l.lectureLink() == null);
		}
		assertThat(all).extracting(CatalogSection::crn).doesNotHaveDuplicates();
	}

	@Test
	void metadataAndCrnLookupCoverEverySection() {
		assertThat(catalog.terms()).singleElement().satisfies(t -> assertThat(t.term()).isEqualTo(SampleCatalog.TERM));
		var detail = catalog.detail(SampleCatalog.TERM, "PHY-011");
		assertThat(detail.description()).isNotBlank();
		assertThat(detail.instructorsByCrn()).hasSize(8).allSatisfy((crn, names) -> assertThat(names).isNotEmpty());
		assertThat(catalog.sectionsByCrn(SampleCatalog.TERM, List.of("42001", "50011")))
			.extracting(CatalogSection::courseId).containsExactly("CSE-109", "PHY-011");
	}

	@Test
	void otherTermsAreEmpty() {
		assertThat(catalog.termExists("2030-FALL")).isFalse();
		assertThat(catalog.listCourses("2030-FALL")).isEmpty();
		assertThat(catalog.findCourse("2030-FALL", "CSE-017")).isEmpty();
	}

}
