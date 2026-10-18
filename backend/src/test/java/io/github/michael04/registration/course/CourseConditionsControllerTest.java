package io.github.michael04.registration.course;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import io.github.michael04.registration.common.json.JsonConfiguration;

/** Course detail carries the catalog's conditions, and an empty list when the catalog has none. */
@WebMvcTest(CourseController.class)
@Import({CourseService.class, DummyCourseCatalog.class, DummySectionCatalog.class, JsonConfiguration.class,
		CourseConditionsControllerTest.Conditions.class})
class CourseConditionsControllerTest {

	@TestConfiguration
	static class Conditions {

		@Bean
		ConditionCatalog conditionCatalog() {
			return (term, courseId) -> "CSE-017".equals(courseId) && "2027-SPRING".equals(term)
					? List.of(new CourseCondition("PREREQUISITE", "Prerequisite: CSE 004 or CSE 007", "BANNER", "UNVERIFIED"),
							new CourseCondition("CLASS_STANDING", "Not open to these class standings: first-year", "BANNER", "UNVERIFIED"))
					: List.of();
		}

	}

	@Autowired
	MockMvc mvc;

	@Test
	void detailListsTheConditionsInOrderWithTheirEvidence() throws Exception {
		mvc.perform(get("/api/courses/CSE-017").param("term", "2027-SPRING")).andExpect(status().isOk())
			.andExpect(jsonPath("$.conditions[*].code", contains("PREREQUISITE", "CLASS_STANDING")))
			.andExpect(jsonPath("$.conditions[0].message").value("Prerequisite: CSE 004 or CSE 007"))
			.andExpect(jsonPath("$.conditions[0].evidenceLevel").value("BANNER"))
			.andExpect(jsonPath("$.conditions[0].outcome").value("UNVERIFIED"));
	}

	@Test
	void aCourseWithoutConditionsHasAnEmptyList() throws Exception {
		mvc.perform(get("/api/courses/MATH-021").param("term", "2027-SPRING")).andExpect(status().isOk())
			.andExpect(jsonPath("$.conditions").isArray())
			.andExpect(jsonPath("$.conditions").isEmpty());
	}

}
