package io.github.michael04.registration.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The real {@code AI_PROVIDER=claude} path (SDK, gateway, extractor, assembler, shared error handler)
 * against {@link FakeAnthropicServer} on localhost. A fake key; no external call; no cost.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ClaudeEndpointFakeServerIntegrationTest {

	private static final FakeAnthropicServer SERVER = new FakeAnthropicServer();

	private static final String SENSITIVE = "sensitive-test-input";

	private static final String VALID_JSON = """
			{"requiredCourses":["CSE 17","Calc 1"],"optionalCourses":[],"optionalCoursesWeight":null,
			 "minCredits":null,"maxCredits":null,
			 "blockedTimes":[{"days":["TUESDAY","THURSDAY"],"start":"15:00","end":"18:00"}],
			 "earliestStart":"10:00","earliestStartWeight":3,"latestEnd":null,"latestEndWeight":null,
			 "daysOff":[],"daysOffWeight":null,"minimizeGapsWeight":null,"minBreakMinutes":null,"minBreakWeight":null,"interestTopics":[],"latestStart":null,"assumptions":[]}
			""";

	@DynamicPropertySource
	static void claudeAgainstFakeServer(DynamicPropertyRegistry registry) {
		registry.add("app.ai.provider", () -> "claude");
		registry.add("app.ai.anthropic.api-key", () -> "test-key-not-real");
		registry.add("app.ai.anthropic.base-url", SERVER::baseUrl);
		registry.add("app.ai.anthropic.max-retries", () -> "0");
		registry.add("app.ai.anthropic.timeout", () -> "5s");
	}

	@BeforeEach
	void noLeftoverRepliesFromEarlierTests() {
		SERVER.clearQueued();
	}

	@AfterAll
	static void stopServer() {
		SERVER.close();
	}

	@Autowired
	private MockMvc mockMvc;

	@Test
	void successfulExtractionThroughTheRealPipeline() throws Exception {
		SERVER.enqueue(FakeAnthropicServer.message("end_turn", VALID_JSON));

		perform().andExpect(status().isOk())
			.andExpect(jsonPath("$.preferences.hardConstraints.requiredCourseIds[0]").value("CSE-017"))
			.andExpect(jsonPath("$.preferences.hardConstraints.requiredCourseIds[1]").value("MATH-021"))
			.andExpect(jsonPath("$.preferences.hardConstraints.blockedTimes[0].start").value("15:00"))
			.andExpect(jsonPath("$.preferences.softPreferences.earliestStart.value").value("10:00"));
	}

	/**
	 * Regression (pre-main merge): the shared HardConstraints is @NotEmpty for the Schedule API, but a
	 * partial extraction with no identifiable course must still be a 200 with a warning, not a 400/500.
	 */
	@Test
	void extractionWithNoRequiredCourseIsStillOkWithWarningAndUnresolved() throws Exception {
		SERVER.enqueue(FakeAnthropicServer.message("end_turn", """
				{"requiredCourses":["Underwater Basket Weaving"],"optionalCourses":[],"optionalCoursesWeight":null,
				 "minCredits":null,"maxCredits":null,"blockedTimes":[],
				 "earliestStart":null,"earliestStartWeight":null,"latestEnd":null,"latestEndWeight":null,
				 "daysOff":[],"daysOffWeight":null,"minimizeGapsWeight":null,"minBreakMinutes":null,"minBreakWeight":null,"interestTopics":[],"latestStart":null,"assumptions":[]}
				"""));

		perform().andExpect(status().isOk())
			.andExpect(jsonPath("$.preferences.hardConstraints.requiredCourseIds").isEmpty())
			.andExpect(jsonPath("$.unresolved[0]").value("Underwater Basket Weaving"))
			.andExpect(jsonPath("$.warnings[0]").value("No required courses were identified; add them before generating schedules."));
	}

	/** Regression (pre-main merge): TimeBlock no longer sorts days, so the AI layer must still de-duplicate and sort them. */
	@Test
	void blockedTimeDaysAreDeduplicatedAndSortedByTheAiLayer() throws Exception {
		SERVER.enqueue(FakeAnthropicServer.message("end_turn", """
				{"requiredCourses":["CSE 17"],"optionalCourses":[],"optionalCoursesWeight":null,
				 "minCredits":12,"maxCredits":15,
				 "blockedTimes":[{"days":["THURSDAY","TUESDAY","TUESDAY"],"start":"15:00","end":"18:00"}],
				 "earliestStart":null,"earliestStartWeight":null,"latestEnd":null,"latestEndWeight":null,
				 "daysOff":["FRIDAY","MONDAY"],"daysOffWeight":2,"minimizeGapsWeight":null,"minBreakMinutes":null,"minBreakWeight":null,"interestTopics":[],"latestStart":null,"assumptions":[]}
				"""));

		perform().andExpect(status().isOk())
			.andExpect(jsonPath("$.preferences.hardConstraints.blockedTimes[0].days.length()").value(2))
			.andExpect(jsonPath("$.preferences.hardConstraints.blockedTimes[0].days[0]").value("TUESDAY"))
			.andExpect(jsonPath("$.preferences.hardConstraints.blockedTimes[0].days[1]").value("THURSDAY"))
			.andExpect(jsonPath("$.preferences.hardConstraints.blockedTimes[0].ordered").doesNotExist())
			.andExpect(jsonPath("$.preferences.softPreferences.daysOff.value[0]").value("MONDAY"))
			.andExpect(jsonPath("$.preferences.softPreferences.daysOff.value[1]").value("FRIDAY"));
	}

	@Test
	void providerRateLimitBecomes429() throws Exception {
		SERVER.enqueue(FakeAnthropicServer.error(429, "rate_limit_error", SENSITIVE));

		assertSafeProblem(perform(), 429, "Too many requests");
	}

	@Test
	void providerOverloadBecomes503() throws Exception {
		SERVER.enqueue(FakeAnthropicServer.error(529, "overloaded_error", SENSITIVE));

		assertSafeProblem(perform(), 503, "AI service is unavailable");
	}

	@Test
	void rejectedCredentialsBecome503() throws Exception {
		SERVER.enqueue(FakeAnthropicServer.error(401, "authentication_error", SENSITIVE));

		assertSafeProblem(perform(), 503, "AI service is unavailable");
	}

	@Test
	void providerInternalErrorBecomes502() throws Exception {
		SERVER.enqueue(FakeAnthropicServer.error(500, "api_error", SENSITIVE));

		assertSafeProblem(perform(), 502, "Upstream service returned an invalid response");
	}

	@Test
	void refusalBecomes502() throws Exception {
		SERVER.enqueue(FakeAnthropicServer.refusal());

		assertSafeProblem(perform(), 502, "Upstream service returned an invalid response");
	}

	@Test
	void malformedOutputIsRetriedOnceThenBecomes502() throws Exception {
		int before = SERVER.requests().size();
		SERVER.enqueue(FakeAnthropicServer.message("end_turn", "not json " + SENSITIVE),
				FakeAnthropicServer.message("end_turn", "still not json " + SENSITIVE));

		assertSafeProblem(perform(), 502, "Upstream service returned an invalid response");
		assertThat(SERVER.requests()).hasSize(before + 2);
	}

	// ---- review finding: a reply missing required properties must be retried once, then fail with 502 ----

	@Test
	void emptyObjectThenAValidReplySucceedsOnTheSecondAttempt() throws Exception {
		int before = SERVER.requests().size();
		SERVER.enqueue(FakeAnthropicServer.message("end_turn", "{}"), FakeAnthropicServer.message("end_turn", VALID_JSON));

		perform().andExpect(status().isOk())
			.andExpect(jsonPath("$.preferences.hardConstraints.requiredCourseIds[0]").value("CSE-017"));
		assertThat(SERVER.requests()).hasSize(before + 2);
	}

	@Test
	void emptyObjectTwiceBecomes502AfterExactlyTwoAttempts() throws Exception {
		int before = SERVER.requests().size();
		SERVER.enqueue(FakeAnthropicServer.message("end_turn", "{}"), FakeAnthropicServer.message("end_turn", "{}"));

		assertSafeProblem(perform(), 502, "Upstream service returned an invalid response");
		assertThat(SERVER.requests()).hasSize(before + 2);
	}

	@Test
	void aReplyMissingOneRequiredPropertyIsRetriedOnceThenBecomes502() throws Exception {
		int before = SERVER.requests().size();
		String missingMinCredits = VALID_JSON.replace("\"minCredits\":null,", "");
		assertThat(missingMinCredits).isNotEqualTo(VALID_JSON);
		SERVER.enqueue(FakeAnthropicServer.message("end_turn", missingMinCredits),
				FakeAnthropicServer.message("end_turn", missingMinCredits));

		assertSafeProblem(perform(), 502, "Upstream service returned an invalid response");
		assertThat(SERVER.requests()).hasSize(before + 2);
	}

	@Test
	void aBlockedTimeMissingItsEndIsRetriedOnceThenBecomes502() throws Exception {
		int before = SERVER.requests().size();
		String noEnd = VALID_JSON.replace(",\"end\":\"18:00\"", "");
		assertThat(noEnd).isNotEqualTo(VALID_JSON);
		SERVER.enqueue(FakeAnthropicServer.message("end_turn", noEnd), FakeAnthropicServer.message("end_turn", noEnd));

		assertSafeProblem(perform(), 502, "Upstream service returned an invalid response");
		assertThat(SERVER.requests()).hasSize(before + 2);
	}

	/** Legitimately empty lists are still a complete answer: 200 with the documented warning, no retry. */
	@Test
	void aCompleteReplyWithEmptyCourseListsIsStill200WithTheNoCoursesWarning() throws Exception {
		int before = SERVER.requests().size();
		SERVER.enqueue(FakeAnthropicServer.message("end_turn", """
				{"requiredCourses":[],"optionalCourses":[],"optionalCoursesWeight":null,
				 "minCredits":null,"maxCredits":null,"blockedTimes":[],
				 "earliestStart":null,"earliestStartWeight":null,"latestEnd":null,"latestEndWeight":null,
				 "daysOff":[],"daysOffWeight":null,"minimizeGapsWeight":null,"minBreakMinutes":null,"minBreakWeight":null,"interestTopics":[],"latestStart":null,"assumptions":[]}
				"""));

		perform().andExpect(status().isOk())
			.andExpect(jsonPath("$.preferences.hardConstraints.requiredCourseIds").isEmpty())
			.andExpect(jsonPath("$.unresolved").isEmpty())
			.andExpect(jsonPath("$.warnings[?(@ == 'No required courses were identified; add them before generating schedules.')]").exists());
		assertThat(SERVER.requests()).hasSize(before + 1);
	}

	/** The malformed-output retry must not widen: refusals and provider failures are never re-asked. */
	@Test
	void aRefusalIsNotRetried() throws Exception {
		int before = SERVER.requests().size();
		// Only the refusal is queued: a retry would reach the empty queue and show up as a second request.
		SERVER.enqueue(FakeAnthropicServer.refusal());

		assertSafeProblem(perform(), 502, "Upstream service returned an invalid response");
		assertThat(SERVER.requests()).hasSize(before + 1);
	}

	@Test
	void aProviderFailureIsNotRetriedByTheExtractor() throws Exception {
		int before = SERVER.requests().size();
		SERVER.enqueue(FakeAnthropicServer.error(500, "api_error", SENSITIVE));

		assertSafeProblem(perform(), 502, "Upstream service returned an invalid response");
		assertThat(SERVER.requests()).hasSize(before + 1);
	}

	@Test
	void theFakeKeyIsSentOnlyToTheFakeServerAndNeverReturned() throws Exception {
		SERVER.enqueue(FakeAnthropicServer.error(500, "api_error", "boom"));

		perform().andExpect(content().string(not(containsString("test-key-not-real"))));
		assertThat(SERVER.requests().get(SERVER.requests().size() - 1).header("x-api-key")).isEqualTo("test-key-not-real");
	}

	private ResultActions perform() throws Exception {
		return mockMvc.perform(post("/api/preferences/extract").contentType(MediaType.APPLICATION_JSON)
			.content("{\"term\":\"2027-SPRING\",\"text\":\"I need CSE 17 and Calc 1\"}"));
	}

	private static void assertSafeProblem(ResultActions result, int code, String detail) throws Exception {
		result.andExpect(status().is(code))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.detail").value(detail))
			.andExpect(jsonPath("$.requestId").isString())
			.andExpect(header().exists("X-Request-Id"))
			.andExpect(content().string(not(containsString(SENSITIVE))));
	}

}
