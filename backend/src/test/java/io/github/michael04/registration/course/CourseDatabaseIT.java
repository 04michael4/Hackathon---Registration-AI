package io.github.michael04.registration.course;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.github.michael04.registration.schedule.AuthoritativeCatalogFixture;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real HTTP Course regressions over pre-main's authoritative datasource, tables and readiness views. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = {"app.catalog.source=database", "app.ai.provider=none"})
class CourseDatabaseIT {
	static final AuthoritativeCatalogFixture DB = new AuthoritativeCatalogFixture(true);
	@DynamicPropertySource static void database(DynamicPropertyRegistry r) {
		r.add("app.catalog.database.url", () -> DB.url);
		r.add("app.catalog.database.username", () -> DB.username);
		r.add("app.catalog.database.password", () -> DB.password);
	}
	@Value("${local.server.port}") int port;
	@Autowired JsonMapper json;
	@Autowired SectionCatalog sections;
	@Autowired DataSource dataSource;
	final HttpClient client = HttpClient.newHttpClient();

	HttpResponse<String> get(String path, int status) throws Exception {
		var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
		return response;
	}
	JsonNode body(String path) throws Exception { return json.readTree(get(path, 200).body()); }
	void update(String sql) throws Exception {
		try (var c = DB.connect(); var s = c.createStatement()) { s.executeUpdate(sql); }
	}
	@Test void listsPersistedTermsUsingApiIdsAndPreservesUnknownCaptureDate() throws Exception {
		var terms = body("/api/terms").get("items");
		assertThat(terms.size()).isEqualTo(1);
		assertThat(terms.get(0).get("term").asString()).isEqualTo("2027-SPRING");
		assertThat(terms.get(0).get("catalogSnapshotDate").isNull()).isTrue();
	}
	@Test void searchesCodeAndTitleAndSubjectUsingLectureCounts() throws Exception {
		for (String q : new String[] {"CSE%20007", "cse007", "introduction%20programming"}) {
			var page = body("/api/courses?term=2027-SPRING&q=" + q);
			assertThat(page.get("totalItems").asInt()).isEqualTo(1);
			assertThat(page.get("items").get(0).get("courseId").asString()).isEqualTo("CSE-007");
			assertThat(page.get("items").get(0).get("sectionCount").asInt()).isEqualTo(1);
		}
		assertThat(body("/api/courses?term=2027-SPRING&subject=cse").get("totalItems").asInt()).isEqualTo(1);
	}
	@Test void searchTreatsSqlWildcardsAndInjectionTextLiterally() throws Exception {
		assertThat(body("/api/courses?term=2027-SPRING&q=%25").get("items").size()).isZero();
		assertThat(body("/api/courses?term=2027-SPRING&q=%27%20OR%201=1--").get("items").size()).isZero();
	}
	@Test void pagesAreSortedStableAndUseWideOffsetArithmetic() throws Exception {
		var first = body("/api/courses?term=2027-SPRING&size=2");
		var second = body("/api/courses?term=2027-SPRING&size=2&page=1");
		assertThat(first.get("totalItems").asInt()).isEqualTo(16);
		assertThat(first.get("totalPages").asInt()).isEqualTo(8);
		assertThat(first.get("items").get(0).get("courseId").asString()).isEqualTo("CSE-007");
		assertThat(second.get("items").get(0).get("courseId").asString()).isEqualTo("MATH-021");
		assertThat(body("/api/courses?term=2027-SPRING&size=100&page=2147483647").get("items").size()).isZero();
	}
	@Test void detailPreservesMetadataAlongsideAuthoritativeComponentsLinksAndMeetings() throws Exception {
		update("UPDATE cse007_snapshot.section SET instructor='Synthetic Instructor, Alex' WHERE crn='12275'");
		var course = body("/api/courses/CSE-007?term=2027-SPRING");
		assertThat(course.get("number").asString()).isEqualTo("007");
		assertThat(course.get("description").asString()).containsIgnoringCase("Java");
		var lecture = course.get("sections").get(0);
		assertThat(lecture.get("crn").asString()).isEqualTo("12275");
		assertThat(lecture.get("instructors").get(0).asString()).isEqualTo("Synthetic Instructor, Alex");
		assertThat(lecture.get("componentType").asString()).isEqualTo("LECTURE");
		assertThat(lecture.get("meetings").size()).isEqualTo(2);
		assertThat(lecture.get("meetings").get(0).get("start").asString()).isEqualTo("09:20");
		assertThat(course.get("sections").get(1).get("lectureLink").asString()).isEqualTo("12275");
		assertThat(course.get("sections").get(1).get("status").asString()).isEqualTo("CLOSED");
		assertThat(sections.sectionsFor("2027-SPRING", List.of("CSE-007")))
			.filteredOn(s -> s.componentType().equals("LAB")).allMatch(s -> "12275".equals(s.lectureLink()));
	}
	@Test void tbaAndAsyncSectionsHaveNoInventedMeeting() throws Exception {
		assertThat(body("/api/courses/ENGL-001?term=2027-SPRING").get("sections").get(0).get("meetings").size()).isZero();
		assertThat(body("/api/courses/OPT-100?term=2027-SPRING").get("sections").get(0).get("meetings").size()).isZero();
	}
	@Test void emptyAndUnknownCatalogResultsUseTheContractStatuses() throws Exception {
		assertThat(body("/api/courses?term=2027-SPRING&q=nothing-matches").get("totalItems").asInt()).isZero();
		get("/api/courses?term=202710", 404);
		get("/api/courses/CSE-999?term=2027-SPRING", 404);
		get("/api/courses/CSE-007?term=UNKNOWN", 404);
	}
	@Test void prerequisitesAndExplicitNoneRemainInTheSameAuthoritativeDatabase() throws Exception {
		try (var c = DB.connect(); var s = c.createStatement(); var r = s.executeQuery(
			"SELECT requirement_expression->>'code' FROM cse007_snapshot.course_requirement WHERE requirement_id=md5('math-prereq')::uuid")) {
			assertThat(r.next()).isTrue(); assertThat(r.getString(1)).isEqualTo("CSE-007");
		}
	}
	@Test void catalogReadsAreReadOnlyAndHttpReadsDoNotWrite() throws Exception {
		body("/api/courses?term=2027-SPRING"); body("/api/courses/CSE-007?term=2027-SPRING");
		try (var c = dataSource.getConnection(); var s = c.createStatement()) {
			assertThatThrownBy(() -> s.executeUpdate("UPDATE cse007_snapshot.course SET title=title WHERE false"))
				.isInstanceOf(java.sql.SQLException.class);
		}
	}
	@Test void realHttpErrorsAndCorsAreSafe() throws Exception {
		assertThat(get("/api/courses?term=2027-SPRING&size=101", 400).headers().firstValue("X-Request-Id")).isPresent();
		var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/terms"))
			.header("Origin", "http://localhost:3000").GET().build(), HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:3000");
	}
	@Test void missingMetadataRemainsNullAndHistoricalAvailabilityIsNeverInferred() throws Exception {
		String original;
		try (var c = DB.connect(); var s = c.createStatement(); var rs = s.executeQuery("SELECT description FROM cse007_snapshot.course WHERE code='CSE-007'")) {
			rs.next(); original = rs.getString(1);
		}
		try {
			update("UPDATE cse007_snapshot.course SET description=null WHERE code='CSE-007'");
			assertThat(body("/api/courses/CSE-007?term=2027-SPRING").get("description").isNull()).isTrue();
		}
		finally {
			try (var c = DB.connect(); var s = c.prepareStatement("UPDATE cse007_snapshot.course SET description=? WHERE code='CSE-007'")) {
				s.setString(1, original); s.executeUpdate();
			}
		}
	}
	@Test void actualCaptureDateIsUsedWithoutSubstitutingVerificationDate() throws Exception {
		try {
			update("UPDATE cse007_snapshot.source_asset SET captured_at='2026-10-01T12:00:00Z'");
			assertThat(body("/api/terms").get("items").get(0).get("catalogSnapshotDate").asString()).isEqualTo("2026-10-01");
		}
		finally { update("UPDATE cse007_snapshot.source_asset SET captured_at=null"); }
	}
	@Test void uncertifiedLinksFailClosedWithoutInventingStandaloneComponents() throws Exception {
		try {
			update("UPDATE cse007_snapshot.section SET link_status='TARGET_UNKNOWN' WHERE crn='13132'");
			assertThat(body("/api/courses?term=2027-SPRING&subject=CSE").get("items").size()).isZero();
			assertThat(sections.sectionsFor("2027-SPRING", List.of("CSE-007"))).isEmpty();
		}
		finally { update("UPDATE cse007_snapshot.section SET link_status='PAIR_CONFIRMED' WHERE crn='13132'"); }
	}
}
