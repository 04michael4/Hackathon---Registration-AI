package io.github.michael04.registration.ai;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.michael04.registration.common.AiUnavailableException;
import io.github.michael04.registration.common.json.JsonConfiguration;

@WebMvcTest(ScheduleExplanationController.class)
@Import(JsonConfiguration.class)
class ScheduleExplanationControllerTest {
	@Autowired MockMvc mvc;
	@MockitoBean ScheduleExplanationService service;
	static final String VALID = """
		{"term":"202710","crns":["12275","13132"],"preferences":{"hardConstraints":{
		"requiredCourseIds":["CSE-007"],"minCredits":4,"maxCredits":4}}}
		""";
	@Test void usesTheDocumentedRouteAndResponse() throws Exception {
		when(service.explain(any())).thenReturn(new ExplainScheduleResponse("A compatible lecture and lab.", List.of("Four credits")));
		mvc.perform(post("/api/schedules/explain").contentType("application/json").content(VALID))
			.andExpect(status().isOk()).andExpect(jsonPath("$.explanation").value("A compatible lecture and lab."))
			.andExpect(jsonPath("$.highlights[0]").value("Four credits"));
	}
	@ParameterizedTest @ValueSource(strings = {"{}", "{", "{\"term\":\"202710\",\"crns\":[],\"preferences\":{}}"})
	void invalidBodiesNeverReachTheService(String body) throws Exception {
		mvc.perform(post("/api/schedules/explain").contentType("application/json").content(body)).andExpect(status().isBadRequest());
		verifyNoInteractions(service);
	}
	@Test void unconfiguredAiUsesShared503() throws Exception {
		when(service.explain(any())).thenThrow(new AiUnavailableException());
		mvc.perform(post("/api/schedules/explain").contentType("application/json").content(VALID))
			.andExpect(status().isServiceUnavailable()).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
			.andExpect(header().exists("X-Request-Id"));
	}
	@Test void unsupportedMethodUsesShared405() throws Exception {
		mvc.perform(get("/api/schedules/explain")).andExpect(status().isMethodNotAllowed());
	}
}
