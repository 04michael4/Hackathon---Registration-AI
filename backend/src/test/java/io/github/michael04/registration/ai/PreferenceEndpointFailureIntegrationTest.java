package io.github.michael04.registration.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import io.github.michael04.registration.common.AiUnavailableException;

/**
 * Every AI failure through the full application and the shared {@code GlobalExceptionHandler}:
 * correct status, Problem Details body, matching request ID, nothing sensitive echoed.
 * The extractor is a Mockito fake; no API key or network.
 */
@SpringBootTest(properties = "app.ai.provider=none")
@AutoConfigureMockMvc
class PreferenceEndpointFailureIntegrationTest {

	private static final String SENSITIVE = "sensitive-test-input";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PreferenceExtractor extractor;

	static Stream<Arguments> failures() {
		return Stream.of(
				Arguments.of("rate limit", (Supplier<RuntimeException>) () -> new AiRateLimitedException(SENSITIVE), 429,
						"Too many requests"),
				Arguments.of("provider error", (Supplier<RuntimeException>) () -> new AiProviderException(SENSITIVE), 502,
						"Upstream service returned an invalid response"),
				Arguments.of("invalid output", (Supplier<RuntimeException>) () -> new AiOutputInvalidException(SENSITIVE), 502,
						"Upstream service returned an invalid response"),
				Arguments.of("unavailable",
						(Supplier<RuntimeException>) () -> new AiUnavailableException(new RuntimeException(SENSITIVE)), 503,
						"AI service is unavailable"));
	}

	@ParameterizedTest(name = "{0} -> {2}")
	@MethodSource("failures")
	void aiFailuresBecomeSafeProblemDetails(String name, Supplier<RuntimeException> failure, int code, String detail)
			throws Exception {
		given(extractor.extract(anyString())).willThrow(failure.get());

		MvcResult result = mockMvc.perform(post("/api/preferences/extract").contentType(MediaType.APPLICATION_JSON)
				.content("{\"term\":\"2027-SPRING\",\"text\":\"I need CSE 17 " + SENSITIVE + "\"}"))
			.andExpect(status().is(code))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("about:blank"))
			.andExpect(jsonPath("$.status").value(code))
			.andExpect(jsonPath("$.detail").value(detail))
			.andExpect(jsonPath("$.instance").value("/api/preferences/extract"))
			.andExpect(jsonPath("$.errors").doesNotExist())
			.andExpect(content().string(not(containsString(SENSITIVE))))
			.andExpect(content().string(not(containsString("Exception"))))
			.andReturn();

		String requestId = result.getResponse().getHeader("X-Request-Id");
		assertThat(requestId).isNotBlank();
		assertThat(result.getResponse().getContentAsString()).contains("\"requestId\":\"" + requestId + "\"");
	}

	@Test
	void eachErrorGetsItsOwnRequestId() throws Exception {
		given(extractor.extract(anyString())).willThrow(new AiRateLimitedException(SENSITIVE));
		var request = post("/api/preferences/extract").contentType(MediaType.APPLICATION_JSON)
			.content("{\"term\":\"2027-SPRING\",\"text\":\"I need CSE 17\"}");

		String first = mockMvc.perform(request).andReturn().getResponse().getHeader("X-Request-Id");
		String second = mockMvc.perform(request).andReturn().getResponse().getHeader("X-Request-Id");

		assertThat(first).isNotEqualTo(second);
	}

}
