package io.github.michael04.registration.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import io.github.michael04.registration.health.HealthController;

@WebMvcTest(controllers = HealthController.class, properties = "app.cors.allowed-origins=")
@Import(WebConfig.class)
class DisabledCorsTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void emptyAllowlistRejectsCrossOriginRequestsButKeepsSameOriginAccess() throws Exception {
		mockMvc.perform(get("/api/health").header(HttpHeaders.ORIGIN, "http://localhost:3000"))
			.andExpect(status().isForbidden())
			.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
		mockMvc.perform(get("/api/health").header(HttpHeaders.ORIGIN, "http://localhost"))
			.andExpect(status().isOk());
		mockMvc.perform(get("/api/health"))
			.andExpect(status().isOk());
	}

}
