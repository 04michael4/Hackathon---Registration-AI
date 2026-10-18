package io.github.michael04.registration.course;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import io.github.michael04.registration.common.json.JsonConfiguration;

/** Endpoints 3 and 4 over the demo catalogs. */
@WebMvcTest(CourseController.class)
@Import({CourseService.class, DummyCourseCatalog.class, DummySectionCatalog.class, JsonConfiguration.class})
class CourseControllerTest {

	@Autowired MockMvc mvc;

	private ResultActions get200(String url) throws Exception {
		return mvc.perform(get(url)).andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
	}

	@Test
	void searchByCodeIgnoresSpacingCaseAndLeadingZeros() throws Exception {
		for (String q : new String[] {"CSE 17", "cse017", "CSE-017"}) {
			mvc.perform(get("/api/courses").param("term", "2027-SPRING").param("q", q)).andExpect(status().isOk())
				.andExpect(jsonPath("$.items[*].courseId", contains("CSE-017")))
				.andExpect(jsonPath("$.items[0].subject").value("CSE"))
				.andExpect(jsonPath("$.items[0].number").value("017"))
				.andExpect(jsonPath("$.items[0].title").value("Programming and Data Structures"))
				.andExpect(jsonPath("$.items[0].credits").value(3))
				.andExpect(jsonPath("$.items[0].sectionCount").value(3));
		}
	}

	@Test
	void searchByTitleWordsAndSubject() throws Exception {
		get200("/api/courses?term=2027-SPRING&q=calculus").andExpect(jsonPath("$.items[*].courseId", contains("MATH-021", "MATH-022")));
		get200("/api/courses?term=2027-SPRING&subject=cse").andExpect(jsonPath("$.totalItems").value(3));
		mvc.perform(get("/api/courses").param("term", "2027-SPRING").param("q", "nothing matches")).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
			.andExpect(jsonPath("$.totalPages").value(0));
	}

	@Test
	void searchPagesThroughAllCourses() throws Exception {
		get200("/api/courses?term=2027-SPRING&size=3&page=2")
			.andExpect(jsonPath("$.items[*].courseId", contains("MATH-022", "PHY-011")))
			.andExpect(jsonPath("$.page").value(2))
			.andExpect(jsonPath("$.size").value(3))
			.andExpect(jsonPath("$.totalItems").value(8))
			.andExpect(jsonPath("$.totalPages").value(3));
	}

	@ParameterizedTest
	@ValueSource(strings = {"/api/courses", "/api/courses?term=", "/api/courses?term=2027-SPRING&size=101",
			"/api/courses?term=2027-SPRING&size=0", "/api/courses?term=2027-SPRING&page=-1",
			"/api/courses/CSE-017", "/api/courses/CSE-017?term=", "/api/courses/CSE-398-?term=2027-SPRING",
			"/api/courses/CSE-398-042-001?term=2027-SPRING", "/api/courses/cse-398-042?term=2027-SPRING"})
	void invalidRequestsReturn400(String url) throws Exception {
		mvc.perform(get(url)).andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@ParameterizedTest
	@ValueSource(strings = {"/api/courses?term=2030-FALL", "/api/courses/CSE-017?term=2030-FALL",
			"/api/courses/NOPE-999?term=2027-SPRING", "/api/courses/CSE-398-042?term=2027-SPRING"})
	void unknownTermOrCourseReturns404(String url) throws Exception {
		mvc.perform(get(url)).andExpect(status().isNotFound());
	}

	@Test
	void detailListsEverySectionWithItsLectureLink() throws Exception {
		get200("/api/courses/PHY-011?term=2027-SPRING")
			.andExpect(jsonPath("$.courseId").value("PHY-011"))
			.andExpect(jsonPath("$.credits").value(4))
			.andExpect(jsonPath("$.sections.length()").value(8))
			.andExpect(jsonPath("$.sections[0].crn").value("48001"))
			.andExpect(jsonPath("$.sections[0].componentType").value("LECTURE"))
			.andExpect(jsonPath("$.sections[0].lectureLink").doesNotExist())
			.andExpect(jsonPath("$.sections[1].crn").value("48002"))
			.andExpect(jsonPath("$.sections[2].crn").value("48011"))
			.andExpect(jsonPath("$.sections[2].componentType").value("LAB"))
			.andExpect(jsonPath("$.sections[2].lectureLink").value("48001"))
			.andExpect(jsonPath("$.sections[2].meetings[0].days", contains("TUESDAY")))
			.andExpect(jsonPath("$.sections[2].meetings[0].start").value("13:00"))
			.andExpect(jsonPath("$.sections[2].status").value("OPEN"));
	}

}
