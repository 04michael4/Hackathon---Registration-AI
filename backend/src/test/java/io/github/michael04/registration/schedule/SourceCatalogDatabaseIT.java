package io.github.michael04.registration.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import io.github.michael04.registration.course.CatalogSection;
import io.github.michael04.registration.course.SectionCatalog;
import tools.jackson.databind.json.JsonMapper;

/** Source Data SQL and views entirely unchanged: no synthetic status overlay or replacement repository. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = {"app.catalog.source=database", "app.ai.provider=none", "server.address=127.0.0.1"})
class SourceCatalogDatabaseIT {
	static final AuthoritativeCatalogFixture DB = new AuthoritativeCatalogFixture(false);
	@DynamicPropertySource static void database(DynamicPropertyRegistry r) {
		r.add("app.catalog.database.url", () -> DB.url);
		r.add("app.catalog.database.username", () -> DB.username);
		r.add("app.catalog.database.password", () -> DB.password);
	}
	@Value("${local.server.port}") int port;
	@Autowired SectionCatalog sections;
	@Autowired JsonMapper json;
	@Test void originalCatalogProducesBothRealLectureLabSchedulesOverHttp() throws Exception {
		assertThat(sections.sectionsFor("2027-SPRING", List.of("CSE-007"))).extracting(CatalogSection::status)
			.containsOnly(CatalogSection.Status.UNKNOWN);
		var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/schedules/generate"))
			.timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString("""
			{"term":"2027-SPRING","maxResults":10,"preferences":{"hardConstraints":{"requiredCourseIds":["CSE-007"],"minCredits":4,"maxCredits":4}}}
			""")).build(), HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		var result = json.readTree(response.body());
		assertThat(result.get("schedules").size()).isEqualTo(2);
		assertThat(result.get("schedules").valueStream().map(s -> s.get("sections").get(1).get("crn").asString()).toList())
			.containsExactlyInAnyOrder("12638", "13132");
		result.get("schedules").forEach(s -> {
			assertThat(s.get("totalCredits").asInt()).isEqualTo(4);
			assertThat(s.get("score").asInt()).isEqualTo(100);
			assertThat(s.get("sections").get(0).get("crn").asString()).isEqualTo("12275");
		});
	}
}
