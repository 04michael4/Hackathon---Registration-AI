package io.github.michael04.registration.schedule;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The actual app must start without pretending that the missing catalog and solver are implemented. */
@SpringBootTest
@AutoConfigureMockMvc
class ScheduleUnavailableTest {
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
