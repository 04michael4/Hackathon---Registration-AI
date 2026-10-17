package io.github.michael04.registration.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** Reuses the same real database pipeline with a different application property, not a replacement solver. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = {"app.catalog.source=database", "app.ai.provider=none", "server.address=127.0.0.1", "scheduling.node-cap=1"})
class SchedulingNodeCapDatabaseIT {
	@DynamicPropertySource static void database(DynamicPropertyRegistry r) { ScheduleDatabaseIT.database(r); }
	@Value("${local.server.port}") int port;
	@Autowired JsonMapper json;
	@Test void customCapReachesTheRealSolver() throws Exception {
		var response = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(
			java.net.URI.create("http://127.0.0.1:" + port + "/api/schedules/generate"))
			.timeout(java.time.Duration.ofSeconds(20)).header("Content-Type", "application/json")
			.POST(java.net.http.HttpRequest.BodyPublishers.ofString("""
			{"term":"2027-SPRING","preferences":{"hardConstraints":{"requiredCourseIds":["CSE-007"],"minCredits":4,"maxCredits":4}}}
			""")).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		assertThat(json.readTree(response.body()).get("searchLimitReached").asBoolean()).isTrue();
	}
}
