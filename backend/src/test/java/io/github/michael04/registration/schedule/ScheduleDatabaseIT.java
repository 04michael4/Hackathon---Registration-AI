package io.github.michael04.registration.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.zaxxer.hikari.HikariDataSource;
import io.github.michael04.registration.course.CourseCatalog;
import io.github.michael04.registration.course.SectionCatalog;
import io.github.michael04.registration.course.persistence.JdbcCourseCatalog;
import io.github.michael04.registration.course.persistence.JdbcSectionCatalog;
import io.github.michael04.registration.engine.BacktrackingScheduleSolver;
import io.github.michael04.registration.engine.Constraints;
import io.github.michael04.registration.engine.CourseOption;
import io.github.michael04.registration.engine.Meeting;
import io.github.michael04.registration.engine.ScheduleProblem;
import io.github.michael04.registration.engine.SectionOption;
import io.github.michael04.registration.engine.SoftWeights;
import io.github.michael04.registration.schedule.integration.NativeSchedulingEngine;
import io.github.michael04.registration.schedule.integration.SchedulingEngine;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Actual HTTP -> unchanged authoritative Hikari/JDBC catalogs/views -> mapper -> real native solver. No mocks. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = {"app.catalog.source=database", "app.ai.provider=none", "server.address=127.0.0.1"})
class ScheduleDatabaseIT {
	static final AuthoritativeCatalogFixture DB = new AuthoritativeCatalogFixture(true);
	@DynamicPropertySource static void database(DynamicPropertyRegistry r) {
		r.add("app.catalog.database.url", () -> DB.url);
		r.add("app.catalog.database.username", () -> DB.username);
		r.add("app.catalog.database.password", () -> DB.password);
	}
	@Value("${local.server.port}") int port;
	@Autowired JsonMapper json;
	@Autowired CourseCatalog courses;
	@Autowired SectionCatalog sections;
	@Autowired ApplicationContext context;
	final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	@BeforeEach void resetTestOnlyAvailability() throws Exception {
		try (var c = DB.connect(); var s = c.createStatement()) {
			s.executeUpdate("UPDATE test_fixture.section_status SET status = CASE WHEN crn='12638' THEN 'CLOSED' ELSE 'OPEN' END");
		}
	}
	String request(String required, int min, int max, String soft, boolean closed) {
		return """
			{"term":"2027-SPRING","maxResults":10,"includeClosedSections":%s,"preferences":{
			"hardConstraints":{"requiredCourseIds":%s,"minCredits":%d,"maxCredits":%d},"softPreferences":%s}}
			""".formatted(closed, required, min, max, soft);
	}
	JsonNode send(String body, int expected) throws Exception {
		var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/schedules/generate"))
			.timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json").header("Origin", "http://localhost:3000")
			.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
		assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:3000");
		if (expected >= 400) {
			assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
			assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
			assertThat(response.body()).doesNotContain("SQLException", "jdbc:postgresql", "StackTrace", "local-fake-key");
		}
		return json.readTree(response.body());
	}
	JsonNode cse(boolean closed) throws Exception { return send(request("[\"CSE-007\"]", 4, 4, "{}", closed), 200); }
	List<String> crns(JsonNode schedule) {
		return schedule.get("sections").valueStream().map(s -> s.get("crn").asString()).toList();
	}
	@Test void onlyAuthoritativeProductionBeansAreActive() {
		assertThat(courses).isInstanceOf(JdbcCourseCatalog.class);
		assertThat(sections).isInstanceOf(JdbcSectionCatalog.class);
		assertThat(context.getBean(DataSource.class)).isInstanceOf(HikariDataSource.class);
		assertThat(context.getBeansOfType(CourseCatalog.class)).hasSize(1);
		assertThat(context.getBeansOfType(SectionCatalog.class)).hasSize(1);
		assertThat(context.getBeansOfType(SchedulingEngine.class)).hasSize(1);
		assertThat(context.getBean(SchedulingEngine.class)).isInstanceOf(NativeSchedulingEngine.class);
	}
	@Test void realViewsPreserveSectionsLinksAndMultipleMeetingBlocks() throws Exception {
		var catalog = sections.sectionsFor("2027-SPRING", List.of("CSE-007"));
		assertThat(catalog).hasSize(3);
		assertThat(catalog.stream().filter(s -> s.crn().equals("12275")).findFirst().orElseThrow().lectureLink()).isNull();
		assertThat(catalog.stream().filter(s -> s.crn().equals("12275")).findFirst().orElseThrow().meetings()).hasSize(2);
		assertThat(catalog.stream().filter(s -> s.componentType().equals("LAB"))).allSatisfy(s -> {
			assertThat(s.lectureLink()).isEqualTo("12275"); assertThat(s.credits()).isZero();
		});
		var result = cse(false);
		assertThat(result.get("schedules").size()).isEqualTo(1);
		assertThat(crns(result.get("schedules").get(0))).containsExactly("12275", "13132");
	}
	@Test void closedLabsAreOnlyEligibleWhenIncluded() throws Exception {
		assertThat(cse(true).get("schedules").size()).isEqualTo(2);
	}
	@Test void closedLectureNeverLeavesAnOrphanOpenLab() throws Exception {
		try (var c = DB.connect(); var s = c.createStatement()) { s.executeUpdate("UPDATE test_fixture.section_status SET status='CLOSED' WHERE crn='12275'"); }
		assertThat(cse(false).get("schedules").isEmpty()).isTrue();
		assertThat(cse(true).get("schedules").size()).isEqualTo(2);
	}
	@Test void aLectureWithAllLabsClosedIsNeverScheduledAlone() throws Exception {
		try (var c = DB.connect(); var s = c.createStatement()) { s.executeUpdate("UPDATE test_fixture.section_status SET status='CLOSED' WHERE crn IN ('13132','12638')"); }
		assertThat(cse(false).get("schedules").isEmpty()).isTrue();
	}
	@Test void multipleRequiredCoursesNeverChooseConflictingLectures() throws Exception {
		var result = send(request("[\"CSE-007\",\"MATH-021\"]", 8, 8, "{}", false), 200);
		assertThat(result.get("schedules").size()).isEqualTo(1);
		assertThat(crns(result.get("schedules").get(0))).containsExactly("12275", "13132", "40101");
		assertThat(result.get("schedules").get(0).get("totalCredits").asInt()).isEqualTo(8);
	}
	@Test void duplicatesAndRequiredOptionalOverlapAreNormalizedAndTbaHasNoFakeMeeting() throws Exception {
		var result = send(request("[\"CSE-007\",\"CSE-007\"]", 7, 7,
			"{\"optionalCourseIds\":{\"value\":[\"CSE-007\",\"ENGL-001\",\"ENGL-001\"],\"weight\":3}}", false), 200);
		var schedule = result.get("schedules").get(0);
		assertThat(crns(schedule)).containsExactly("12275", "13132", "40200");
		assertThat(schedule.get("sections").get(2).get("meetings").isEmpty()).isTrue();
		assertThat(result.get("electiveAdvice").size()).isEqualTo(1);
	}
	@ParameterizedTest @ValueSource(ints = {18, 19, 21})
	void overloadUsesActualCreditsAndAsyncSections(int credits) throws Exception {
		var result = send(request("[\"OVR-0" + credits + "\"]", credits, credits, "{}", false), 200).get("schedules").get(0);
		assertThat(result.get("totalCredits").asInt()).isEqualTo(credits);
		assertThat(result.get("overload").asBoolean()).isEqualTo(credits > 18);
		assertThat(result.get("sections").get(0).get("meetings").isEmpty()).isTrue();
	}
	@Test void infeasibleRequestsAre200WithNoFabricatedSchedule() throws Exception {
		String blocked = request("[\"CSE-007\"]", 4, 4, "{}", false).replace("\"minCredits\":4",
			"\"blockedTimes\":[{\"days\":[\"MONDAY\"],\"start\":\"00:00\",\"end\":\"23:59\"}],\"minCredits\":4");
		var result = send(blocked, 200);
		assertThat(result.get("schedules").isEmpty()).isTrue();
		assertThat(result.get("diagnostics").isEmpty()).isFalse();
		assertThat(send(request("[\"CSE-007\"]", 5, 5, "{}", false), 200).get("schedules").isEmpty()).isTrue();
	}
	@Test void exactlyTenOptionalsReachTheRealSolverButElevenEntriesReturn400() throws Exception {
		var optional = java.util.stream.IntStream.range(100, 110).mapToObj(i -> "OPT-" + i).toList();
		String soft = json.writeValueAsString(Map.of("optionalCourseIds", Map.of("value", optional, "weight", 0)));
		assertThat(send(request("[\"CSE-007\"]", 4, 4, soft, false), 200).get("schedules").isEmpty()).isFalse();
		String eleven = json.writeValueAsString(Map.of("optionalCourseIds", Map.of("value", java.util.Collections.nCopies(11, "CSE-007"), "weight", 0)));
		send(request("[\"CSE-007\"]", 4, 4, eleven, false), 400);
	}
	@Test void actualNativeScoresBreakdownsCrnsAndRankAreCopiedUnchanged() throws Exception {
		String soft = "{\"earliestStart\":{\"value\":\"10:00\",\"weight\":3},\"daysOff\":{\"value\":[\"FRIDAY\"],\"weight\":4}}";
		var result = send(request("[\"CSE-007\"]", 4, 4, soft, true), 200);
		var nativeSections = sections.sectionsFor("2027-SPRING", List.of("CSE-007")).stream().map(s ->
			new SectionOption(s.crn(), s.courseId(), s.componentType(), s.lectureLink(), s.credits(), s.meetings().stream()
				.map(m -> new Meeting(EnumSet.copyOf(m.days()), m.start(), m.end())).toList())).toList();
		var expected = new BacktrackingScheduleSolver(200000).solve(new ScheduleProblem(
			List.of(new CourseOption("CSE-007", true, nativeSections)), new Constraints(4, 4, List.of()),
			new SoftWeights(LocalTime.of(10, 0), 3, null, 0, EnumSet.of(DayOfWeek.FRIDAY), 4, 0, 0), 10));
		assertThat(result.get("schedules").size()).isEqualTo(expected.schedules().size());
		for (int i = 0; i < expected.schedules().size(); i++) {
			var actual = result.get("schedules").get(i); var nativeSchedule = expected.schedules().get(i);
			assertThat(actual.get("rank").asInt()).isEqualTo(i + 1);
			assertThat(actual.get("score").asInt()).isEqualTo(nativeSchedule.score());
			assertThat(actual.get("scoreBreakdown")).isEqualTo(json.valueToTree(nativeSchedule.breakdown()));
			assertThat(crns(actual)).isEqualTo(nativeSchedule.sections().stream().map(SectionOption::crn).toList());
		}
	}
	@Test void identicalRequestsAreDeterministic() throws Exception { assertThat(cse(true)).isEqualTo(cse(true)); }
	@Test void unknownTermsAndCoursesReturn422() throws Exception {
		send(request("[\"CSE-999\"]", 4, 4, "{}", false), 422);
		send(request("[\"CSE-007\"]", 4, 4, "{}", false).replace("2027-SPRING", "202710"), 422);
	}
	@Test void prerequisiteExpressionsRemainInTheAuthoritativeDatabase() throws Exception {
		try (var c = DB.connect(); var s = c.createStatement(); var rs = s.executeQuery("SELECT requirement_expression->>'code' FROM cse007_snapshot.course_requirement WHERE requirement_id=md5('math-prereq')::uuid")) {
			assertThat(rs.next()).isTrue(); assertThat(rs.getString(1)).isEqualTo("CSE-007");
		}
	}
}
