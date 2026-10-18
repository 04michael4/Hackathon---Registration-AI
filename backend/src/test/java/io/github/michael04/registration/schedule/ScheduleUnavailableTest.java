package io.github.michael04.registration.schedule;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import io.github.michael04.registration.course.CourseCatalog;
import io.github.michael04.registration.course.DummyCourseCatalog;

/**
 * The app must start and return a safe 503 when required scheduling integrations are unavailable.
 * {@code app.catalog.source=none} turns off the default demo catalogs; the test supplies only a course catalog
 * (the AI module needs one), so the section catalog is missing.
 */
@SpringBootTest(properties = "app.catalog.source=none")
@AutoConfigureMockMvc
class ScheduleUnavailableTest {
	@TestConfiguration
	static class CourseCatalogOnly {
		@Bean
		CourseCatalog courseCatalog() {
			return new DummyCourseCatalog();
		}
	}

	@Autowired MockMvc mvc;

	@Test
	void missingIntegrationReturnsSafe503RatherThanFakeSuccess() throws Exception {
		mvc.perform(post("/api/schedules/generate").contentType(MediaType.APPLICATION_JSON).content(ScheduleControllerTest.VALID))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.type").value("about:blank"))
			.andExpect(jsonPath("$.detail").value("Service is temporarily unavailable"))
			.andExpect(jsonPath("$.schedules").doesNotExist());
	}

	@Test
	void validationStillRunsWhenEngineAndCatalogAreMissing() throws Exception {
		mvc.perform(post("/api/schedules/generate").contentType(MediaType.APPLICATION_JSON)
			.content("{\"term\":\"2027-SPRING\",\"preferences\":{\"hardConstraints\":{\"requiredCourseIds\":[]}}}"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors").isArray());
	}
}
