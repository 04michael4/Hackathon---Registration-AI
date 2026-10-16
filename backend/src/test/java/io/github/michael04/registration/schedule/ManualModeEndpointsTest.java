package io.github.michael04.registration.schedule;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Manual mode, elective suggestions and elective advice, end to end over the demo catalogs. */
@SpringBootTest
@AutoConfigureMockMvc
class ManualModeEndpointsTest {

	@Autowired MockMvc mvc;

	private static final String PREFS = """
		"preferences":{"hardConstraints":{"requiredCourseIds":["CSE-017"],"minCredits":1,"maxCredits":18}}""";

	private ResultActions post(String path, String body) throws Exception {
		return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
			.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	// --- /check ---

	@Test
	void placedPhysicsLecturePromptsForItsLabAndRecitationAndGreysOutClashes() throws Exception {
		// CSE-017 011 is T/R 13:00-14:15, so the Thursday physics lab 48012 (R 13:00) clashes; lab 48011 (T 13:00) too.
		post("/api/schedules/check", """
			{"term":"2027-SPRING",%s,"courseIds":["PHY-011"],"crns":["41002","48001"]}""".formatted(PREFS))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sections[*].crn", contains("41002", "48001")))
			.andExpect(jsonPath("$.sections[1].title").value("Introductory Physics I"))
			.andExpect(jsonPath("$.totalCredits").value(7))
			.andExpect(jsonPath("$.complete").value(false))
			.andExpect(jsonPath("$.issues[*].type", containsInAnyOrder("MISSING_LINKED_SECTION", "MISSING_LINKED_SECTION")))
			.andExpect(jsonPath("$.issues[*].message", hasItem("PHY-011 lecture 48001 needs a LAB.")))
			.andExpect(jsonPath("$.candidates[?(@.crn=='48012')].fits", contains(false)))
			.andExpect(jsonPath("$.candidates[?(@.crn=='48012')].reasons[0]")
				.value("Meets at the same time as 41002 (CSE-017)."))
			.andExpect(jsonPath("$.candidates[?(@.crn=='48021')].completesLecture", contains(true)));
	}

	@Test
	void completeSelectionIsReportedComplete() throws Exception {
		post("/api/schedules/check", """
			{"term":"2027-SPRING",%s,"crns":["41002"]}""".formatted(PREFS))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.complete").value(true))
			.andExpect(jsonPath("$.issues").isEmpty())
			.andExpect(jsonPath("$.score").value(100));
	}

	@Test
	void timeClashIsAConflictAndUnknownCrnIsReportedNotRejected() throws Exception {
		// CSE-017 010 (MWF 09:00) and MATH-022 010 (MWF 09:00) clash.
		post("/api/schedules/check", """
			{"term":"2027-SPRING",%s,"courseIds":["MATH-022"],"crns":["41001","47001","99999"]}""".formatted(PREFS))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.issues[*].type", hasItem("TIME_CONFLICT")))
			.andExpect(jsonPath("$.issues[*].type", hasItem("UNKNOWN_SECTION")))
			.andExpect(jsonPath("$.issues[0].severity").value("CONFLICT"));
	}

	@Test
	void manualModeHasNoEightElectiveLimit() throws Exception {
		post("/api/schedules/check", """
			{"term":"2027-SPRING",%s,"courseIds":["CSE-109","CSE-140","ECO-001","ENGL-001","MATH-021","MATH-022","PHY-011"],
			 "crns":["41002"]}""".formatted(PREFS))
			.andExpect(status().isOk());
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"{\"term\":\"2027-SPRING\",\"preferences\":{\"hardConstraints\":{\"requiredCourseIds\":[\"CSE-017\"]}}}",
		"{\"term\":\"2027-SPRING\",\"preferences\":{\"hardConstraints\":{\"requiredCourseIds\":[\"CSE-017\"],\"minCredits\":12,\"maxCredits\":9}},\"crns\":[]}",
		"{\"term\":\"\",\"preferences\":{\"hardConstraints\":{\"requiredCourseIds\":[\"CSE-017\"]}},\"crns\":[]}"})
	void invalidCheckRequestsReturn400(String body) throws Exception {
		post("/api/schedules/check", body).andExpect(status().isBadRequest());
	}

	@Test
	void unknownTermOrCourseReturns422() throws Exception {
		post("/api/schedules/check", "{\"term\":\"2030-FALL\"," + PREFS + ",\"crns\":[]}")
			.andExpect(status().isUnprocessableContent());
		post("/api/schedules/check", "{\"term\":\"2027-SPRING\"," + PREFS + ",\"courseIds\":[\"NOPE-1\"],\"crns\":[]}")
			.andExpect(status().isUnprocessableContent());
	}

	// --- /suggest-electives ---

	@Test
	void suggestsOnlyElectivesThatFitTheScheduleWithTheirSections() throws Exception {
		// Schedule: CSE-017 011 (T/R 13:00-14:15). Both labs of physics lecture 48001 clash with it (T/R 13:00), so
		// the only fit is lecture 48002 with its lab 48013 and recitation 48023.
		post("/api/schedules/suggest-electives", """
			{"term":"2027-SPRING",%s,"crns":["41002"],"limit":10}""".formatted(PREFS))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.suggestions[*].courseId", not(hasItem("CSE-017"))))
			.andExpect(jsonPath("$.suggestions[?(@.courseId=='PHY-011')].sections[*].crn",
				contains("48002", "48013", "48023")))
			.andExpect(jsonPath("$.suggestions[0].title").exists())
			.andExpect(jsonPath("$.suggestions[*].totalCredits", everyItem(org.hamcrest.Matchers.greaterThan(3))));
	}

	@Test
	void searchTextNarrowsTheElectives() throws Exception {
		post("/api/schedules/suggest-electives", """
			{"term":"2027-SPRING",%s,"crns":["41002"],"q":"calculus"}""".formatted(PREFS))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.suggestions[*].courseId", containsInAnyOrder("MATH-021", "MATH-022")));
	}

	@Test
	void scheduleCrnsMustBelongToTheScheduleCourses() throws Exception {
		post("/api/schedules/suggest-electives", """
			{"term":"2027-SPRING",%s,"crns":["99999"]}""".formatted(PREFS))
			.andExpect(status().isUnprocessableContent());
		post("/api/schedules/suggest-electives", """
			{"term":"2027-SPRING",%s,"crns":["41002"],"limit":21}""".formatted(PREFS))
			.andExpect(status().isBadRequest());
	}

	// --- elective advice in /generate ---

	@Test
	void generateExplainsATypedElectiveThatDidNotFit() throws Exception {
		// ENGL-001 can't fit: every section overlaps the blocked times below.
		post("/api/schedules/generate", """
			{"term":"2027-SPRING","maxResults":4,"preferences":{
			  "hardConstraints":{"requiredCourseIds":["CSE-017"],"minCredits":1,"maxCredits":18,
			    "blockedTimes":[{"days":["MONDAY","WEDNESDAY","FRIDAY"],"start":"08:00","end":"12:00"},
			                    {"days":["TUESDAY","THURSDAY"],"start":"16:00","end":"18:00"}]},
			  "softPreferences":{"optionalCourseIds":{"value":["ENGL-001"],"weight":3}}}}""")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.electiveAdvice[0].courseId").value("ENGL-001"))
			.andExpect(jsonPath("$.electiveAdvice[0].status").value("CANNOT_FIT"))
			.andExpect(jsonPath("$.electiveAdvice[0].fixes[0]").exists());
	}

}
