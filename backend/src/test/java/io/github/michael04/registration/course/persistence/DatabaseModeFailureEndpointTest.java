package io.github.michael04.registration.course.persistence;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The whole app in database mode with a database that is not there (nothing listens on port 1). The app must start,
 * and every request that needs the catalog must fail as a safe 503 that reveals no connection detail. Requests that
 * never need the database must keep working.
 */
@SpringBootTest(properties = { "app.catalog.source=database", "app.ai.provider=dummy",
		"app.catalog.database.url=jdbc:postgresql://127.0.0.1:1/catalog-down-test",
		"app.catalog.database.username=reader-test-user", "app.catalog.database.password=http-test-secret-pw",
		"app.catalog.database.connection-timeout=300ms" })
@AutoConfigureMockMvc
class DatabaseModeFailureEndpointTest {

	private static final String SCHEDULE = """
			{"term":"%s","preferences":{"hardConstraints":{"requiredCourseIds":["CSE-007"],"minCredits":4,"maxCredits":4}}}
			""";

	@Autowired
	MockMvc mvc;

	private ResultActions generate(String term) throws Exception {
		return mvc.perform(post("/api/schedules/generate").contentType(MediaType.APPLICATION_JSON).content(SCHEDULE.formatted(term)));
	}

	private static ResultActions safe503(ResultActions result) throws Exception {
		return result.andExpect(status().isServiceUnavailable())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.detail").value("Service is temporarily unavailable"))
			.andExpect(jsonPath("$.requestId").isString())
			.andExpect(header().exists("X-Request-Id"))
			.andExpect(content().string(not(containsString("http-test-secret-pw"))))
			.andExpect(content().string(not(containsString("127.0.0.1"))))
			.andExpect(content().string(not(containsString("jdbc"))))
			.andExpect(content().string(not(containsString("reader-test-user"))))
			.andExpect(jsonPath("$.schedules").doesNotExist());
	}

	@Test
	void theAppStartsAndTheScheduleEndpointAnswersWithASafe503WhenTheDatabaseIsDown() throws Exception {
		safe503(generate("2027-SPRING"));
	}

	@Test
	void theAiEndpointAnswersWithASafe503WhenItsCatalogCheckCannotReachTheDatabase() throws Exception {
		safe503(mvc.perform(post("/api/preferences/extract").contentType(MediaType.APPLICATION_JSON)
			.content("{\"term\":\"2027-SPRING\",\"text\":\"I need CSE 7\"}")));
	}

	@Test
	void anUnknownTermIsStill422BecauseItNeverNeedsTheDatabase() throws Exception {
		generate("2027-FALL").andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.detail").value("Unknown term"));
	}

	@Test
	void theBannerCodeIsNotAcceptedAsATerm() throws Exception {
		generate("202710").andExpect(status().isUnprocessableContent());
	}

	@Test
	void requestValidationStillRunsBeforeAnyDatabaseAccess() throws Exception {
		mvc.perform(post("/api/schedules/generate").contentType(MediaType.APPLICATION_JSON)
			.content("{\"term\":\"2027-SPRING\",\"preferences\":{\"hardConstraints\":{\"requiredCourseIds\":[]}}}"))
			.andExpect(status().isBadRequest());
	}

}
