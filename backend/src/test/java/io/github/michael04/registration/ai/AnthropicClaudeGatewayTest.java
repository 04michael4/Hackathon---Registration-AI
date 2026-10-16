package io.github.michael04.registration.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.michael04.registration.common.AiUnavailableException;
import io.github.michael04.registration.ai.ClaudeGateway.Outcome;
import io.github.michael04.registration.ai.ClaudeGateway.Reply;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The real Anthropic SDK against {@link FakeAnthropicServer}: verifies what we send, how responses
 * are interpreted, and how every failure maps to our status codes. No external calls.
 */
class AnthropicClaudeGatewayTest {

	private static final String VALID_JSON = """
			{"requiredCourses":["CSE 17","Calc 1"],"optionalCourses":[],"optionalCoursesWeight":null,
			 "minCredits":null,"maxCredits":null,
			 "blockedTimes":[{"days":["TUESDAY","THURSDAY"],"start":"15:00","end":"18:00"}],
			 "earliestStart":"10:00","earliestStartWeight":4,"latestEnd":null,"latestEndWeight":null,
			 "daysOff":["FRIDAY"],"daysOffWeight":2,"minimizeGapsWeight":null,"assumptions":[]}
			""";

	private final FakeAnthropicServer server = new FakeAnthropicServer();

	private ClaudeGateway gateway = gateway(0, Duration.ofSeconds(5));

	@AfterEach
	void tearDown() {
		gateway.close();
		server.close();
	}

	@Test
	void sendsStructuredOutputRequestWithConfiguredModelAndLowEffort() {
		server.enqueue(FakeAnthropicServer.message("end_turn", VALID_JSON));

		gateway.send("SYSTEM PROMPT", "<student_text>\nI need CSE 17\n</student_text>");

		FakeAnthropicServer.Recorded request = server.requests().get(0);
		assertThat(request.method()).isEqualTo("POST");
		assertThat(request.path()).isEqualTo("/v1/messages");
		assertThat(request.header("x-api-key")).isEqualTo("test-key");
		JsonNode body = JsonMapper.builder().build().readTree(request.body());
		assertThat(body.path("model").asString()).isEqualTo("claude-haiku-5-5");
		assertThat(body.path("max_tokens").asLong()).isEqualTo(4096);
		assertThat(body.path("system").toString()).contains("SYSTEM PROMPT");
		assertThat(body.path("messages").get(0).path("role").asString()).isEqualTo("user");
		assertThat(body.path("messages").toString()).contains("I need CSE 17");
		assertThat(body.path("output_config").path("effort").asString()).isEqualTo("low");
		JsonNode schema = body.path("output_config").path("format").path("schema");
		assertThat(body.path("output_config").path("format").path("type").asString()).isEqualTo("json_schema");
		assertThat(schema.path("properties").has("requiredCourses")).isTrue();
		// "Not mentioned" must be expressible, so the model never has to invent a value.
		assertThat(schema.path("properties").path("minCredits").path("type").toString()).contains("null");
	}

	/** The properties we ask the model for must be exactly the ones the validator insists on. */
	@Test
	void theSchemaSentToTheModelAndTheValidatorsRequiredPropertiesAgree() {
		server.enqueue(FakeAnthropicServer.message("end_turn", VALID_JSON));

		gateway.send("s", "u");

		JsonNode schema = JSON.readTree(server.requests().get(0).body()).path("output_config").path("format").path("schema");
		List<String> sent = schema.path("properties").propertyNames().stream().toList();
		assertThat(sent).containsExactlyInAnyOrderElementsOf(ClaudeExtractionValidator.requiredProperties());
		List<String> required = schema.path("required").valueStream().map(JsonNode::asString).toList();
		assertThat(required).containsExactlyInAnyOrderElementsOf(ClaudeExtractionValidator.requiredProperties());
	}

