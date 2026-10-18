package io.github.michael04.registration.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Opt-in: runs the whole slice on the REAL local fixture of all 42 researched Spring 2027 offerings. Skipped unless
 * {@code DEMO_FIXTURE_PATH} points at a fixture built by data/catalog-promotion/tools/build_demo_fixture.py (that file is kept outside
 * the repository). {@code DEMO_FIXTURE_PATH=... ./mvnw test -Dtest=DemoRealFixtureTest}
 */
@EnabledIfEnvironmentVariable(named = "DEMO_FIXTURE_PATH", matches = ".+")
@SpringBootTest(properties = { "app.catalog.source=demo", "app.demo.fixture-path=${DEMO_FIXTURE_PATH}", "app.ai.provider=none" })
@AutoConfigureMockMvc
class DemoRealFixtureTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	DemoCatalog catalog;
	@Autowired
	JsonMapper json;

	private JsonNode generate(String body) throws Exception {
		var result = mvc.perform(post("/api/demo/schedules/generate").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn();
		return json.readTree(result.getResponse().getContentAsString());
	}

	private DemoFixture.Offering offering(String id) {
		return catalog.offering(id).orElseThrow(() -> new AssertionError("missing offering " + id));
	}

	@Test
	void allFortyTwoOfferingsAreLoadedAndIdentified() {
		assertThat(catalog.offerings()).hasSize(42);
		assertThat(catalog.offerings().stream().mapToInt(o -> o.sections().size()).sum()).isEqualTo(52);
		assertThat(catalog.offerings().stream().map(DemoFixture.Offering::offeringId).distinct().count()).isEqualTo(42);
		assertThat(catalog.offerings().stream().flatMap(o -> o.sections().stream()).map(DemoFixture.Section::crn).distinct().count()).isEqualTo(52);
		assertThat(catalog.offerings()).extracting(DemoFixture.Offering::offeringId).contains("CSE-007", "CSE-217", "CSE-252", "CSE-367", "CSE-467",
				"CSE-398-042", "CSE-398-052");
		assertThat(catalog.offerings()).allSatisfy(o -> assertThat(o.title()).isNotBlank());
	}

	@Test
	void theLinkedLabCoursesHaveTheRightCreditsAndLabsThatAddNone() {
		Map<String, Integer> totals = Map.of("CSE-007", 4, "CSE-017", 3, "CSE-109", 4, "CSE-202", 3, "CSE-216", 3, "CSE-241", 3);
		totals.forEach((id, credits) -> {
			var o = offering(id);
			var lectures = o.sections().stream().filter(s -> s.component().equals("LECTURE")).toList();
			var labs = o.sections().stream().filter(s -> s.component().equals("LAB")).toList();
			assertThat(lectures).as(id + " lectures").hasSize(1);
			assertThat(lectures.get(0).credits()).as(id + " lecture credits").isEqualTo(credits);
			assertThat(labs).as(id + " labs").isNotEmpty().allSatisfy(l -> {
				assertThat(l.credits()).isZero();
				assertThat(l.lectureCrn()).isEqualTo(lectures.get(0).crn());
			});
		});
		long othersWithLabs = catalog.offerings().stream().filter(o -> !totals.containsKey(o.offeringId()))
			.filter(o -> o.sections().stream().anyMatch(s -> s.component().equals("LAB"))).count();
		assertThat(othersWithLabs).isZero();
	}

	@Test
	void theTwoCse398TopicsStayDistinctBySectionAndTitle() {
		var a = offering("CSE-398-042");
		var b = offering("CSE-398-052");
		assertThat(a.sections()).extracting(DemoFixture.Section::crn).containsExactly("14215");
		assertThat(b.sections()).extracting(DemoFixture.Section::crn).containsExactly("15194");
		assertThat(a.title()).isNotEqualTo(b.title());
		assertThat(catalog.offerings().stream().filter(o -> o.number().equals("398"))).hasSize(2);
	}

	@Test
	void cse217IsAConditionalOptionOnMondayAndWednesdayAfternoon() throws Exception {
		var o = offering("CSE-217");
		assertThat(o.administrativeType()).isEqualTo("Independent Study");
		assertThat(o.credits()).isEqualTo(3);
		var m = o.sections().get(0).meetings().get(0);
		assertThat(o.sections().get(0).crn()).isEqualTo("14003");
		assertThat(m.days()).containsExactly(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY);
		assertThat(m.start()).isEqualTo(LocalTime.of(15, 0));
		assertThat(m.end()).isEqualTo(LocalTime.of(16, 15));
		JsonNode d = generate("{\"courseIds\":[\"CSE-217\"],\"profile\":{\"classStanding\":\"JR\"}}");
		assertThat(d.get("schedules")).hasSize(1);
		List<String> codes = d.get("schedules").get(0).get("warnings").valueStream().map(w -> w.get("code").asString()).toList();
		assertThat(codes).contains("ADMINISTRATIVE_TYPE", "PREREQUISITE_NOT_VERIFIED", "REGISTRATION_ELIGIBILITY_NOT_CONFIRMED");
	}

	@Test
	void cse367And467ShowTheFixedFridayMeetingAndFlagTheTeamMeeting() throws Exception {
		for (String id : List.of("CSE-367", "CSE-467")) {
			var o = offering(id);
			var m = o.sections().get(0).meetings().get(0);
			assertThat(m.days()).containsExactly(DayOfWeek.FRIDAY);
			assertThat(m.start()).isEqualTo(LocalTime.of(10, 45));
			assertThat(o.arrangedMeeting()).contains("Time to be arranged");
			JsonNode d = generate("{\"courseIds\":[\"" + id + "\"]}");
			assertThat(d.get("schedules").get(0).get("warnings").valueStream().map(w -> w.get("code").asString())).contains("ARRANGED_MEETING");
		}
	}

	@Test
	void everyOfferingProducesARealTimetableWithTheRightPartsAndCredits() throws Exception {
		for (DemoFixture.Offering o : catalog.offerings()) {
			JsonNode d = generate("{\"courseIds\":[\"" + o.offeringId() + "\"],\"maxResults\":10}");
			JsonNode schedules = d.get("schedules");
			long labs = o.sections().stream().filter(s -> s.component().equals("LAB")).count();
			long lectures = o.sections().stream().filter(x -> x.component().equals("LECTURE")).count();
			assertThat(schedules.size()).as(o.offeringId() + " timetables (one per lecture alternative, times one per lab choice)")
				.isEqualTo((int) (lectures * Math.max(1, labs / lectures)));
			for (JsonNode s : schedules) {
				assertThat(s.get("totalCredits").asInt()).as(o.offeringId() + " credits").isEqualTo(o.credits());
				List<String> parts = s.get("sections").valueStream().map(x -> x.get("component").asString()).toList();
				assertThat(parts.stream().filter("LECTURE"::equals).count()).as(o.offeringId() + " lectures").isEqualTo(1L);
				assertThat(parts.stream().filter("LAB"::equals).count()).as(o.offeringId() + " labs").isEqualTo(labs == 0 ? 0L : 1L);
				assertThat(s.get("warnings").valueStream().map(w -> w.get("code").asString()).collect(Collectors.toSet()))
					.contains("REGISTRATION_ELIGIBILITY_NOT_CONFIRMED");
				assertThat(s.get("status").asString()).isIn("UNVERIFIED", "OVERRIDE_REQUIRED");
			}
		}
	}

	@Test
	void cse007KeepsItsTwoSchedulesAndCombinesWithCse252ForSevenCredits() throws Exception {
		JsonNode alone = generate("{\"courseIds\":[\"CSE-007\"],\"minCredits\":4,\"maxCredits\":4}");
		assertThat(alone.get("schedules")).hasSize(2);
		assertThat(alone.get("schedules").valueStream().map(s -> s.get("sections").valueStream().map(x -> x.get("crn").asString()).toList()).toList())
			.containsExactlyInAnyOrder(List.of("12275", "12638"), List.of("12275", "13132"));
		JsonNode both = generate("{\"courseIds\":[\"CSE-007\",\"CSE-252\"],\"minCredits\":7,\"maxCredits\":7,\"profile\":{\"classStanding\":\"SO\"}}");
		assertThat(both.get("schedules")).hasSize(2);
		assertThat(both.get("schedules").valueStream().allMatch(s -> s.get("totalCredits").asInt() == 7)).isTrue();
	}

	@Test
	void overlappingFixedMeetingsAreNeverScheduledTogether() throws Exception {
		for (List<String> pair : List.of(List.of("CSE-017", "CSE-109"), List.of("CSE-127", "CSE-327"), List.of("CSE-313", "CSE-413"),
				List.of("CSE-367", "CSE-467"), List.of("CSE-398-052", "CSE-498"), List.of("CSE-003", "CSE-007"))) {
			JsonNode d = generate("{\"courseIds\":[\"" + pair.get(0) + "\",\"" + pair.get(1) + "\"]}");
			assertThat(d.get("schedules")).as(pair + " overlap").isEmpty();
		}
	}

	@Test
	void noOfferingIsEverReportedAsEligible() {
		DemoProfile everything = new DemoProfile("SR", "GR", "EN", List.of("CSE"));
		for (DemoFixture.Offering o : catalog.offerings()) {
			var report = DemoEligibility.report(o, everything);
			assertThat(report.status()).as(o.offeringId()).isIn("UNVERIFIED", "OVERRIDE_REQUIRED");
			assertThat(report.warnings()).extracting(DemoWarning::code).contains("REGISTRATION_ELIGIBILITY_NOT_CONFIRMED");
		}
	}

}
