package io.github.michael04.registration.ai;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.michael04.registration.common.json.JsonConfiguration;
import io.github.michael04.registration.course.DummyCourseCatalog;

// Slice tests skip @Configuration classes, so import the app's JSON formats explicitly.
@WebMvcTest(PreferenceController.class)
@Import(JsonConfiguration.class)
class PreferenceControllerTest {

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
		mockMvc.perform(extract("{\"term\":\"2027-SPRING\",\"text\":\"   \"}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void textOver2000CharactersIsRejected() throws Exception {
		String longText = "a".repeat(2001);
		mockMvc.perform(extract("{\"term\":\"2027-SPRING\",\"text\":\"" + longText + "\"}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void missingTermIsRejected() throws Exception {
		mockMvc.perform(extract("{\"text\":\"I need CSE 17\"}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void unknownTermReturns422() throws Exception {
		given(service.extract(anyString(), anyString())).willThrow(new UnknownTermException("1999-FALL"));

		mockMvc.perform(extract("{\"term\":\"1999-FALL\",\"text\":\"I need CSE 17\"}"))
			.andExpect(status().isUnprocessableContent());
	}

	@Test
	void unavailableAiReturns503() throws Exception {
		given(service.extract(anyString(), anyString())).willThrow(new AiUnavailableException("not configured"));

		mockMvc.perform(extract("{\"term\":\"2027-SPRING\",\"text\":\"I need CSE 17\"}"))
			.andExpect(status().isServiceUnavailable());
	}

	@Test
	void invalidAiOutputReturns502() throws Exception {
		given(service.extract(anyString(), anyString())).willThrow(new AiOutputInvalidException("bad output"));

		mockMvc.perform(extract("{\"term\":\"2027-SPRING\",\"text\":\"I need CSE 17\"}"))
			.andExpect(status().isBadGateway());
	}

	private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder extract(String json) {
		return post("/api/preferences/extract").contentType(MediaType.APPLICATION_JSON).content(json);
	}

}
