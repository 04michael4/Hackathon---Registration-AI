package io.github.michael04.registration.course;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.michael04.registration.course.persistence.CatalogRepository;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Actual HTTP -> controller -> service -> JDBC repository -> isolated PostgreSQL. Run explicitly with -Dtest=CourseDatabaseIT. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = {"app.catalog.source=database", "app.ai.provider=none"})
class CourseDatabaseIT {
	static final FixtureDatabase DB = new FixtureDatabase("course_api_" + UUID.randomUUID().toString().replace("-", ""));
	static { DB.prepare(); }
	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		registry.add("app.catalog.database.url", () -> DB.url);
		registry.add("app.catalog.database.username", () -> DB.user);
		registry.add("app.catalog.database.password", () -> DB.password);
		registry.add("app.catalog.database.schema", () -> DB.schema);
	}
	@Value("${local.server.port}") int port;
	@Autowired JsonMapper json;
	@Autowired CatalogRepository repository;
	@Autowired JdbcTemplate jdbc;
	@Autowired DataSourceTransactionManager transactions;
	final HttpClient client = HttpClient.newHttpClient();

	@AfterAll static void clean() throws Exception { DB.clean(); }

	HttpResponse<String> get(String path, int status) throws Exception {
		var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
		return response;
	}
	JsonNode body(String path) throws Exception { return json.readTree(get(path, 200).body()); }

	@Test void listsPersistedTermsWithActualSnapshotDate() throws Exception {
		var terms = body("/api/terms").get("items");
		assertThat(terms.size()).isEqualTo(2);
		assertThat(terms.get(0).get("term").asString()).isEqualTo("TEST-2027");
		assertThat(terms.get(0).get("catalogSnapshotDate").asString()).isEqualTo("2026-10-01");
	}
	@Test void searchesCodeAndTitleAndSubject() throws Exception {
		for (String q : new String[] {"CSE%20007", "cse007", "introduction%20programming"}) {
			var page = body("/api/courses?term=TEST-2027&q=" + q);
			assertThat(page.get("totalItems").asInt()).isEqualTo(1);
			assertThat(page.get("items").get(0).get("courseId").asString()).isEqualTo("CSE-007");
			assertThat(page.get("items").get(0).get("sectionCount").asInt()).isEqualTo(4);
		}
		assertThat(body("/api/courses?term=TEST-2027&subject=cse").get("totalItems").asInt()).isEqualTo(2);
	}
	@Test void searchTreatsSqlWildcardsAndInjectionTextLiterally() throws Exception {
		assertThat(body("/api/courses?term=TEST-2027&q=%25").get("items").size()).isZero();
		assertThat(body("/api/courses?term=TEST-2027&q=%27%20OR%201=1--").get("items").size()).isZero();
	}
	@Test void pagesAreSortedStableAndUseWideOffsetArithmetic() throws Exception {
		var first = body("/api/courses?term=TEST-2027&size=2");
		var second = body("/api/courses?term=TEST-2027&size=2&page=1");
		assertThat(first.get("totalItems").asInt()).isEqualTo(17);
		assertThat(first.get("totalPages").asInt()).isEqualTo(9);
		assertThat(first.get("items").get(0).get("courseId").asString()).isEqualTo("CSE-007");
		assertThat(second.get("items").get(0).get("courseId").asString()).isEqualTo("ENGL-001");
		assertThat(body("/api/courses?term=TEST-2027&size=100&page=2147483647").get("items").size()).isZero();
	}
	@Test void detailPreservesDescriptionInstructorComponentsAndMultipleMeetings() throws Exception {
		var course = body("/api/courses/CSE-007?term=TEST-2027");
		assertThat(course.get("number").asString()).isEqualTo("007");
		assertThat(course.get("description").asString()).isEqualTo("Synthetic description for CSE-007");
		var lecture = course.get("sections").get(0);
		assertThat(lecture.get("crn").asString()).isEqualTo("12275");
		assertThat(lecture.get("instructors").get(0).asString()).isEqualTo("Synthetic Instructor, Alex");
		assertThat(lecture.get("meetings").size()).isEqualTo(2);
		assertThat(lecture.get("meetings").get(0).get("start").asString()).isEqualTo("09:20");
		assertThat(course.get("sections").get(1).get("status").asString()).isEqualTo("CLOSED");
		var sections = repository.schedulingSections("TEST-2027", java.util.List.of("CSE-007"));
		assertThat(sections).filteredOn(s -> s.componentType().equals("LAB")).allMatch(s -> "12275".equals(s.lectureLink()));
		assertThat(sections).filteredOn(s -> s.crn().equals("12275")).allMatch(s -> s.lectureLink() == null);
	}
	@Test void tbaSectionsHaveNoInventedMeetingAndFractionalCreditsSurviveCourseReads() throws Exception {
		assertThat(body("/api/courses/ENGL-001?term=TEST-2027").get("sections").get(0).get("meetings").size()).isZero();
		assertThat(body("/api/courses/CSE-099?term=TEST-2027").get("credits").asDouble()).isEqualTo(1.5);
	}
	@Test void emptyAndUnknownCatalogResultsUseTheContractStatuses() throws Exception {
		assertThat(body("/api/courses?term=TEST-EMPTY").get("totalItems").asInt()).isZero();
		get("/api/courses?term=UNKNOWN", 404);
		get("/api/courses/CSE-999?term=TEST-2027", 404);
		get("/api/courses/CSE-007?term=UNKNOWN", 404);
	}
	@Test void prerequisitesAndExplicitNoneAndUnknownArePreservedInternally() {
		assertThat(repository.requirements("MATH-021")).singleElement().satisfies(rule -> {
			assertThat(rule.assessmentStatus()).isEqualTo("RULE_DEFINED");
			assertThat(json.readTree(rule.expressionJson()).get("code").asString()).isEqualTo("CSE-007");
		});
		assertThat(repository.requirements("CSE-007")).extracting(r -> r.assessmentStatus())
			.containsExactly("USER_CONFIRMED_NONE", "UNKNOWN");
	}
	@Test void catalogTransactionsAreReadOnlyAndHttpReadsDoNotWrite() throws Exception {
		long before = jdbc.queryForObject("SELECT count(*) FROM section", Long.class);
		body("/api/courses?term=TEST-2027");
		body("/api/courses/CSE-007?term=TEST-2027");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM section", Long.class)).isEqualTo(before);
		var readOnly = new TransactionTemplate(transactions);
		readOnly.setReadOnly(true);
		assertThatThrownBy(() -> readOnly.execute(status -> jdbc.update("UPDATE course SET title = title")))
			.isInstanceOf(org.springframework.dao.DataAccessException.class);
	}
	@Test void realHttpErrorsAndCorsAreSafe() throws Exception {
		var response = get("/api/courses?term=TEST-2027&size=101", 400);
		assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
		var crossOrigin = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/terms"))
			.header("Origin", "http://localhost:3000").GET().build(), HttpResponse.BodyHandlers.ofString());
		assertThat(crossOrigin.statusCode()).isEqualTo(200);
		assertThat(crossOrigin.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:3000");
	}
	@Test void historicalSeatSnippetsStayUnknownAndMissingDescriptionStaysNull() throws Exception {
		try {
			jdbc.update("UPDATE section SET search_status_text = '70 of 70 seats...' WHERE crn = '12275'");
			jdbc.update("UPDATE course SET description = null WHERE code = 'CSE-007'");
			var detail = body("/api/courses/CSE-007?term=TEST-2027");
			assertThat(detail.get("description").isNull()).isTrue();
			assertThat(detail.get("sections").get(0).get("status").asString()).isEqualTo("UNKNOWN");
		}
		finally {
			jdbc.update("UPDATE section SET search_status_text = 'OPEN' WHERE crn = '12275'");
			jdbc.update("UPDATE course SET description = 'Synthetic description for CSE-007' WHERE code = 'CSE-007'");
		}
	}
	@Test void unknownCaptureDateIsNotReplacedByTheTranscriptionVerificationDate() throws Exception {
		try {
			jdbc.update("UPDATE source_asset SET captured_at = null");
			assertThat(body("/api/terms").get("items").get(0).get("catalogSnapshotDate").isNull()).isTrue();
		}
		finally { jdbc.update("UPDATE source_asset SET captured_at = '2026-10-01T12:00:00Z'"); }
	}
	@Test void aMissingLabAssociationFailsSafelyWithoutInventingAStandaloneSection() {
		try {
			jdbc.update("DELETE FROM section_link WHERE compatible_section_id = (SELECT section_id FROM section WHERE crn = '13132')");
			assertThatThrownBy(() -> repository.schedulingSections("TEST-2027", java.util.List.of("CSE-007")))
				.isInstanceOf(IllegalStateException.class);
		}
		finally {
			jdbc.update("INSERT INTO section_link SELECT p.section_id, c.section_id, 'LAB' FROM section p, section c WHERE p.crn = '12275' AND c.crn = '13132'");
		}
	}
}
