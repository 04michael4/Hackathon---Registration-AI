package io.github.michael04.registration.course;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.github.michael04.registration.course.persistence.CatalogRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Run explicitly with -Dtest=SourceCatalogVerification and SOURCE_CATALOG_SQL pointing to Data's unmodified SQL bundle. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = {"app.catalog.source=database", "app.ai.provider=none"})
class SourceCatalogVerification {
	static final FixtureDatabase DB = new FixtureDatabase("course_api_" + UUID.randomUUID().toString().replace("-", ""));
	static {
		String sql = System.getenv("SOURCE_CATALOG_SQL");
		if (sql == null || sql.isBlank()) throw new IllegalStateException("SOURCE_CATALOG_SQL is required for source-schema verification");
		DB.prepareSource(Path.of(sql));
	}
	@DynamicPropertySource static void database(DynamicPropertyRegistry r) {
		r.add("app.catalog.database.url", () -> DB.url);
		r.add("app.catalog.database.username", () -> DB.user);
		r.add("app.catalog.database.password", () -> DB.password);
		r.add("app.catalog.database.schema", () -> DB.schema);
	}
	@AfterAll static void clean() throws Exception { DB.clean(); }
	@Value("${local.server.port}") int port;
	@Autowired JsonMapper json;
	@Autowired CatalogRepository repository;
	final HttpClient client = HttpClient.newHttpClient();
	JsonNode get(String path) throws Exception {
		var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		return json.readTree(response.body());
	}
	JsonNode generate(String extraHard, String soft) throws Exception {
		String body = """
			{"term":"202710","maxResults":10,"preferences":{"hardConstraints":{"requiredCourseIds":["CSE-007"],
			"minCredits":4,"maxCredits":4%s},"softPreferences":%s}}
			""".formatted(extraHard, soft);
		var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/schedules/generate"))
			.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		return json.readTree(response.body());
	}
	@Test void originalMigrationsAndSeedProduceTheDocumentedCourseAndAllSections() throws Exception {
		var page = get("/api/courses?term=202710&q=cse007");
		assertThat(page.get("totalItems").asInt()).isEqualTo(1);
		assertThat(page.get("items").get(0).get("sectionCount").asInt()).isEqualTo(3);
		var course = get("/api/courses/CSE-007?term=202710");
		assertThat(course.get("title").asString()).isEqualTo("Introduction to Programming");
		assertThat(course.get("description").asString()).containsIgnoringCase("Java");
		assertThat(course.get("sections").valueStream().map(s -> s.get("crn").asString()).toList())
			.containsExactly("12275", "12638", "13132");
		assertThat(course.get("sections").valueStream().map(s -> s.get("status").asString()).toList())
			.containsOnly("UNKNOWN");
		assertThat(get("/api/terms").get("items").get(0).get("catalogSnapshotDate").isNull()).isTrue();
		assertThat(repository.requirements("CSE-007")).hasSize(3).allMatch(r -> r.expressionJson() == null
			&& r.assessmentStatus().equals("USER_CONFIRMED_NONE"));
	}
	@Test void originalLectureLabPairsReachTheActualSolver() throws Exception {
		var response = generate("", "{}");
		assertThat(response.get("schedules").size()).isEqualTo(2);
		for (var schedule : response.get("schedules")) {
			assertThat(schedule.get("totalCredits").asInt()).isEqualTo(4);
			assertThat(schedule.get("sections").size()).isEqualTo(2);
			assertThat(schedule.get("sections").get(0).get("crn").asString()).isEqualTo("12275");
			assertThat(schedule.get("sections").get(1).get("crn").asString()).isIn("13132", "12638");
			assertThat(schedule.get("score").asInt()).isEqualTo(100);
		}
	}
	@Test void earliestStartRanksRatherThanDropsTheHistoricalMorningLecture() throws Exception {
		var response = generate("", "{\"earliestStart\":{\"value\":\"10:00\",\"weight\":4}}");
		assertThat(response.get("schedules").size()).isEqualTo(2);
		assertThat(response.get("schedules").get(0).get("sections").get(1).get("crn").asString()).isEqualTo("12638");
		assertThat(response.get("schedules").get(0).get("score").asInt()).isGreaterThan(response.get("schedules").get(1).get("score").asInt());
		assertThat(generate(",\"blockedTimes\":[{\"days\":[\"MONDAY\"],\"start\":\"00:00\",\"end\":\"10:00\"}]", "{}")
			.get("schedules").size()).isZero();
	}
}
