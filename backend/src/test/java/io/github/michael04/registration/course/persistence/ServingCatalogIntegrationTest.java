package io.github.michael04.registration.course.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import io.github.michael04.registration.course.CourseCatalog;
import io.github.michael04.registration.course.CourseSummary;

/**
 * OPT-IN: reads the {@code catalog_serving} views (researched READY and CONDITIONAL courses, with their conditions) from a real
 * PostgreSQL, through the real course endpoints and the real scheduling engine. Needs a database with {@code catalog_research} and
 * {@code data/catalog-serving} installed and the 2026-10-17 Spring 2027 research loaded (40 served courses, 50 sections).
 * Skipped unless {@code RUN_SERVING_IT=true}, so {@code ./mvnw test} never needs a database.
 *
 * <pre>
 * RUN_SERVING_IT=true DB_URL=jdbc:postgresql://127.0.0.1:55437/ratings_scratch DB_USERNAME=catalog_reader \
 *   ./mvnw test -Dtest=ServingCatalogIntegrationTest
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "RUN_SERVING_IT", matches = "true")
@SpringBootTest(properties = { "app.catalog.source=database", "app.catalog.database.url=${DB_URL}",
		"app.catalog.database.username=${DB_USERNAME}", "app.catalog.database.password=${DB_PASSWORD:}",
		"app.catalog.database.schema=catalog_serving" })
@AutoConfigureMockMvc
class ServingCatalogIntegrationTest {

	private static final String TERM = "2027-SPRING";

	@Autowired
	CourseCatalog courses;

	@Autowired
	MockMvc mvc;

	private String body(String url) throws Exception {
		return mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	@Test
	void theResearchedCourseSetIsServedAndNothingBlockedIs() throws Exception {
		List<String> ids = courses.listCourses(TERM).stream().map(CourseSummary::courseId).toList();
		assertThat(ids).hasSize(40).contains("CSE-007", "CSE-017", "CSE-216", "CSE-217");
		assertThat(ids).noneMatch(id -> id.startsWith("CSE-398-"));           // topics undecided at install time
		assertThat(ids).noneMatch(id -> id.startsWith("MATH-021") || id.startsWith("WRT-"));   // grid-only non-CSE courses stay blocked
		mvc.perform(get("/api/courses/MATH-021").param("term", TERM)).andExpect(status().isNotFound());
	}

	@Test
	void aPrerequisiteCourseShowsItsConditionsAndNeverAnEligibilityClaim() throws Exception {
		String detail = body("/api/courses/CSE-216?term=" + TERM);
		List<String> messages = JsonPath.read(detail, "$.conditions[*].message");
		assertThat(messages).contains("Prerequisite: CSE 017", "Not open to these class standings: first-year");
		assertThat((List<String>) JsonPath.read(detail, "$.conditions[*].outcome")).containsOnly("UNVERIFIED");
		assertThat(detail.toLowerCase()).doesNotContain("eligible");
	}

	@Test
	void aLabCourseKeepsItsLinkedLabsAndMeetings() throws Exception {
		String detail = body("/api/courses/CSE-017?term=" + TERM);
		List<String> lectureLinks = JsonPath.read(detail, "$.sections[?(@.componentType=='LAB')].lectureLink");
		assertThat(lectureLinks).isNotEmpty().doesNotContainNull();
		List<?> lectures = JsonPath.read(detail, "$.sections[?(@.componentType=='LECTURE')]");
		assertThat(lectures).isNotEmpty();
	}

	@Test
	void theTimeToBeArrangedMeetingIsAConditionNotAMeeting() throws Exception {
		String detail = body("/api/courses/CSE-367?term=" + TERM);
		assertThat((List<String>) JsonPath.read(detail, "$.conditions[*].code")).contains("ARRANGED_MEETING");
	}

	@Test
	void aScheduleOfResearchedCoursesIsGeneratedByTheRealEngine() throws Exception {
		String result = mvc.perform(post("/api/schedules/generate").contentType(MediaType.APPLICATION_JSON)
				.content("{\"term\":\"" + TERM + "\",\"preferences\":{\"hardConstraints\":{\"requiredCourseIds\":[\"CSE-017\",\"CSE-252\"],"
						+ "\"minCredits\":6,\"maxCredits\":9}}}"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		List<?> schedules = JsonPath.read(result, "$.schedules");
		assertThat(schedules).isNotEmpty();
		List<List<String>> perSchedule = new java.util.ArrayList<>();
		for (int i = 0; i < schedules.size(); i++) {
			perSchedule.add(JsonPath.read(result, "$.schedules[" + i + "].sections[*].courseId"));
		}
		assertThat(perSchedule).allSatisfy(ids -> assertThat(ids).contains("CSE-017", "CSE-252"));
	}

}
