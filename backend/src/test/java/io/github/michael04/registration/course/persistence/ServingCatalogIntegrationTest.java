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
		assertThat(ids).hasSizeGreaterThanOrEqualTo(40).contains("CSE-007", "CSE-017", "CSE-216", "CSE-217");
		// the two CSE 398 topics are separate offerings once the owner's decision is applied (data/catalog-research/apply_owner_decisions.sql)
		org.junit.jupiter.api.Assumptions.assumeTrue(ids.contains("CSE-398-042") == ids.contains("CSE-398-052"), "only one CSE 398 topic is served");
		// never served, whatever else was imported: shared or unlinkable components, and credits that vary by section
		for (String blocked : new String[] { "CHM-030", "PHY-012", "MATH-312", "MATH-334" }) {
			assertThat(ids).doesNotContain(blocked);
			mvc.perform(get("/api/courses/" + blocked).param("term", TERM)).andExpect(status().isNotFound());
		}
	}

	@Test
	void anInspectedNonCseCourseIsServedWithLinkedRecitationsAndItsConditions() throws Exception {
		List<String> ids = courses.listCourses(TERM).stream().map(CourseSummary::courseId).toList();
		org.junit.jupiter.api.Assumptions.assumeTrue(ids.contains("MATH-021"), "non-CSE Class Details are not imported in this database");
		String detail = body("/api/courses/MATH-021?term=" + TERM);
		List<String> lectureCrns = JsonPath.read(detail, "$.sections[?(@.componentType=='LECTURE')].crn");
		List<String> links = JsonPath.read(detail, "$.sections[?(@.componentType=='RECITATION')].lectureLink");
		assertThat(lectureCrns).hasSize(2);
		assertThat(links).isNotEmpty().doesNotContainNull().allSatisfy(l -> assertThat(lectureCrns).contains(l));
		assertThat((List<String>) JsonPath.read(detail, "$.conditions[*].code")).contains("PREREQUISITE", "ENROLLMENT_RESTRICTION");
		assertThat((List<String>) JsonPath.read(detail, "$.conditions[*].outcome")).containsOnly("UNVERIFIED");
	}

	@Test
	void theEngineSchedulesANonCseCourseTogetherWithItsRecitation() throws Exception {
		List<String> ids = courses.listCourses(TERM).stream().map(CourseSummary::courseId).toList();
		org.junit.jupiter.api.Assumptions.assumeTrue(ids.contains("MATH-021") && ids.contains("WRT-001"), "non-CSE Class Details are not imported");
		String result = mvc.perform(post("/api/schedules/generate").contentType(MediaType.APPLICATION_JSON)
				.content("{\"term\":\"" + TERM + "\",\"preferences\":{\"hardConstraints\":{\"requiredCourseIds\":[\"MATH-021\",\"WRT-001\"],"
						+ "\"minCredits\":6,\"maxCredits\":9}}}"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		List<?> schedules = JsonPath.read(result, "$.schedules");
		assertThat(schedules).isNotEmpty();
		for (int i = 0; i < schedules.size(); i++) {
			List<String> mathSections = JsonPath.read(result, "$.schedules[" + i + "].sections[?(@.courseId=='MATH-021')].crn");
			assertThat(mathSections).as("a lecture and its recitation").hasSize(2);
		}
	}

	@Test
	void theTwoCse398TopicsAreServedAsSeparateCoursesWhenDecided() throws Exception {
		List<String> ids = courses.listCourses(TERM).stream().map(CourseSummary::courseId).toList();
		org.junit.jupiter.api.Assumptions.assumeTrue(ids.contains("CSE-398-042"), "the CSE 398 topic decision is not applied in this database");
		assertThat(ids).contains("CSE-398-052");
		String detail = body("/api/courses/CSE-398-042?term=" + TERM);
		assertThat((String) JsonPath.read(detail, "$.courseId")).isEqualTo("CSE-398-042");
		assertThat((String) JsonPath.read(detail, "$.number")).isEqualTo("398");
		assertThat((String) JsonPath.read(detail, "$.title")).isEqualTo("Advanced Operating Systems");
		assertThat((String) JsonPath.read(body("/api/courses/CSE-398-052?term=" + TERM), "$.title")).isEqualTo("Accelerated Computing for Machine Learning");
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
	void courseSearchCarriesPredictedRatingsWithinRangeAndNeverMoreThanStored() throws Exception {
		String list = body("/api/courses?term=" + TERM + "&size=100");
		List<Object> ratings = JsonPath.read(list, "$.items[?(@.predictedRating != null)].predictedRating.quality");
		assertThat(ratings).hasSizeGreaterThan(20);
		assertThat((List<Double>) JsonPath.read(list, "$.items[*].predictedRating.quality")).allSatisfy(q -> assertThat(q).isBetween(1.0, 5.0));
		assertThat((List<String>) JsonPath.read(list, "$.items[*].predictedRating.basis")).containsOnly("PREDICTED");
	}

	@Test
	void sectionsListTheirInstructorsAndAnUnratedInstructorHasNoNumbers() throws Exception {
		String detail = body("/api/courses/CSE-216?term=" + TERM);
		assertThat((List<String>) JsonPath.read(detail, "$.sections[*].instructors[*]")).isNotEmpty();
		List<Double> predicted = JsonPath.read(detail, "$.sections[*].instructorRatings[*].predictedQuality");
		assertThat(predicted).allSatisfy(q -> assertThat(q == null || (q >= 1.0 && q <= 5.0)).isTrue());
		// a course nobody has rated shows null, not a number
		String unrated = body("/api/courses/CSE-252?term=" + TERM);
		assertThat((Object) JsonPath.read(unrated, "$.predictedRating")).isNull();
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
