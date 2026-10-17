package io.github.michael04.registration.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.github.michael04.registration.course.FixtureDatabase;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"app.catalog.source=database", "app.ai.provider=none", "scheduling.node-cap=1"})
class SchedulingNodeCapDatabaseIT {
	static final FixtureDatabase DB = new FixtureDatabase("course_api_" + UUID.randomUUID().toString().replace("-", ""));
	static { DB.prepare(); }
	@DynamicPropertySource static void database(DynamicPropertyRegistry r) {
		r.add("app.catalog.database.url", () -> DB.url);
		r.add("app.catalog.database.username", () -> DB.user);
		r.add("app.catalog.database.password", () -> DB.password);
		r.add("app.catalog.database.schema", () -> DB.schema);
	}
	@AfterAll static void clean() throws Exception { DB.clean(); }
	@Value("${local.server.port}") int port;
	@Autowired JsonMapper json;
	@Autowired SchedulingProperties properties;

	@Test void customCapReachesTheActualSolverAndProducesItsLimitFlag() throws Exception {
		assertThat(properties.nodeCap()).isEqualTo(1);
		var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/schedules/generate"))
			.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("""
			{"term":"TEST-2027","maxResults":10,"preferences":{"hardConstraints":{
			"requiredCourseIds":["CSE-007","MATH-021"],"minCredits":8,"maxCredits":8}}}
			""")).build(), HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		assertThat(json.readTree(response.body()).get("searchLimitReached").asBoolean()).isTrue();
	}
}