	@Test
	void completeResponseIsParsedIntoClaudeExtraction() {
		server.enqueue(FakeAnthropicServer.message("end_turn", VALID_JSON));

		Reply reply = gateway.send("s", "u");

		assertThat(reply.outcome()).isEqualTo(Outcome.COMPLETE);
		RawPreferences raw = reply.extraction().toRawPreferences();
		assertThat(raw.requiredCourses()).containsExactly("CSE 17", "Calc 1");
		assertThat(raw.minCredits()).isNull();
		assertThat(raw.blockedTimes()).containsExactly(
				new RawPreferences.RawTimeBlock(List.of("TUESDAY", "THURSDAY"), "15:00", "18:00"));
		assertThat(raw.earliestStart()).isEqualTo("10:00");
		assertThat(raw.earliestStartWeight()).isEqualTo(4);
		assertThat(raw.daysOff()).containsExactly("FRIDAY");
		assertThat(raw.latestEnd()).isNull();
	}

	@Test
	void thinkingBlockBeforeTextIsSkipped() {
		server.enqueue(FakeAnthropicServer.messageWithThinking(VALID_JSON));

		assertThat(gateway.send("s", "u").outcome()).isEqualTo(Outcome.COMPLETE);
	}

	@Test
	void refusalIsReportedNotParsed() {
		server.enqueue(FakeAnthropicServer.refusal());

		Reply reply = gateway.send("s", "u");

		assertThat(reply.outcome()).isEqualTo(Outcome.REFUSED);
		assertThat(reply.extraction()).isNull();
	}

	@Test
	void maxTokensIsReportedAsTruncated() {
		server.enqueue(FakeAnthropicServer.message("max_tokens", "{\"requiredCourses\":[\"CSE"));

		assertThat(gateway.send("s", "u").outcome()).isEqualTo(Outcome.TRUNCATED);
	}

	@Test
	void invalidJsonIsReportedAsMalformed() {
		server.enqueue(FakeAnthropicServer.message("end_turn", "this is not json"));

		assertThat(gateway.send("s", "u").outcome()).isEqualTo(Outcome.MALFORMED);
	}

	@Test
	void unexpectedStopReasonIsReportedAsMalformed() {
		server.enqueue(FakeAnthropicServer.message("tool_use", VALID_JSON));

		assertThat(gateway.send("s", "u").outcome()).isEqualTo(Outcome.MALFORMED);
	}

	@Test
	void rateLimitMapsTo429() {
		server.enqueue(FakeAnthropicServer.error(429, "rate_limit_error", "slow down"));

		assertThatThrownBy(() -> gateway.send("s", "u")).isInstanceOf(AiRateLimitedException.class);
	}

	@Test
	void badCredentialsMapTo503() {
		server.enqueue(FakeAnthropicServer.error(401, "authentication_error", "invalid x-api-key"));

		assertThatThrownBy(() -> gateway.send("s", "u")).isInstanceOf(AiUnavailableException.class);
	}

	@Test
	void overloadedProviderMapsTo503() {
		server.enqueue(FakeAnthropicServer.error(529, "overloaded_error", "overloaded"));

		assertThatThrownBy(() -> gateway.send("s", "u")).isInstanceOf(AiUnavailableException.class);
	}

	@Test
	void internalProviderErrorMapsTo502() {
		server.enqueue(FakeAnthropicServer.error(500, "api_error", "boom"));

		assertThatThrownBy(() -> gateway.send("s", "u")).isInstanceOf(AiProviderException.class);
	}

	@Test
	void badRequestOrUnknownModelMapsTo502() {
		server.enqueue(FakeAnthropicServer.error(404, "not_found_error", "model: nope"));

		assertThatThrownBy(() -> gateway.send("s", "u")).isInstanceOf(AiProviderException.class);
	}

	@Test
	void errorsDoNotLeakProviderMessagesIntoOurExceptions() {
		server.enqueue(FakeAnthropicServer.error(500, "api_error", "secret internal detail"));

		assertThatThrownBy(() -> gateway.send("s", "u")).hasMessageNotContaining("secret internal detail");
	}

	@Test
	void timeoutMapsTo503() {
		gateway.close();
		gateway = gateway(0, Duration.ofMillis(300));
		server.enqueue(FakeAnthropicServer.delayed(FakeAnthropicServer.message("end_turn", VALID_JSON), Duration.ofSeconds(3)));

		assertThatThrownBy(() -> gateway.send("s", "u")).isInstanceOf(AiUnavailableException.class);
	}

	@Test
	void connectionFailureMapsTo503() {
		ClaudeGateway unreachable = AnthropicClaudeGateway.create(new ClaudeProperties("test-key", "claude-haiku-5-5",
				Duration.ofSeconds(2), 0, 4096L, "http://127.0.0.1:1"));
		try {
			assertThatThrownBy(() -> unreachable.send("s", "u")).isInstanceOf(AiUnavailableException.class);
		}
		finally {
			unreachable.close();
		}
	}

