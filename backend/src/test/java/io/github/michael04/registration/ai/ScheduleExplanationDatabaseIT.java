package io.github.michael04.registration.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.github.michael04.registration.course.FixtureDatabase;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real HTTP, PostgreSQL, SelectionChecker, explanation service and official SDK; only the external AI is a local fake. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = {"app.catalog.source=database", "app.ai.provider=claude"})
class ScheduleExplanationDatabaseIT {
	static final FixtureDatabase DB = new FixtureDatabase("course_api_" + UUID.randomUUID().toString().replace("-", ""));
	static final FakeAnthropicServer SERVER = new FakeAnthropicServer();
	static { DB.prepare(); }
	static final String REQUEST = """
		{"term":"TEST-2027","crns":["12275","13132"],"preferences":{"hardConstraints":{
		"requiredCourseIds":["CSE-007"],"minCredits":4,"maxCredits":4}}}
		""";
	static final String OUTPUT = """
		{"explanation":"This 4-credit schedule pairs the lecture with its required lab.",
		"highlights":["Includes CSE-007","Lecture and lab have no time conflict"]}
		""";
	@DynamicPropertySource static void configuration(DynamicPropertyRegistry r) {
		r.add("app.catalog.database.url", () -> DB.url);
		r.add("app.catalog.database.username", () -> DB.user);
		r.add("app.catalog.database.password", () -> DB.password);
		r.add("app.catalog.database.schema", () -> DB.schema);
		r.add("app.ai.anthropic.api-key", () -> "local-fake-key");
		r.add("app.ai.anthropic.base-url", SERVER::baseUrl);
		r.add("app.ai.anthropic.timeout", () -> "5s");
		r.add("app.ai.anthropic.max-retries", () -> "0");
	}
	@AfterAll static void clean() throws Exception { SERVER.close(); DB.clean(); }
	@BeforeEach void clearQueue() { SERVER.clearQueued(); }
	@Value("${local.server.port}") int port;
	@Autowired JsonMapper json;
	final HttpClient client = HttpClient.newHttpClient();

	JsonNode send(String body, int status) throws Exception {
		var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/schedules/explain"))
			.header("Content-Type", "application/json").header("Origin", "http://localhost:3000")
			.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
		assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:3000");
		if (status >= 400) {
			assertThat(response.body()).doesNotContain("sensitive-provider-secret", "local-fake-key", "Exception");
			assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
		}
		return json.readTree(response.body());
	}
	@Test void explanationUsesReloadedCatalogAndEngineFactsRatherThanClientClaims() throws Exception {
		SERVER.enqueue(FakeAnthropicServer.message("end_turn", OUTPUT));
		var response = send(REQUEST.replace("\"term\":", "\"title\":\"client-invented-title\",\"term\":"), 200);
		assertThat(response.get("explanation").asString()).contains("4-credit");
		assertThat(response.get("highlights").size()).isEqualTo(2);
		String raw = SERVER.requests().getLast().body();
		assertThat(raw).doesNotContain("client-invented-title");
		var content = json.readTree(raw).get("messages").get(0).get("content");
		var facts = json.readTree(content.isString() ? content.asString() : content.get(0).get("text").asString());
		assertThat(facts.get("totalCredits").asInt()).isEqualTo(4);
		assertThat(facts.get("score").asInt()).isEqualTo(100);
		assertThat(facts.get("sections").get(0).get("title").asString()).isEqualTo("Synthetic Introduction to Programming");
	}
	@ParameterizedTest @ValueSource(strings = {"{}", "{", "{\"term\":\"TEST-2027\",\"crns\":[],\"preferences\":{}}"})
	void invalidBodiesNeverInvokeTheAi(String body) throws Exception {
		int before = SERVER.requests().size();
		send(body, 400);
		assertThat(SERVER.requests()).hasSize(before);
	}
	@Test void unknownTermOrCrnNeverCostsAnAiCall() throws Exception {
		int before = SERVER.requests().size();
		send(REQUEST.replace("TEST-2027", "UNKNOWN"), 422);
		send(REQUEST.replace("13132", "99999"), 422);
		assertThat(SERVER.requests()).hasSize(before);
	}
	@Test void missingLabOrReversedCreditsAreRejectedBeforeAi() throws Exception {
		int before = SERVER.requests().size();
		send(REQUEST.replace("\"12275\",\"13132\"", "\"12275\""), 422);
		send(REQUEST.replace("\"minCredits\":4", "\"minCredits\":5"), 400);
		assertThat(SERVER.requests()).hasSize(before);
	}
	@Test void incompleteOutputIsRetriedOnceThenFailsSafely() throws Exception {
		int before = SERVER.requests().size();
		SERVER.enqueue(FakeAnthropicServer.message("end_turn", "{}"), FakeAnthropicServer.message("end_turn", "{}"));
		send(REQUEST, 502);
		assertThat(SERVER.requests()).hasSize(before + 2);
	}
	@Test void malformedThenValidOutputSucceedsOnTheSecondAttempt() throws Exception {
		int before = SERVER.requests().size();
		SERVER.enqueue(FakeAnthropicServer.message("end_turn", "{}"), FakeAnthropicServer.message("end_turn", OUTPUT));
		send(REQUEST, 200);
		assertThat(SERVER.requests()).hasSize(before + 2);
	}
	@Test void refusalIsFinal() throws Exception {
		int before = SERVER.requests().size();
		SERVER.enqueue(FakeAnthropicServer.refusal());
		send(REQUEST, 502);
		assertThat(SERVER.requests()).hasSize(before + 1);
	}
	@ParameterizedTest @ValueSource(ints = {429, 500, 503})
	void providerErrorsUseTheSharedSafeStatusMapping(int status) throws Exception {
		SERVER.enqueue(FakeAnthropicServer.error(status, "api_error", "sensitive-provider-secret"));
		send(REQUEST, status == 500 ? 502 : status);
	}
}
