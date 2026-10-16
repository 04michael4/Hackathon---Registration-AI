package io.github.michael04.registration.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.michael04.registration.ai.ClaudeGateway.Outcome;
import io.github.michael04.registration.ai.ClaudeGateway.Reply;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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

	private ClaudeGateway gateway(int maxRetries, Duration timeout) {
		return AnthropicClaudeGateway.create(
				new ClaudeProperties("test-key", "claude-haiku-5-5", timeout, maxRetries, 4096L, server.baseUrl()));
	}

}
