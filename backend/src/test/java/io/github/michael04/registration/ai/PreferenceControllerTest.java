package io.github.michael04.registration.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import io.github.michael04.registration.common.AiUnavailableException;
import io.github.michael04.registration.common.json.JsonConfiguration;
import io.github.michael04.registration.course.DummyCourseCatalog;

/**
 * The extract endpoint through the shared {@code common.GlobalExceptionHandler}, which {@code @WebMvcTest}
 * loads automatically as controller advice: every error is an RFC 9457 Problem Details body with a
 * request ID, and nothing sensitive is echoed.
 */
// Slice tests skip @Configuration classes, so import the app's JSON formats explicitly.
@WebMvcTest(PreferenceController.class)
@Import(JsonConfiguration.class)
class PreferenceControllerTest {

	/** Planted in every exception and request; must never appear in a response. */
	private static final String SENSITIVE = "sensitive-test-input";

	private static final String INSTANCE = "/api/preferences/extract";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PreferenceExtractionService service;

	private final PreferenceAssembler assembler =
			new PreferenceAssembler(new CourseMentionResolver(new DummyCourseCatalog()));

	@Test
	void returnsPreferencesInContractShape() throws Exception {
		given(service.extract(anyString(), anyString()))
			.willReturn(assembler.assemble(DummyCourseCatalog.TERM, new DummyPreferenceExtractor().extract("x")));

		mockMvc.perform(extract("{\"term\":\"2027-SPRING\",\"text\":\"I need CSE 17 and Calc 1\"}"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.preferences.hardConstraints.requiredCourseIds[0]").value("CSE-017"))
			.andExpect(jsonPath("$.preferences.hardConstraints.requiredCourseIds[1]").value("MATH-021"))
			.andExpect(jsonPath("$.preferences.hardConstraints.minCredits").value(12))
			.andExpect(jsonPath("$.preferences.hardConstraints.maxCredits").value(18))
			.andExpect(jsonPath("$.preferences.hardConstraints.blockedTimes[0].days[0]").value("TUESDAY"))
			.andExpect(jsonPath("$.preferences.hardConstraints.blockedTimes[0].start").value("15:00"))
			.andExpect(jsonPath("$.preferences.hardConstraints.blockedTimes[0].end").value("18:00"))
			.andExpect(jsonPath("$.preferences.softPreferences.earliestStart.value").value("10:00"))
			.andExpect(jsonPath("$.preferences.softPreferences.earliestStart.weight").value(4))
			.andExpect(jsonPath("$.preferences.softPreferences.daysOff.value[0]").value("FRIDAY"))
			// Preferences the student didn't express are omitted, not null.
			.andExpect(jsonPath("$.preferences.softPreferences.latestEnd").doesNotExist())
			.andExpect(jsonPath("$.unresolved").isArray())
			.andExpect(jsonPath("$.warnings").isArray());
	}

	@Test
	void blankTextIsRejected() throws Exception {
		assertProblem(mockMvc.perform(extract("{\"term\":\"" + SENSITIVE + "\",\"text\":\"   \"}")), 400,
				"Request validation failed")
			.andExpect(jsonPath("$.errors[0].field").value("text"));
	}

	@Test
	void textOver2000CharactersIsRejected() throws Exception {
		String longText = SENSITIVE + "a".repeat(2001);
		assertProblem(mockMvc.perform(extract("{\"term\":\"2027-SPRING\",\"text\":\"" + longText + "\"}")), 400,
				"Request validation failed")
			.andExpect(jsonPath("$.errors[0].field").value("text"))
			.andExpect(content().string(not(containsString("aaaaaaaaaa"))));
	}

	@Test
	void missingTermIsRejected() throws Exception {
		assertProblem(mockMvc.perform(extract("{\"text\":\"" + SENSITIVE + "\"}")), 400, "Request validation failed")
			.andExpect(jsonPath("$.errors[0].field").value("term"));
	}

	@Test
	void unknownTermReturns422() throws Exception {
		given(service.extract(anyString(), anyString())).willThrow(new UnknownTermException(SENSITIVE));

		// The fixed public message; the submitted term is never echoed.
		assertProblem(mockMvc.perform(extract("{\"term\":\"" + SENSITIVE + "\",\"text\":\"I need CSE 17\"}")), 422,
				"Term is not available");
	}

	@Test
	void unavailableAiReturns503() throws Exception {
		given(service.extract(anyString(), anyString()))
			.willThrow(new AiUnavailableException(new RuntimeException(SENSITIVE)));

		assertProblem(mockMvc.perform(extract("{\"term\":\"2027-SPRING\",\"text\":\"" + SENSITIVE + "\"}")), 503,
				"AI service is unavailable");
	}

	@Test
	void rateLimitedAiReturns429() throws Exception {
		given(service.extract(anyString(), anyString())).willThrow(new AiRateLimitedException(SENSITIVE));

		assertProblem(mockMvc.perform(extract("{\"term\":\"2027-SPRING\",\"text\":\"" + SENSITIVE + "\"}")), 429,
				"Too many requests");
	}

	@Test
	void providerErrorReturns502() throws Exception {
		given(service.extract(anyString(), anyString())).willThrow(new AiProviderException(SENSITIVE));

		assertProblem(mockMvc.perform(extract("{\"term\":\"2027-SPRING\",\"text\":\"" + SENSITIVE + "\"}")), 502,
				"Upstream service returned an invalid response");
	}

	@Test
	void invalidAiOutputReturns502() throws Exception {
		given(service.extract(anyString(), anyString())).willThrow(new AiOutputInvalidException(SENSITIVE));

		assertProblem(mockMvc.perform(extract("{\"term\":\"2027-SPRING\",\"text\":\"" + SENSITIVE + "\"}")), 502,
				"Upstream service returned an invalid response");
	}

	/** Problem Details shape shared with common.GlobalExceptionHandlerTest. */
	private static ResultActions assertProblem(ResultActions result, int code, String detail) throws Exception {
		result.andExpect(status().is(code))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("about:blank"))
			.andExpect(jsonPath("$.status").value(code))
			.andExpect(jsonPath("$.detail").value(detail))
			.andExpect(jsonPath("$.instance").value(INSTANCE))
			.andExpect(jsonPath("$.requestId").isString())
			.andExpect(header().exists("X-Request-Id"))
			.andExpect(content().string(not(containsString(SENSITIVE))));
		MvcResult mvc = result.andReturn();
		assertThat(mvc.getResponse().getContentAsString())
			.contains("\"requestId\":\"" + mvc.getResponse().getHeader("X-Request-Id") + "\"");
		return result;
	}

	private static MockHttpServletRequestBuilder extract(String json) {
		return post(INSTANCE).contentType(MediaType.APPLICATION_JSON).content(json);
	}

}
