package io.github.michael04.registration.schedule;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The default (no database) app generates real schedules from the demo catalogs. */
@SpringBootTest
@AutoConfigureMockMvc
class DemoCatalogScheduleTest {

	@Autowired MockMvc mvc;

	@Test
	void theDummyAiExampleGetsFourSuggestions() throws Exception {
		// The preferences the dummy AI returns for "I need CSE 17 and Calc 1, no classes before 10, Fridays off".
		String body = """
			{"term":"2027-SPRING","maxResults":4,"preferences":{
			  "hardConstraints":{"requiredCourseIds":["CSE-017","MATH-021"],"minCredits":1,"maxCredits":18,
			    "blockedTimes":[{"days":["TUESDAY","THURSDAY"],"start":"15:00","end":"18:00"}]},
			  "softPreferences":{"earliestStart":{"value":"10:00","weight":4},"daysOff":{"value":["FRIDAY"],"weight":3}}}}
			""";
		mvc.perform(post("/api/schedules/generate").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.schedules.length()").value(4))
			// CSE-017 011 (T/R 13:00) + MATH-021 011 (T/R 10:30): nothing before 10, nothing on Friday.
			.andExpect(jsonPath("$.schedules[0].sections[*].crn", hasItems("41002", "46002")))
			.andExpect(jsonPath("$.schedules[0].score").value(100))
			// The closed section CSE-017 012 never appears.
			.andExpect(jsonPath("$.schedules[*].sections[*].crn", everyItem(not("41003"))));
	}

	@Test
	void physicsComesWithOneLabAndOneRecitation() throws Exception {
		String body = """
			{"term":"2027-SPRING","maxResults":1,"preferences":{
			  "hardConstraints":{"requiredCourseIds":["PHY-011"],"minCredits":1,"maxCredits":18}}}
			""";
		mvc.perform(post("/api/schedules/generate").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.schedules[0].sections.length()").value(3))
			.andExpect(jsonPath("$.schedules[0].totalCredits").value(4));
	}

}
