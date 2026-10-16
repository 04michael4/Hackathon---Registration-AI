package io.github.michael04.registration.course.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import io.github.michael04.registration.course.CatalogSection;
import io.github.michael04.registration.course.CatalogSection.Meeting;
import io.github.michael04.registration.course.CatalogSection.Status;
import io.github.michael04.registration.course.CourseCatalog;
import io.github.michael04.registration.course.CourseSummary;
import io.github.michael04.registration.course.SectionCatalog;
import io.github.michael04.registration.schedule.integration.SchedulingEngine;

/**
 * OPT-IN: runs against a real PostgreSQL holding David's cse007_snapshot with the catalog_api views installed
 * (data/catalog-api/install.sql). Skipped unless {@code RUN_DB_IT=true}, so {@code ./mvnw test} never needs a database.
 *
 * <pre>
 * RUN_DB_IT=true DB_URL=jdbc:postgresql://127.0.0.1:55433/registration_cse007_local DB_USERNAME=... \
 *   PGPASSFILE=... ./mvnw test -Dtest=DatabaseCatalogIntegrationTest        (or DB_PASSWORD=...)
 * </pre>
 *
 * Read-only by construction: the pool is read-only, and the one test that tries to write only uses statements that
 * would change zero rows even if the server allowed them.
 *
 * <p>The scheduling engine is a recording double: it returns no schedules and invents none; it only captures what
 * the scheduler would be handed.
 */
@EnabledIfEnvironmentVariable(named = "RUN_DB_IT", matches = "true")
@SpringBootTest(properties = { "app.catalog.source=database", "app.catalog.database.url=${DB_URL}",
		"app.catalog.database.username=${DB_USERNAME}", "app.catalog.database.password=${DB_PASSWORD:}" })
@AutoConfigureMockMvc
@Import(DatabaseCatalogIntegrationTest.RecordingEngineConfig.class)
class DatabaseCatalogIntegrationTest {

	private static final String TERM = "2027-SPRING";

	@Autowired
	CourseCatalog courses;

	@Autowired
	SectionCatalog sections;

	@Autowired
	DataSource dataSource;

	@Autowired
	MockMvc mvc;

	@Autowired
	RecordingEngine engine;

	@BeforeEach
	void clear() {
		engine.problems.clear();
	}

	@TestConfiguration
	static class RecordingEngineConfig {

		@Bean
		RecordingEngine recordingEngine() {
			return new RecordingEngine();
		}

	}

	static class RecordingEngine implements SchedulingEngine {

		final List<Problem> problems = new CopyOnWriteArrayList<>();

		@Override
		public Result solve(Problem problem, int nodeCap) {
			problems.add(problem);
			return new Result(List.of(), List.of("test double: no engine ran, no schedule was produced"), false);
		}

	}

	private static final List<CatalogSection> EXPECTED = List.of(
			new CatalogSection(TERM, "CSE-007", "12275", "010", 4, "LECTURE", null, Status.UNKNOWN,
					List.of(new Meeting(List.of(DayOfWeek.MONDAY), LocalTime.of(9, 20), LocalTime.of(10, 35), "TBD"))),
			new CatalogSection(TERM, "CSE-007", "12638", "062", 0, "LAB", "12275", Status.UNKNOWN,
					List.of(new Meeting(List.of(DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), LocalTime.of(10, 45), LocalTime.of(12, 0),
							"Asa Packer Campus | 019 Packard Lab | Room 11"))),
			new CatalogSection(TERM, "CSE-007", "13132", "060", 0, "LAB", "12275", Status.UNKNOWN,
					List.of(new Meeting(List.of(DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), LocalTime.of(9, 20), LocalTime.of(10, 35),
							"Asa Packer Campus | 019 Packard Lab | Room 112"))));

	@Test
	void databaseModeUsesTheJdbcCatalogs() {
		assertThat(courses).isInstanceOf(JdbcCourseCatalog.class);
		assertThat(sections).isInstanceOf(JdbcSectionCatalog.class);
	}

	@Test
	void theApiTermMapsToBanner202710AndTheBannerCodeIsNotATerm() {
		assertThat(courses.termExists("2027-SPRING")).isTrue();
		assertThat(courses.termExists("202710")).isFalse();
		assertThat(courses.termExists("2027-FALL")).isFalse();
	}

	@Test
	void theCatalogListsExactlyCse007() {
		assertThat(courses.listCourses(TERM)).containsExactly(new CourseSummary("CSE-007", "Introduction to Programming", 4));
		assertThat(courses.findCourse(TERM, "CSE-007")).contains(new CourseSummary("CSE-007", "Introduction to Programming", 4));
	}

	@Test
	void unknownCoursesAndTermsAreEmpty() {
		assertThat(courses.findCourse(TERM, "CSE-017")).isEmpty();
		assertThat(courses.findCourse(TERM, "cse-007")).isEmpty();
		assertThat(courses.findCourse("2027-FALL", "CSE-007")).isEmpty();
		assertThat(courses.listCourses("2027-FALL")).isEmpty();
		assertThat(sections.sectionsFor("2027-FALL", List.of("CSE-007"))).isEmpty();
		assertThat(sections.sectionsFor("202710", List.of("CSE-007"))).isEmpty();
	}