	@Test
	void sdkRetriesTransientErrorsWithinTheConfiguredBound() {
		gateway.close();
		gateway = gateway(1, Duration.ofSeconds(5));
		server.enqueue(FakeAnthropicServer.error(529, "overloaded_error", "overloaded"),
				FakeAnthropicServer.message("end_turn", VALID_JSON));

		assertThat(gateway.send("s", "u").outcome()).isEqualTo(Outcome.COMPLETE);
		assertThat(server.requests()).hasSize(2);
	}

	@Test
	void retriesStopAtTheConfiguredBound() {
		gateway.close();
		gateway = gateway(1, Duration.ofSeconds(5));
		server.enqueue(FakeAnthropicServer.error(429, "rate_limit_error", "slow down"),
				FakeAnthropicServer.error(429, "rate_limit_error", "slow down"),
				FakeAnthropicServer.message("end_turn", VALID_JSON));

		assertThatThrownBy(() -> gateway.send("s", "u")).isInstanceOf(AiRateLimitedException.class);
		assertThat(server.requests()).hasSize(2);
	}

	// ---- required properties: a reply must contain every property of the ClaudeExtraction contract ----

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** Every top-level property in the structured-output contract; the SDK marks all of them required. */
	private static final String[] TOP_LEVEL = { "requiredCourses", "optionalCourses", "optionalCoursesWeight", "minCredits",
			"maxCredits", "blockedTimes", "earliestStart", "earliestStartWeight", "latestEnd", "latestEndWeight", "daysOff",
			"daysOffWeight", "minimizeGapsWeight", "assumptions" };

	private static ObjectNode validObject() {
		return (ObjectNode) JSON.readTree(VALID_JSON);
	}

	private Reply replyFor(String modelText) {
		server.enqueue(FakeAnthropicServer.message("end_turn", modelText));
		return gateway.send("s", "u");
	}

	/** Review finding: a reply of {} used to be accepted as COMPLETE with every list silently empty. */
	@Test
	void emptyObjectIsMalformedNotComplete() {
		Reply reply = replyFor("{}");

		assertThat(reply.outcome()).isEqualTo(Outcome.MALFORMED);
		assertThat(reply.extraction()).isNull();
	}

	@ParameterizedTest(name = "missing {0}")
	@ValueSource(strings = { "requiredCourses", "optionalCourses", "optionalCoursesWeight", "minCredits", "maxCredits",
			"blockedTimes", "earliestStart", "earliestStartWeight", "latestEnd", "latestEndWeight", "daysOff",
			"daysOffWeight", "minimizeGapsWeight", "assumptions" })
	void aReplyMissingAnyOneTopLevelPropertyIsMalformed(String property) {
		ObjectNode node = validObject();
		node.remove(property);

		assertThat(replyFor(node.toString()).outcome()).isEqualTo(Outcome.MALFORMED);
	}

	@Test
	void aReplyWithOnlySomeRequiredFieldsIsMalformed() {
		assertThat(replyFor("{\"requiredCourses\":[\"CSE 17\"],\"optionalCourses\":[]}").outcome()).isEqualTo(Outcome.MALFORMED);
	}

	@ParameterizedTest(name = "blockedTimes[0] missing {0}")
	@ValueSource(strings = { "days", "start", "end" })
	void aBlockedTimeMissingAnyRequiredFieldIsMalformed(String property) {
		ObjectNode node = validObject();
		((ObjectNode) node.get("blockedTimes").get(0)).remove(property);

		assertThat(replyFor(node.toString()).outcome()).isEqualTo(Outcome.MALFORMED);
	}

	@Test
	void aReplyIsMalformedWhenOnlyTheSecondBlockedTimeIsIncomplete() {
		ObjectNode node = validObject();
		((ArrayNode) node.get("blockedTimes"))
			.add(JSON.readTree("{\"days\":[\"MONDAY\"],\"start\":\"08:00\"}"));

		assertThat(replyFor(node.toString()).outcome()).isEqualTo(Outcome.MALFORMED);
	}

