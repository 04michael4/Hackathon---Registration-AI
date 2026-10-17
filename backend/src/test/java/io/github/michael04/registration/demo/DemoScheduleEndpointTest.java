package io.github.michael04.registration.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The demo vertical slice: the real schedule service and the real engine on the demo catalog (synthetic fixture), through HTTP.
 * Nothing is mocked except the network.
 */
@SpringBootTest(properties = { "app.catalog.source=demo", "app.demo.fixture-path=classpath:demo/synthetic-fixture.json", "app.ai.provider=none" })
@AutoConfigureMockMvc
class DemoScheduleEndpointTest {

	@Autowired
	MockMvc mvc;

	private String generate(String body) throws Exception {
		return mvc.perform(post("/api/demo/schedules/generate").contentType(MediaType.APPLICATION_JSON).content(body))
			.andReturn().getResponse().getContentAsString();
	}

	private org.springframework.test.web.servlet.ResultActions run(String body) throws Exception {
		return mvc.perform(post("/api/demo/schedules/generate").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	@Test
	void everyOfferingInTheFixtureIsListedWithItsSections() throws Exception {
		mvc.perform(get("/api/demo/offerings")).andExpect(status().isOk())
			.andExpect(jsonPath("$.term").value("2027-SPRING"))
			.andExpect(jsonPath("$.offerings", hasSize(11)))
			.andExpect(jsonPath("$.offerings[*].offering.offeringId", hasItem("SYN-201-010")))
			.andExpect(jsonPath("$.offerings[?(@.offering.offeringId=='SYN-102')].offering.sections.length()").value(3))
			.andExpect(jsonPath("$.caution").value(containsString("Proposed scheduling options only")));
	}

	@Test
	void aLectureOnlyCourseGivesARealTimetableWithEligibilityUnverified() throws Exception {
		run("{\"courseIds\":[\"SYN-101\"]}").andExpect(status().isOk())
			.andExpect(jsonPath("$.schedules", hasSize(1)))
			.andExpect(jsonPath("$.schedules[0].totalCredits").value(3))
			.andExpect(jsonPath("$.schedules[0].sections[0].crn").value("91001"))
			.andExpect(jsonPath("$.schedules[0].sections[0].meetings[0].start").value("09:00"))
			.andExpect(jsonPath("$.schedules[0].status").value("UNVERIFIED"))
			.andExpect(jsonPath("$.schedules[0].warnings[*].code", hasItem("REGISTRATION_ELIGIBILITY_NOT_CONFIRMED")))
			.andExpect(jsonPath("$.schedules[0].warnings[*].code", hasItem("PREREQUISITE_NOT_VERIFIED")))
			.andExpect(jsonPath("$.schedules[0].warnings[*].code", hasItem("LINKED_COMPONENTS_UNVERIFIED")))
			.andExpect(jsonPath("$.schedules[0].warnings[?(@.code=='CLASS_STANDING')].outcome").value("UNRESOLVED"));
	}

	@Test
	void aCourseWithLabsGetsExactlyOneLabAndTheLabAddsNoCredits() throws Exception {
		run("{\"courseIds\":[\"SYN-102\"]}").andExpect(status().isOk())
			.andExpect(jsonPath("$.schedules", hasSize(2)))
			.andExpect(jsonPath("$.schedules[*].totalCredits").value(hasItem(3)))
			.andExpect(jsonPath("$.schedules[0].sections", hasSize(2)))
			.andExpect(jsonPath("$.schedules[0].sections[?(@.component=='LAB')].credits").value(0))
			.andExpect(jsonPath("$.schedules[0].sections[?(@.component=='LECTURE')].crn").value("91101"))
			.andExpect(jsonPath("$.schedules[0].warnings[*].code", hasItem("REQUIRED_LAB")))
			.andExpect(jsonPath("$.schedules[*].sections[?(@.component=='LAB')].crn").value(hasItem("91102")))
			.andExpect(jsonPath("$.schedules[*].sections[?(@.component=='LAB')].crn").value(hasItem("91103")));
	}

	@Test
	void overlappingFixedMeetingsAreRejectedAndExplained() throws Exception {
		run("{\"courseIds\":[\"SYN-101\",\"SYN-103\"]}").andExpect(status().isOk())
			.andExpect(jsonPath("$.schedules", hasSize(0)))
			.andExpect(jsonPath("$.diagnostics[0]").value(containsString("conflict")));
	}

	@Test
	void anOptionalCourseThatOverlapsARequiredOneNeverAppears() throws Exception {
		String body = generate("{\"courseIds\":[\"SYN-101\"],\"optionalCourseIds\":[\"SYN-103\"]}");
		assertThat(body).contains("91001").doesNotContain("91201");
	}

	@Test
	void theCreditRangeIsHonouredAndNoTimetableIsInventedToReachIt() throws Exception {
		run("{\"courseIds\":[\"SYN-101\",\"SYN-102\"],\"minCredits\":6,\"maxCredits\":6}").andExpect(jsonPath("$.schedules", hasSize(2)))
			.andExpect(jsonPath("$.schedules[0].totalCredits").value(6));
		run("{\"courseIds\":[\"SYN-101\",\"SYN-102\"],\"minCredits\":7,\"maxCredits\":9}").andExpect(jsonPath("$.schedules", hasSize(0)));
	}

	@Test
	void twoTopicsOfOneCourseNumberStayDistinctByCrnAndTitle() throws Exception {
		run("{\"courseIds\":[\"SYN-201-010\",\"SYN-201-020\"],\"profile\":{\"classStanding\":\"SR\"}}").andExpect(status().isOk())
			.andExpect(jsonPath("$.schedules", hasSize(1)))
			.andExpect(jsonPath("$.schedules[0].totalCredits").value(6))
			.andExpect(jsonPath("$.schedules[0].sections[?(@.crn=='91301')].title").value("Synthetic Topic Alpha"))
			.andExpect(jsonPath("$.schedules[0].sections[?(@.crn=='91302')].title").value("Synthetic Topic Beta"))
			.andExpect(jsonPath("$.schedules[0].sections[?(@.crn=='91301')].courseId").value("SYN-201-010"))
			.andExpect(jsonPath("$.schedules[0].sections[?(@.crn=='91302')].courseId").value("SYN-201-020"));
		run("{\"courseIds\":[\"SYN-201-010\"]}").andExpect(jsonPath("$.schedules[0].sections", hasSize(1)))
			.andExpect(jsonPath("$.schedules[0].sections[0].crn").value("91301"));
	}

	@Test
	void anIndependentStudyTypeSectionIsAConditionalOptionWithItsMeetingAndAWarning() throws Exception {
		run("{\"courseIds\":[\"SYN-301\"]}").andExpect(status().isOk())
			.andExpect(jsonPath("$.schedules", hasSize(1)))
			.andExpect(jsonPath("$.schedules[0].sections[0].meetings[0].days[0]").value("MONDAY"))
			.andExpect(jsonPath("$.schedules[0].sections[0].meetings[0].start").value("15:00"))
			.andExpect(jsonPath("$.schedules[0].sections[0].meetings[0].end").value("16:15"))
			.andExpect(jsonPath("$.schedules[0].warnings[*].code", hasItem("ADMINISTRATIVE_TYPE")))
			.andExpect(jsonPath("$.schedules[0].warnings[?(@.code=='NOTE')].source").value("TEAM_POLICY"))
			.andExpect(jsonPath("$.schedules[0].warnings[?(@.code=='PREREQUISITE_NOT_VERIFIED')].text").value(hasItem(containsString("Prerequisite not verified"))));
	}

	@Test
	void aFirstYearStudentStillGetsATimetableButItIsMarkedOverrideRequired() throws Exception {
		run("{\"courseIds\":[\"SYN-101\"],\"profile\":{\"classStanding\":\"FR\"}}").andExpect(status().isOk())
			.andExpect(jsonPath("$.schedules", hasSize(1)))
			.andExpect(jsonPath("$.schedules[0].status").value("OVERRIDE_REQUIRED"))
			.andExpect(jsonPath("$.courses[0].status").value("OVERRIDE_REQUIRED"));
		run("{\"courseIds\":[\"SYN-101\"],\"profile\":{\"classStanding\":\"JR\"}}")
			.andExpect(jsonPath("$.schedules[0].status").value("UNVERIFIED"))
			.andExpect(jsonPath("$.schedules[0].warnings[?(@.code=='CLASS_STANDING')].outcome").value("SATISFIED"));
	}

	@Test
	void anArrangedTeamMeetingIsFlaggedAndNeverGivenInventedTimes() throws Exception {
		run("{\"courseIds\":[\"SYN-401\"]}").andExpect(status().isOk())
			.andExpect(jsonPath("$.schedules[0].sections[0].meetings", hasSize(1)))
			.andExpect(jsonPath("$.schedules[0].sections[0].meetings[0].days[0]").value("FRIDAY"))
			.andExpect(jsonPath("$.schedules[0].warnings[?(@.code=='ARRANGED_MEETING')].text").value(hasItem(containsString("Time to be arranged"))));
	}

	@Test
	void creditExclusionsAmongSelectedCoursesAreShownNotHidden() throws Exception {
		run("{\"courseIds\":[\"SYN-601\",\"SYN-602\"]}").andExpect(status().isOk())
			.andExpect(jsonPath("$.schedules", hasSize(1)))
			.andExpect(jsonPath("$.schedules[0].warnings[*].code", hasItem("CREDIT_EXCLUSION_IN_SELECTION")));
		run("{\"courseIds\":[\"SYN-601\"]}").andExpect(jsonPath("$.schedules[0].warnings[*].code", not(hasItem("CREDIT_EXCLUSION_IN_SELECTION"))))
			.andExpect(jsonPath("$.schedules[0].warnings[*].code", hasItem("CREDIT_EXCLUSION")));
	}

	@Test
	void badInputIsRejectedWithTheSharedErrorStatuses() throws Exception {
		run("{\"courseIds\":[\"SYN-101\"],\"profile\":{\"classStanding\":\"XX\"}}").andExpect(status().isBadRequest());
		run("{\"courseIds\":[\"SYN-101\"],\"profile\":{\"level\":\"PHD\"}}").andExpect(status().isBadRequest());
		run("{\"courseIds\":[]}").andExpect(status().isBadRequest());
		run("{\"courseIds\":[\"SYN-101\"],\"minCredits\":9,\"maxCredits\":3}").andExpect(status().isBadRequest());
		run("{\"courseIds\":[\"NOPE-999\"]}").andExpect(status().isUnprocessableEntity());
	}

	@Test
	void theSameRequestGivesTheSameAnswer() throws Exception {
		String body = "{\"courseIds\":[\"SYN-101\",\"SYN-102\"],\"optionalCourseIds\":[\"SYN-701\",\"SYN-501\"],\"minCredits\":6,\"maxCredits\":14}";
		assertThat(generate(body)).isEqualTo(generate(body));
	}

	@Test
	void theSharedScheduleEndpointAndCourseSearchWorkOnTheDemoCatalogUnchanged() throws Exception {
		mvc.perform(post("/api/schedules/generate").contentType(MediaType.APPLICATION_JSON).content(
				"{\"term\":\"2027-SPRING\",\"preferences\":{\"hardConstraints\":{\"requiredCourseIds\":[\"SYN-102\"],\"minCredits\":3,\"maxCredits\":3}}}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.schedules", hasSize(2)));
		mvc.perform(get("/api/courses").param("term", "2027-SPRING").param("q", "Synthetic")).andExpect(status().isOk())
			.andExpect(jsonPath("$.totalItems").value(11));
	}

	@Test
	void theDemoPageIsServed() throws Exception {
		mvc.perform(get("/demo")).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
			.andExpect(content().string(containsString("proposed-schedule demo")));
	}

}
