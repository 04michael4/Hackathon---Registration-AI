package io.github.michael04.registration.common;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import io.github.michael04.registration.health.HealthController;

@WebMvcTest(HealthController.class)
@Import(WebConfig.class)
class WebConfigTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void allowedOriginCanReadHealthWithoutCredentials() throws Exception {
		mockMvc.perform(get("/api/health").header(HttpHeaders.ORIGIN, "http://localhost:3000"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"))
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"))
			.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
	}

	@Test
	void preflightAllowsJsonPost() throws Exception {
		mockMvc.perform(options("/api/health")
			.header(HttpHeaders.ORIGIN, "http://localhost:3000")
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type"))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"))
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET,HEAD,POST,OPTIONS"))
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "content-type"))
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"https://untrusted.example", "http://localhost:3000.untrusted.example", "null"})
	void rejectedOriginsUseProblemDetails(String origin) throws Exception {
		mockMvc.perform(get("/api/health").header(HttpHeaders.ORIGIN, origin))
			.andExpect(status().isForbidden())
			.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("about:blank"))
			.andExpect(jsonPath("$.status").value(403))
			.andExpect(jsonPath("$.detail").value("Cross-origin request is not allowed"))
			.andExpect(jsonPath("$.instance").value("/api/health"))
			.andExpect(jsonPath("$.requestId").isString())
			.andExpect(jsonPath("$.properties").doesNotExist());
	}

	@Test
	void disallowedPreflightMethodIsRejected() throws Exception {
		mockMvc.perform(options("/api/health")
			.header(HttpHeaders.ORIGIN, "http://localhost:3000")
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "DELETE"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.status").value(403))
			.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
	}

	@Test
	void disallowedPreflightHeaderIsRejected() throws Exception {
		mockMvc.perform(options("/api/health")
			.header(HttpHeaders.ORIGIN, "http://localhost:3000")
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.status").value(403));
	}

	@Test
	void allowedOriginCanReadErrorResponses() throws Exception {
		mockMvc.perform(get("/api/missing").header(HttpHeaders.ORIGIN, "http://localhost:3000"))
			.andExpect(status().isNotFound())
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"))
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "X-Request-Id"))
			.andExpect(jsonPath("$.status").value(404));
	}

	@Test
	void requestsWithoutOriginStillWork() throws Exception {
		mockMvc.perform(get("/api/health"))
			.andExpect(status().isOk())
			.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
	}

	@Test
	void corsDoesNotExposePathsOutsideApi() throws Exception {
		mockMvc.perform(get("/outside-api").header(HttpHeaders.ORIGIN, "http://localhost:3000"))
			.andExpect(status().isNotFound())
			.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
	}

	@ParameterizedTest
	@ValueSource(strings = {"*", "https://*.example.com", "https://example.com/", "https://example.com/path",
		"https://user:password@example.com", "https://example.com?query=value", "https://example.com#fragment",
		"file:///tmp", "null", "https://example.com:70000"})
	void invalidOriginConfigurationFailsFast(String origin) {
		assertThatThrownBy(() -> new WebConfig(origin)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("CORS allowed origins must be exact HTTP(S) origins");
	}

}