	/** An explicit null is a real answer for the nullable fields; an explicit [] is a real answer for lists. */
	@Test
	void aCompleteReplyWithEmptyListsAndExplicitNullsIsComplete() {
		Reply reply = replyFor("""
				{"requiredCourses":[],"optionalCourses":[],"optionalCoursesWeight":null,"minCredits":null,
				 "maxCredits":null,"blockedTimes":[],"earliestStart":null,"earliestStartWeight":null,
				 "latestEnd":null,"latestEndWeight":null,"daysOff":[],"daysOffWeight":null,
				 "minimizeGapsWeight":null,"assumptions":[]}
				""");

		assertThat(reply.outcome()).isEqualTo(Outcome.COMPLETE);
		RawPreferences raw = reply.extraction().toRawPreferences();
		assertThat(raw.requiredCourses()).isEmpty();
		assertThat(raw.optionalCourses()).isEmpty();
		assertThat(raw.blockedTimes()).isEmpty();
		assertThat(raw.minCredits()).isNull();
		assertThat(raw.earliestStart()).isNull();
	}

	@ParameterizedTest(name = "explicit null for {0}")
	@ValueSource(strings = { "optionalCoursesWeight", "minCredits", "maxCredits", "earliestStart", "earliestStartWeight",
			"latestEnd", "latestEndWeight", "daysOffWeight", "minimizeGapsWeight" })
	void anExplicitNullIsAcceptedForEachNullableField(String property) {
		ObjectNode node = validObject();
		node.putNull(property);

		assertThat(replyFor(node.toString()).outcome()).isEqualTo(Outcome.COMPLETE);
	}

	@ParameterizedTest(name = "null for non-nullable {0}")
	@ValueSource(strings = { "requiredCourses", "optionalCourses", "blockedTimes", "daysOff", "assumptions" })
	void anExplicitNullIsNotAcceptedWhereAListIsRequired(String property) {
		ObjectNode node = validObject();
		node.putNull(property);

		assertThat(replyFor(node.toString()).outcome()).isEqualTo(Outcome.MALFORMED);
	}

	@Test
	void anExplicitNullIsNotAcceptedForARequiredBlockedTimeField() {
		ObjectNode node = validObject();
		((ObjectNode) node.get("blockedTimes").get(0)).putNull("start");

		assertThat(replyFor(node.toString()).outcome()).isEqualTo(Outcome.MALFORMED);
	}

	@Test
	void presentFieldsOfTheWrongTypeAreMalformed() {
		for (String[] change : new String[][] { { "requiredCourses", "\"CSE 17\"" }, { "minCredits", "\"12\"" },
				{ "minCredits", "12.5" }, { "earliestStart", "10" }, { "daysOff", "\"FRIDAY\"" },
				{ "assumptions", "{}" }, { "blockedTimes", "{}" }, { "optionalCoursesWeight", "true" } }) {
			ObjectNode node = validObject();
			node.set(change[0], JSON.readTree(change[1]));

			assertThat(replyFor(node.toString()).outcome()).as(change[0] + " = " + change[1]).isEqualTo(Outcome.MALFORMED);
		}
	}

	@Test
	void theDiagnosticNamesTheMissingPropertyButNeverEchoesModelOutput() {
		String secret = "sensitive-student-text-123";
		ObjectNode node = validObject();
		node.remove("minCredits");
		node.putArray("assumptions").add(secret);

		Reply reply = replyFor(node.toString());

		assertThat(reply.outcome()).isEqualTo(Outcome.MALFORMED);
		assertThat(reply.detail()).contains("minCredits").doesNotContain(secret).doesNotContain("CSE 17");
	}

	@Test
	void aMalformedThenAValidResponseIsHandledByTheTwoCallsIndependently() {
		server.enqueue(FakeAnthropicServer.message("end_turn", "{}"), FakeAnthropicServer.message("end_turn", VALID_JSON));

		assertThat(gateway.send("s", "u").outcome()).isEqualTo(Outcome.MALFORMED);
		assertThat(gateway.send("s", "u").outcome()).isEqualTo(Outcome.COMPLETE);
	}


	private ClaudeGateway gateway(int maxRetries, Duration timeout) {
		return AnthropicClaudeGateway.create(
				new ClaudeProperties("test-key", "claude-haiku-5-5", timeout, maxRetries, 4096L, server.baseUrl()));
	}

}
