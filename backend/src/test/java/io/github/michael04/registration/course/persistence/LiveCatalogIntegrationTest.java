package io.github.michael04.registration.course.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import io.github.michael04.registration.course.CatalogSection;
import io.github.michael04.registration.course.CourseCatalog;
import io.github.michael04.registration.course.CourseSummary;
import io.github.michael04.registration.course.SectionCatalog;

/**
 * OPT-IN: reads the {@code catalog_live} views (verified snapshot + approved promoted courses) from a real PostgreSQL
 * through the REAL scheduling engine. Needs a TEST database built by data/setup-local-database.sh into which
 * {@code data/catalog-promotion/load_synthetic_fixture.py} has loaded its SYNTHETIC courses:
 * CSE-092 (lecture-only, promoted), CSE-091 (lecture + two labs, promoted), CSE-090 (blocked, never served).
 * Skipped unless {@code RUN_LIVE_IT=true}, so {@code ./mvnw test} never needs a database.
 *
 * <pre>
 * RUN_LIVE_IT=true DB_URL=jdbc:postgresql://127.0.0.1:55434/registration_local DB_USERNAME=registration_reader \
 *   PGPASSFILE=... ./mvnw test -Dtest=LiveCatalogIntegrationTest
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_IT", matches = "true")
@SpringBootTest(properties = { "app.catalog.source=database", "app.catalog.database.url=${DB_URL}",
		"app.catalog.database.username=${DB_USERNAME}", "app.catalog.database.password=${DB_PASSWORD:}",
		"app.catalog.database.schema=catalog_live" })
@AutoConfigureMockMvc
class LiveCatalogIntegrationTest {

	private static final String TERM = "2027-SPRING";

	@Autowired
	CourseCatalog courses;

	@Autowired
	SectionCatalog sections;

	@Autowired
	MockMvc mvc;

	private String generate(int min, int max, String... required) throws Exception {
		StringBuilder ids = new StringBuilder();
		for (String id : required) {
			ids.append(ids.length() == 0 ? "" : ",").append('"').append(id).append('"');
		}
		return mvc.perform(post("/api/schedules/generate").contentType(MediaType.APPLICATION_JSON)
				.content("{\"term\":\"" + TERM + "\",\"preferences\":{\"hardConstraints\":{\"requiredCourseIds\":[" + ids
						+ "],\"minCredits\":" + min + ",\"maxCredits\":" + max + "}}}"))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
	}

	private static List<List<String>> crnsPerSchedule(String body) {
		int n = ((List<?>) JsonPath.read(body, "$.schedules")).size();
		List<List<String>> out = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			out.add(JsonPath.read(body, "$.schedules[" + i + "].sections[*].crn"));
		}
		return out;
	}

	@Test
	void theCatalogOffersTheSnapshotCourseAndOnlyThePromotedSyntheticCourses() {
		assertThat(courses.listCourses(TERM)).extracting(CourseSummary::courseId)
			.containsExactly("CSE-007", "CSE-091", "CSE-092");
		assertThat(courses.findCourse(TERM, "CSE-090")).isEmpty();
	}

	@Test
	void promotedSectionsKeepTheirTypesLinksAndCredits() {
		List<CatalogSection> found = sections.sectionsFor(TERM, List.of("CSE-091", "CSE-092"));
		assertThat(found).extracting(s -> s.crn() + ":" + s.componentType() + ":" + s.credits() + ":" + s.lectureLink())
			.containsExactlyInAnyOrder("91001:LECTURE:3:null", "91002:LAB:0:91001", "91003:LAB:0:91001", "92001:LECTURE:3:null",
					"92002:LECTURE:3:null");
		assertThat(found).allSatisfy(s -> assertThat(s.meetings()).isNotEmpty());
	}

	@Test
	void csE007StillGeneratesItsTwoRealSchedulesThroughCatalogLive() throws Exception {
		String body = generate(4, 4, "CSE-007");
		assertThat(crnsPerSchedule(body)).hasSize(2).allSatisfy(s -> assertThat(s).hasSize(2).contains("12275"));
		assertThat(crnsPerSchedule(body).stream().map(s -> s.stream().filter(c -> !c.equals("12275")).findFirst().orElseThrow()).toList())
			.containsExactlyInAnyOrder("12638", "13132");
	}

	@Test
	void aPromotedLectureOnlyCourseSchedulesEachOfItsLectureSectionsAsAnAlternative() throws Exception {
		String body = generate(3, 3, "CSE-092");
		assertThat(crnsPerSchedule(body)).containsExactlyInAnyOrder(List.of("92001"), List.of("92002"));
	}

	@Test
	void aPromotedLectureAndLabCourseAlwaysGetsExactlyOneLab() throws Exception {
		String body = generate(3, 3, "CSE-091");
		assertThat(crnsPerSchedule(body)).hasSize(2).allSatisfy(s -> assertThat(s).hasSize(2).contains("91001"));
		assertThat(crnsPerSchedule(body).stream().map(s -> s.stream().filter(c -> !c.equals("91001")).findFirst().orElseThrow()).toList())
			.containsExactlyInAnyOrder("91002", "91003");
	}

	@Test
	void snapshotAndPromotedCoursesScheduleTogetherWithoutAConflict() throws Exception {
		// 92001 (Mon/Wed 10:00-10:50) overlaps CSE 007's Monday lecture (09:20-10:35); 92002 (Tue/Thu) does not.
		String body = generate(7, 7, "CSE-007", "CSE-092");
		List<List<String>> schedules = crnsPerSchedule(body);
		assertThat(schedules).hasSize(2).allSatisfy(s -> assertThat(s).hasSize(3).contains("12275", "92002").doesNotContain("92001"));
	}

	@Test
	void aBlockedCourseIsUnknownToTheSchedulerAndNeverReachesTheEngine() throws Exception {
		mvc.perform(post("/api/schedules/generate").contentType(MediaType.APPLICATION_JSON).content(
				"{\"term\":\"" + TERM + "\",\"preferences\":{\"hardConstraints\":{\"requiredCourseIds\":[\"CSE-090\"],\"minCredits\":3,\"maxCredits\":3}}}"))
			.andExpect(status().isUnprocessableContent());
		mvc.perform(post("/api/schedules/generate").contentType(MediaType.APPLICATION_JSON).content(
				"{\"term\":\"" + TERM + "\",\"preferences\":{\"hardConstraints\":{\"requiredCourseIds\":[\"CSE-017\"],\"minCredits\":3,\"maxCredits\":3}}}"))
			.andExpect(status().isUnprocessableContent());
	}

	@Test
	void everyScheduleReportsItsCreditsAndNoOverload() throws Exception {
		mvc.perform(post("/api/schedules/generate").contentType(MediaType.APPLICATION_JSON).content(
				"{\"term\":\"" + TERM + "\",\"preferences\":{\"hardConstraints\":{\"requiredCourseIds\":[\"CSE-091\"],\"minCredits\":3,\"maxCredits\":3}}}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.schedules", hasSize(2)))
			.andExpect(jsonPath("$.schedules[0].totalCredits").value(3))
			.andExpect(jsonPath("$.schedules[0].overload").value(false));
	}

}