	@Test
	void cse007SectionsMeetingsAndLinksAreExactlyDavidsVerifiedData() {
		assertThat(sections.sectionsFor(TERM, List.of("CSE-007"))).isEqualTo(EXPECTED);
	}

	@Test
	void bothLabsAreLinkedToLecture12275AndTheLectureToNothing() {
		List<CatalogSection> found = sections.sectionsFor(TERM, List.of("CSE-007"));

		assertThat(found).filteredOn(s -> s.crn().equals("12275")).singleElement().satisfies(s -> {
			assertThat(s.componentType()).isEqualTo("LECTURE");
			assertThat(s.credits()).isEqualTo(4);
			assertThat(s.lectureLink()).isNull();
		});
		assertThat(found).filteredOn(s -> s.componentType().equals("LAB")).extracting(CatalogSection::crn)
			.containsExactlyInAnyOrder("13132", "12638");
		assertThat(found).filteredOn(s -> s.componentType().equals("LAB")).allSatisfy(s -> {
			assertThat(s.lectureLink()).isEqualTo("12275");
			assertThat(s.credits()).isZero();
		});
		assertThat(found).extracting(CatalogSection::status).containsOnly(Status.UNKNOWN);
		assertThat(found.stream().flatMap(s -> s.meetings().stream()).mapToInt(m -> m.days().size()).sum()).isEqualTo(5);
	}

	@Test
	void coursesThatAreNotInTheCertifiedViewsNeverComeBack() {
		assertThat(sections.sectionsFor(TERM, List.of("CSE-017", "CSE-109", "DEMO-900"))).isEmpty();
		assertThat(sections.sectionsFor(TERM, List.of("CSE-007", "CSE-017"))).hasSize(3)
			.allSatisfy(s -> assertThat(s.courseId()).isEqualTo("CSE-007"));
	}

	@Test
	void theConnectionIsReadOnlyAndRefusesWrites() throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			connection.setAutoCommit(false);
			try (Statement statement = connection.createStatement()) {
				// Zero-row statements: harmless even if the server were to allow them. The rollback below is extra insurance.
				assertThatThrownBy(() -> statement.executeUpdate("UPDATE cse007_snapshot.section SET credits = credits WHERE false"))
					.isInstanceOf(SQLException.class).hasMessageContaining("read-only transaction");
				connection.rollback();
				assertThatThrownBy(() -> statement.executeUpdate(
						"INSERT INTO cse007_snapshot.term SELECT * FROM cse007_snapshot.term WHERE false"))
					.isInstanceOf(SQLException.class).hasMessageContaining("read-only transaction");
			}
			finally {
				connection.rollback();
			}
		}
	}

	@Test
	void theSchedulerWouldReceiveExactlyTheLectureAndTwoLinkedLabs() throws Exception {
		mvc.perform(post("/api/schedules/generate").contentType(MediaType.APPLICATION_JSON).content("""
				{"term":"2027-SPRING","preferences":{"hardConstraints":{"requiredCourseIds":["CSE-007"],"minCredits":4,"maxCredits":4}}}
				"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.schedules").isEmpty())
			.andExpect(jsonPath("$.diagnostics", hasItem("test double: no engine ran, no schedule was produced")));

		assertThat(engine.problems).hasSize(1);
		SchedulingEngine.Problem problem = engine.problems.get(0);
		assertThat(problem.constraints().minCredits()).isEqualTo(4);
		assertThat(problem.constraints().maxCredits()).isEqualTo(4);
		assertThat(problem.courses()).singleElement().satisfies(course -> {
			assertThat(course.courseId()).isEqualTo("CSE-007");
			assertThat(course.required()).isTrue();
			assertThat(course.sections()).extracting(SchedulingEngine.Section::crn).containsExactly("12275", "12638", "13132");
			assertThat(course.sections()).extracting(SchedulingEngine.Section::credits).containsExactly(4, 0, 0);
			assertThat(course.sections()).extracting(SchedulingEngine.Section::lectureLink).containsExactly(null, "12275", "12275");
			assertThat(course.sections()).extracting(SchedulingEngine.Section::componentType)
				.containsExactly("LECTURE", "LAB", "LAB");
		});
	}

	@Test
	void anUncertifiedOrUnknownRequiredCourseIsRejectedAndNeverReachesTheScheduler() throws Exception {
		mvc.perform(post("/api/schedules/generate").contentType(MediaType.APPLICATION_JSON).content("""
				{"term":"2027-SPRING","preferences":{"hardConstraints":{"requiredCourseIds":["CSE-007","CSE-017"],"minCredits":4,"maxCredits":18}}}
				"""))
			.andExpect(status().isUnprocessableContent());

		assertThat(engine.problems).isEmpty();
	}

}
