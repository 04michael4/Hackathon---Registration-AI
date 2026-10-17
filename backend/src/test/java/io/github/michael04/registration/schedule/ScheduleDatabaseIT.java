package io.github.michael04.registration.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.github.michael04.registration.course.CatalogSection;
import io.github.michael04.registration.course.FixtureDatabase;
import io.github.michael04.registration.course.SectionCatalog;
import io.github.michael04.registration.engine.BacktrackingScheduleSolver;
import io.github.michael04.registration.engine.CourseOption;
import io.github.michael04.registration.engine.Constraints;
import io.github.michael04.registration.engine.Meeting;
import io.github.michael04.registration.engine.ScheduleProblem;
import io.github.michael04.registration.engine.SectionOption;
import io.github.michael04.registration.engine.SoftWeights;
import io.github.michael04.registration.schedule.integration.NativeSchedulingEngine;
import io.github.michael04.registration.schedule.integration.SchedulingEngine;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Primary end-to-end tests: actual TCP HTTP, PostgreSQL catalog, mapper and native solver. No mocks. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = {"app.catalog.source=database", "app.ai.provider=none"})
class ScheduleDatabaseIT {
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
	@Autowired SectionCatalog catalog;
	@Autowired SchedulingEngine engine;
	@Autowired SchedulingProperties properties;
	@Autowired JdbcTemplate jdbc;
	final HttpClient client = HttpClient.newHttpClient();

	private String request(String required, int min, int max, String soft, String extra) {
		return """
			{"term":"TEST-2027", "maxResults":10,%s "preferences":{"hardConstraints":{
			"requiredCourseIds":%s,"minCredits":%d,"maxCredits":%d,"blockedTimes":[]},"softPreferences":%s}}
			""".formatted(extra, required, min, max, soft);
	}
	private JsonNode send(String body, int status) throws Exception {
		var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/schedules/generate"))
			.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
		return json.readTree(response.body());
	}
	private JsonNode generate(String required, int min, int max) throws Exception { return send(request(required, min, max, "{}", ""), 200); }
	private List<String> crns(JsonNode schedule) { return schedule.get("sections").valueStream().map(s -> s.get("crn").asString()).toList(); }

