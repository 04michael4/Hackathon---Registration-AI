package io.github.michael04.registration.course;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.michael04.registration.common.json.JsonConfiguration;

/** Regressions for features on both sides of the pre-main merge. */
@WebMvcTest(CourseController.class)
@Import({CourseService.class, JsonConfiguration.class})
class CourseMergeControllerTest {
	@Autowired MockMvc mvc;
	@MockitoBean CourseCatalog courses;
	@MockitoBean SectionCatalog sections;
	@MockitoBean CourseMetadataCatalog metadata;

	@Test void termsKeepApiIdsAndActualCaptureDates() throws Exception {
		when(metadata.terms()).thenReturn(List.of(new CourseResponses.TermResponse("2027-SPRING", "Spring 2027", LocalDate.of(2026, 10, 1))));
		mvc.perform(get("/api/terms")).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].term").value("2027-SPRING"))
			.andExpect(jsonPath("$.items[0].catalogSnapshotDate").value("2026-10-01"));
	}

	@Test void detailKeepsDescriptionAndInstructorAlongsideComponentAndLectureLink() throws Exception {
		when(courses.termExists("2027-SPRING")).thenReturn(true);
		when(courses.findCourse("2027-SPRING", "CSE-007"))
			.thenReturn(Optional.of(new CourseSummary("CSE-007", "Programming", 4)));
		when(sections.sectionsFor("2027-SPRING", List.of("CSE-007"))).thenReturn(List.of(
			new CatalogSection("2027-SPRING", "CSE-007", "12275", "010", 4, "LECTURE", null, CatalogSection.Status.UNKNOWN, List.of()),
			new CatalogSection("2027-SPRING", "CSE-007", "13132", "060", 0, "LAB", "12275", CatalogSection.Status.UNKNOWN, List.of())));
		when(metadata.detail("2027-SPRING", "CSE-007"))
			.thenReturn(new CourseMetadataCatalog.Detail("Java and OOP", Map.of("12275", List.of("Chen, Brian"))));
		mvc.perform(get("/api/courses/CSE-007?term=2027-SPRING")).andExpect(status().isOk())
			.andExpect(jsonPath("$.description").value("Java and OOP"))
			.andExpect(jsonPath("$.sections[0].instructors[0]").value("Chen, Brian"))
			.andExpect(jsonPath("$.sections[1].componentType").value("LAB"))
			.andExpect(jsonPath("$.sections[1].lectureLink").value("12275"))
			.andExpect(jsonPath("$.sections[1].credits").value(0))
			.andExpect(jsonPath("$.sections[1].meetings").isEmpty());
	}

	@ParameterizedTest
	@ValueSource(strings = {"/api/courses", "/api/courses?term=", "/api/courses?term=2027-SPRING&page=-1",
		"/api/courses?term=2027-SPRING&size=0", "/api/courses?term=2027-SPRING&size=101",
		"/api/courses?term=2027-SPRING&page=1.5", "/api/courses?term=2027-SPRING&size=nope",
		"/api/courses?term=2027-SPRING&subject=CSE1", "/api/courses?term=2027-SPRING&subject=",
		"/api/courses/CSE-007", "/api/courses/not-a-course?term=2027-SPRING"})
	void invalidParametersDoNotReachAnyCatalog(String path) throws Exception {
		mvc.perform(get(path)).andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
			.andExpect(header().exists("X-Request-Id"));
		verifyNoInteractions(courses, sections, metadata);
	}

	@Test void oversizedSearchNeverReachesCatalog() throws Exception {
		mvc.perform(get("/api/courses").param("term", "2027-SPRING").param("q", "a".repeat(101)))
			.andExpect(status().isBadRequest());
		verifyNoInteractions(courses, sections, metadata);
	}
}
