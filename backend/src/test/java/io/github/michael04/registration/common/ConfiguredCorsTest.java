package io.github.michael04.registration.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import io.github.michael04.registration.health.HealthController;

@WebMvcTest(controllers = HealthController.class, properties =
	"app.cors.allowed-origins=https://planner.example, https://preview.example")
@Import(WebConfig.class)
class ConfiguredCorsTest {

	@Autowired
	private MockMvc mockMvc;

	@ParameterizedTest
	@ValueSource(strings = {"https://planner.example", "https://preview.example"})
	void configurationAllowsEachExactOrigin(String origin) throws Exception {
		mockMvc.perform(get("/api/health").header(HttpHeaders.ORIGIN, origin))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin));
	}

	@Test
	void configurationReplacesTheLocalhostDefault() throws Exception {
		mockMvc.perform(get("/api/health").header(HttpHeaders.ORIGIN, "http://localhost:3000"))
			.andExpect(status().isForbidden())
			.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
	}

}