	@Test void actualSolverSelectsACompatibleLectureLabBundle() throws Exception {
		assertThat(engine).isInstanceOf(NativeSchedulingEngine.class);
		assertThat(properties.nodeCap()).isEqualTo(200000);
		var response = generate("[\"CSE-007\"]", 4, 4);
		assertThat(response.get("schedules").size()).isEqualTo(1);
		assertThat(crns(response.get("schedules").get(0))).containsExactly("12275", "13132");
		assertThat(response.get("schedules").get(0).get("totalCredits").asInt()).isEqualTo(4);
		assertThat(response.get("searchLimitReached").asBoolean()).isFalse();
	}
	@Test void includesClosedLabOnlyWhenRequestedAndNeverChoosesAnInternallyConflictingLab() throws Exception {
		var response = send(request("[\"CSE-007\"]", 4, 4, "{}", "\"includeClosedSections\":true,"), 200);
		assertThat(response.get("schedules").size()).isEqualTo(2);
		assertThat(response.get("schedules").valueStream().flatMap(s -> crns(s).stream()).toList())
			.contains("13132", "12638").doesNotContain("49999");
	}
	@Test void aClosedLectureIsExcludedWithItsOpenChildren() throws Exception {
		try {
			jdbc.update("UPDATE section SET search_status_text = 'CLOSED' WHERE crn = '12275'");
			assertThat(generate("[\"CSE-007\"]", 4, 4).get("schedules").size()).isZero();
			assertThat(send(request("[\"CSE-007\"]", 4, 4, "{}", "\"includeClosedSections\":true,"), 200)
				.get("schedules").size()).isEqualTo(2);
		}
		finally { jdbc.update("UPDATE section SET search_status_text = 'OPEN' WHERE crn = '12275'"); }
	}
	@Test void allClosedLabsNeverProduceALectureAlone() throws Exception {
		try {
			jdbc.update("UPDATE section SET search_status_text = 'CLOSED' WHERE component_type = 'LAB'");
			assertThat(generate("[\"CSE-007\"]", 4, 4).get("schedules").size()).isZero();
		}
		finally { jdbc.update("UPDATE section SET search_status_text = CASE WHEN crn = '12638' THEN 'CLOSED' ELSE 'OPEN' END WHERE component_type = 'LAB'"); }
	}
	@Test void realSolverHonorsConflictsRequiredCoursesAndActualCreditTotals() throws Exception {
		var response = generate("[\"CSE-007\",\"MATH-021\"]", 8, 8);
		assertThat(response.get("schedules").size()).isEqualTo(1);
		var result = response.get("schedules").get(0);
		assertThat(crns(result)).containsExactly("12275", "13132", "40101").doesNotContain("40100");
		assertThat(result.get("totalCredits").asInt()).isEqualTo(8);
		assertNoConflicts(result);
	}
	@Test void blockedTimesAndImpossibleCreditsAreNormalEmptyResults() throws Exception {
		String blocked = request("[\"CSE-007\"]", 4, 4, "{}", "").replace("\"blockedTimes\":[]",
			"\"blockedTimes\":[{\"days\":[\"MONDAY\"],\"start\":\"09:00\",\"end\":\"11:00\"}]");
		var response = send(blocked, 200);
		assertThat(response.get("schedules").size()).isZero();
		assertThat(response.get("diagnostics").size()).isPositive();
		assertThat(generate("[\"CSE-007\"]", 1, 3).get("schedules").size()).isZero();
	}
	@Test void normalizesRequiredOptionalDuplicatesAndKeepsTbaMeetingsEmpty() throws Exception {
		var response = send(request("[\"CSE-007\",\"CSE-007\"]", 7, 7,
			"{\"optionalCourseIds\":{\"value\":[\"CSE-007\",\"ENGL-001\",\"ENGL-001\"],\"weight\":5}}", ""), 200);
		assertThat(response.get("schedules").size()).isEqualTo(1);
		var result = response.get("schedules").get(0);
		assertThat(crns(result)).containsExactly("12275", "13132", "40200");
		assertThat(result.get("sections").get(2).get("meetings").size()).isZero();
		assertNoConflicts(result);
	}
	@ParameterizedTest @ValueSource(ints = {18, 19, 21})
	void overloadUsesActualGeneratedCreditsAndAsynchronousSections(int credits) throws Exception {
		var result = generate("[\"OVR-0" + credits + "\"]", credits, credits).get("schedules").get(0);
		assertThat(result.get("totalCredits").asInt()).isEqualTo(credits);
		assertThat(result.get("overload").asBoolean()).isEqualTo(credits > 18);
		assertThat(result.get("sections").get(0).get("meetings").size()).isZero();
	}
	@Test void tenOptionalsAreAcceptedButElevenOriginalEntriesAreRejected() throws Exception {
		String ids = String.join(",", java.util.stream.IntStream.range(100, 110).mapToObj(i -> "\"OPT-" + i + "\"").toList());
		String soft = "{\"optionalCourseIds\":{\"value\":[" + ids + "],\"weight\":0}}";
		assertThat(send(request("[\"CSE-007\"]", 4, 4, soft, ""), 200).get("schedules").size()).isEqualTo(1);
		send(request("[\"CSE-007\"]", 4, 4, soft.replace(ids, ids + ",\"CSE-007\""), ""), 400);
	}
	@Test void copiesEveryScoreBreakdownAndRankFromTheNativeSolverUnchanged() throws Exception {
		var soft = new SoftWeights(LocalTime.of(10, 0), 4, LocalTime.of(12, 0), 3,
			EnumSet.of(DayOfWeek.FRIDAY), 2, 2, 5);
		var courses = new ArrayList<CourseOption>();
		for (String id : List.of("CSE-007", "ENGL-001")) {
			var raw = catalog.sectionsFor("TEST-2027", List.of(id));
			var closed = raw.stream().filter(s -> s.status() == CatalogSection.Status.CLOSED).map(CatalogSection::crn).toList();
			courses.add(new CourseOption(id, id.equals("CSE-007"), raw.stream().map(s -> new SectionOption(
				s.crn(), s.courseId(), s.componentType(), s.lectureLink(), s.credits(), s.meetings().stream()
					.map(m -> new Meeting(EnumSet.copyOf(m.days()), m.start(), m.end())).toList())).toList())
				.excluding(s -> closed.contains(s.crn())));
		}
		var nativeResult = new BacktrackingScheduleSolver(200000).solve(new ScheduleProblem(courses, new Constraints(4, 8, List.of()), soft, 10));
		String preferences = """
			{"optionalCourseIds":{"value":["ENGL-001"],"weight":5},"earliestStart":{"value":"10:00","weight":4},
			"latestEnd":{"value":"12:00","weight":3},"daysOff":{"value":["FRIDAY"],"weight":2},"minimizeGaps":{"weight":2}}
			""";
		var response = send(request("[\"CSE-007\"]", 4, 8, preferences, ""), 200);
		assertThat(response.get("schedules").size()).isEqualTo(nativeResult.schedules().size());
		for (int i = 0; i < nativeResult.schedules().size(); i++) {
			var expected = nativeResult.schedules().get(i);
			var actual = response.get("schedules").get(i);
			assertThat(actual.get("rank").asInt()).isEqualTo(i + 1);
			assertThat(actual.get("score").asInt()).isEqualTo(expected.score());
			assertThat(crns(actual)).containsExactlyElementsOf(expected.sections().stream().map(SectionOption::crn).toList());
			assertThat(actual.get("scoreBreakdown").size()).isEqualTo(expected.breakdown().size());
			expected.breakdown().forEach((key, points) -> assertThat(actual.get("scoreBreakdown").get(key).asInt()).isEqualTo(points));
		}
	}
	@Test void deterministicRequestsReturnIdenticalResults() throws Exception {
		String body = request("[\"CSE-007\",\"MATH-021\"]", 8, 11,
			"{\"optionalCourseIds\":{\"value\":[\"ENGL-001\"],\"weight\":3}}", "");
		assertThat(send(body, 200)).isEqualTo(send(body, 200));
	}
	@Test void malformedCatalogLinksAndUnsupportedCreditsReturnSanitized500() throws Exception {
		try {
			jdbc.update("INSERT INTO section_link SELECT section_id, section_id, 'LAB' FROM section WHERE crn = '12275'");
			assertThat(send(request("[\"CSE-007\"]", 4, 4, "{}", ""), 500).toString()).doesNotContain("SELECT", "Exception", "password");
		}
		finally { jdbc.update("DELETE FROM section_link WHERE primary_section_id = compatible_section_id"); }
		send(request("[\"CSE-099\"]", 1, 3, "{}", ""), 500);
	}

	private void assertNoConflicts(JsonNode result) {
		var sections = result.get("sections");
		for (int i = 0; i < sections.size(); i++) for (int j = i + 1; j < sections.size(); j++) {
			for (var a : sections.get(i).get("meetings")) for (var b : sections.get(j).get("meetings")) {
				boolean sharedDay = a.get("days").valueStream().anyMatch(day -> b.get("days").valueStream().anyMatch(day::equals));
				assertThat(sharedDay && LocalTime.parse(a.get("start").asString()).isBefore(LocalTime.parse(b.get("end").asString()))
					&& LocalTime.parse(b.get("start").asString()).isBefore(LocalTime.parse(a.get("end").asString()))).isFalse();
			}
		}
	}
}
