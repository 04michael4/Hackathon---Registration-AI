package io.github.michael04.registration.ai;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Full application (AI module + common error handling + CORS) with {@code AI_PROVIDER=dummy}.
 * No API key, no network, no database.
 */
@SpringBootTest(properties = { "app.ai.provider=dummy", "app.catalog.source=dummy",
		"app.cors.allowed-origins=http://localhost:3000" })
@AutoConfigureMockMvc
class PreferenceEndpointIntegrationTest {

	private static final String URL = "/api/preferences/extract";

	private static final String ALLOWED_ORIGIN = "http://localhost:3000";

	private static final String SENSITIVE = "sensitive-test-input";

	@Autowired
	private MockMvc mockMvc;

	@Test
	void dummyProviderReturnsContractJsonWithHourMinuteTimes() throws Exception {
		mockMvc.perform(extract("{\"term\":\"2027-SPRING\",\"text\":\"I need CSE 17 and Calc 1\"}"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.preferences.hardConstraints.requiredCourseIds[0]").value("CSE-017"))
			.andExpect(jsonPath("$.preferences.hardConstraints.requiredCourseIds[1]").value("MATH-021"))
			.andExpect(jsonPath("$.preferences.hardConstraints.blockedTimes[0].start").value("15:00"))
			.andExpect(jsonPath("$.preferences.hardConstraints.blockedTimes[0].end").value("18:00"))
			.andExpect(jsonPath("$.preferences.softPreferences.earliestStart.value").value("10:00"))
			.andExpect(content().string(not(containsString("15:00:00"))))
			.andExpect(jsonPath("$.warnings[?(@ == '" + DummyPreferenceExtractor.NOTICE + "')]").exists());
	}

	@Test
	void invalidRequestReturns400ProblemDetailsWithFieldErrors() throws Exception {
		mockMvc.perform(extract("{\"term\":\"2027-SPRING\",\"text\":\"\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.detail").value("Request validation failed"))
			.andExpect(jsonPath("$.errors[0].field").value("text"))
			.andExpect(jsonPath("$.instance").value(URL))
			.andExpect(header().exists("X-Request-Id"));
	}

	@Test
	void unknownTermReturns422WithoutEchoingTheTerm() throws Exception {
		// Real service and dummy catalog: the term doesn't exist, so the AI is never called.
		mockMvc.perform(extract("{\"term\":\"" + SENSITIVE + "\",\"text\":\"I need CSE 17\"}"))
			.andExpect(status().isUnprocessableContent())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.detail").value(UnknownTermException.PUBLIC_MESSAGE))
			.andExpect(jsonPath("$.requestId").isString())
			.andExpect(header().exists("X-Request-Id"))
			.andExpect(content().string(not(containsString(SENSITIVE))));
	}

	@Test
	void allowedOriginPreflightSucceeds() throws Exception {
		mockMvc.perform(options(URL).header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type"))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("POST")));
	}

	@Test
	void disallowedOriginPreflightIsRejectedWithProblemDetails() throws Exception {
		mockMvc.perform(options(URL).header(HttpHeaders.ORIGIN, "https://evil.example")
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
			.andExpect(status().isForbidden())
			.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.detail").value("Cross-origin request is not allowed"))
			.andExpect(header().exists("X-Request-Id"));
	}

	@Test
	void crossOriginPostFromAllowedOriginWorksAndExposesRequestId() throws Exception {
		mockMvc.perform(extract("{\"term\":\"2027-SPRING\",\"text\":\"I need CSE 17\"}").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsString("X-Request-Id")));
	}

	@Test
	void crossOriginErrorsStillCarryCorsHeaders() throws Exception {
		mockMvc.perform(extract("{\"term\":\"2027-SPRING\",\"text\":\"\"}").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
			.andExpect(status().isBadRequest())
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN));
	}

	private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder extract(String json) {
		return post(URL).contentType(MediaType.APPLICATION_JSON).content(json);
	}

}
