package io.github.michael04.registration.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import io.github.michael04.registration.common.json.JsonConfiguration;
import io.github.michael04.registration.course.CatalogSection;
import io.github.michael04.registration.course.CourseCatalog;
import io.github.michael04.registration.course.CourseSummary;
import io.github.michael04.registration.course.SectionCatalog;
import io.github.michael04.registration.schedule.integration.SchedulingEngine;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@WebMvcTest(ScheduleController.class)
@Import({ScheduleService.class, EngineMapper.class, SchedulingConfiguration.class, JsonConfiguration.class})
class ScheduleControllerTest {
	static final String TERM = "2027-SPRING";
	static final String VALID = """
		{"term":"2027-SPRING","maxResults":5,"includeClosedSections":false,
		 "preferences":{"hardConstraints":{"requiredCourseIds":["CSE-017","MATH-021"],
		 "minCredits":6,"maxCredits":21,"blockedTimes":[{"days":["TUESDAY"],"start":"15:00","end":"18:00"}]},
		 "softPreferences":{"optionalCourseIds":{"value":["ECO-001"],"weight":2},
		 "earliestStart":{"value":"10:00","weight":3},"latestEnd":{"value":"17:00","weight":1},
		 "daysOff":{"value":["FRIDAY"],"weight":4},"minimizeGaps":{"weight":2}}}}
		""";
	@Autowired MockMvc mvc;
	@Autowired JsonMapper json;
	@MockitoBean CourseCatalog catalog;
	@MockitoBean SectionCatalog sections;
	@MockitoBean SchedulingEngine engine;

	@BeforeEach
	void catalogAndEngine() {
		when(catalog.termExists(TERM)).thenReturn(true);
		when(catalog.findCourse(eq(TERM), anyString()))
			.thenAnswer(invocation -> Optional.of(new CourseSummary(invocation.getArgument(1), "Course title", 3)));
		when(sections.sectionsFor(eq(TERM), any())).thenReturn(List.of(section("1", "CSE-017")));
		when(engine.solve(any(), anyInt())).thenReturn(new SchedulingEngine.Result(List.of(), List.of("No feasible schedules"), false));
	}

	static Stream<Arguments> invalidRequests() {
		var cases = Stream.of(
			Arguments.of("term", "\"\""), Arguments.of("term", "DELETE"),
			Arguments.of("preferences", "null"), Arguments.of("preferences.hardConstraints", "null"),
			Arguments.of("preferences.hardConstraints.requiredCourseIds", "[]"),
			Arguments.of("preferences.hardConstraints.requiredCourseIds", "DELETE"),
			Arguments.of("preferences.hardConstraints.requiredCourseIds", "null"),
			Arguments.of("preferences.hardConstraints.requiredCourseIds", "[null]"),
			Arguments.of("preferences.hardConstraints.requiredCourseIds", "[17]"),
			Arguments.of("preferences.hardConstraints.requiredCourseIds", "[\" \"]"),
			Arguments.of("preferences.hardConstraints.minCredits", "0"),
			Arguments.of("preferences.hardConstraints.minCredits", "-1"),
			Arguments.of("preferences.hardConstraints.minCredits", "22"),
			Arguments.of("preferences.hardConstraints.maxCredits", "22"),
			Arguments.of("preferences.hardConstraints.maxCredits", "0"),
			Arguments.of("preferences.hardConstraints.maxCredits", "5"),
			Arguments.of("maxResults", "0"), Arguments.of("maxResults", "-1"), Arguments.of("maxResults", "11"),
			Arguments.of("maxResults", "1.9"), Arguments.of("maxResults", "\"5\""),
			Arguments.of("maxResults", "\"\""),
			Arguments.of("preferences.hardConstraints.minCredits", "1.9"),
			Arguments.of("preferences.hardConstraints.minCredits", "\"\""),
			Arguments.of("preferences.hardConstraints.maxCredits", "21.9"),
			Arguments.of("includeClosedSections", "\"true\""),
			Arguments.of("preferences.softPreferences.optionalCourseIds.value", optionalIds(11)),
			Arguments.of("preferences.softPreferences.optionalCourseIds.value", "[\"\"]"),
			Arguments.of("preferences.softPreferences.optionalCourseIds.value", "[null]"),
			Arguments.of("preferences.softPreferences.optionalCourseIds.value", "[true]"),
			Arguments.of("preferences.softPreferences.optionalCourseIds.value", "null"),
			Arguments.of("preferences.softPreferences.optionalCourseIds.value", "[" + String.join(",", java.util.Collections.nCopies(11, "\"CSE-017\"")) + "]"),
			Arguments.of("preferences.softPreferences.daysOff.value", "[null]"),
			Arguments.of("preferences.softPreferences.daysOff.value", "[\"FUNDAY\"]"),
			Arguments.of("preferences.softPreferences.earliestStart.value", "\"10:00:30\""),
			Arguments.of("preferences.softPreferences.latestEnd.value", "\"25:00\""),
			Arguments.of("preferences.softPreferences.latestEnd.value", "\"24:00\""),
			Arguments.of("preferences.hardConstraints.blockedTimes", "[null]"),
			Arguments.of("preferences.hardConstraints.blockedTimes", "[{\"days\":[],\"start\":\"15:00\",\"end\":\"18:00\"}]"),
			Arguments.of("preferences.hardConstraints.blockedTimes", "[{\"days\":[\"MONDAY\"],\"start\":\"18:00\",\"end\":\"18:00\"}]"),
			Arguments.of("preferences.hardConstraints.blockedTimes", "[{\"days\":[\"MONDAY\"],\"start\":\"19:00\",\"end\":\"18:00\"}]"),
			Arguments.of("preferences.hardConstraints.blockedTimes", "[{\"days\":[\"MONDAY\"],\"end\":\"18:00\"}]"));
		var weights = Stream.of("optionalCourseIds", "earliestStart", "latestEnd", "daysOff", "minimizeGaps")
			.flatMap(name -> Stream.of("-1", "6", "2.5", "\"\"", "null", "DELETE")
				.map(value -> Arguments.of("preferences.softPreferences." + name + ".weight", value)));
		return Stream.concat(cases, weights);
	}

	@ParameterizedTest(name = "{0} = {1}")
	@MethodSource("invalidRequests")
	void invalidRequestsNeverReachCatalogOrEngine(String path, String value) throws Exception {
		send(changed(VALID, path, value)).andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("about:blank"))
			.andExpect(jsonPath("$.status").value(400))
			.andExpect(header().exists("X-Request-Id"));
		verifyNoInteractions(catalog, sections, engine);
	}

	@Test
	void crossFieldValidationHasStructuredFieldErrors() throws Exception {
		send(changed(VALID, "preferences.hardConstraints.maxCredits", "5"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("preferences.hardConstraints.minCredits"))
			.andExpect(jsonPath("$.errors[0].message").value("minCredits must not exceed maxCredits"));
		verifyNoInteractions(engine);
	}

	@ParameterizedTest
	@CsvSource({"1,1,0", "21,10,5"})
	void inclusiveNumericBoundariesReachEngine(int credits, int maxResults, int weight) throws Exception {
		String body = changed(VALID, "preferences.hardConstraints.minCredits", Integer.toString(credits));
		body = changed(body, "preferences.hardConstraints.maxCredits", Integer.toString(credits));
		body = changed(body, "maxResults", Integer.toString(maxResults));
		for (String name : List.of("optionalCourseIds", "earliestStart", "latestEnd", "daysOff", "minimizeGaps")) {
			body = changed(body, "preferences.softPreferences." + name + ".weight", Integer.toString(weight));
		}
		send(body).andExpect(status().isOk());
		var problem = capturedProblem();
		assertThat(problem.constraints().minCredits()).isEqualTo(credits);
		assertThat(problem.constraints().maxCredits()).isEqualTo(credits);
		assertThat(problem.maxResults()).isEqualTo(maxResults);
		assertThat(problem.soft()).isEqualTo(new SchedulingEngine.SoftWeights(weight, LocalTime.of(10, 0), weight,
			LocalTime.of(17, 0), weight, List.of(DayOfWeek.FRIDAY), weight, weight));
	}

	@Test
	void exactlyTenOptionalCoursesAreAccepted() throws Exception {
		send(changed(VALID, "preferences.softPreferences.optionalCourseIds.value", optionalIds(10)))
			.andExpect(status().isOk());
		assertThat(capturedProblem().courses().stream().filter(course -> !course.required())).hasSize(10);
	}

	@Test
	void deduplicatesBothListsAndPreservesEveryRequiredCourse() throws Exception {
		String body = changed(VALID, "preferences.hardConstraints.requiredCourseIds", "[\"MATH-021\",\"CSE-017\",\"CSE-017\"]");
		body = changed(body, "preferences.softPreferences.optionalCourseIds.value", "[\"CSE-017\",\"ECO-001\",\"ECO-001\"]");
		send(body).andExpect(status().isOk());
		var problem = capturedProblem();
		assertThat(problem.courses()).extracting(SchedulingEngine.Course::courseId)
			.containsExactly("CSE-017", "ECO-001", "MATH-021");
		assertThat(problem.courses()).extracting(SchedulingEngine.Course::required).containsExactly(true, false, true);
		verify(sections).sectionsFor(TERM, List.of("CSE-017", "ECO-001", "MATH-021"));
	}

	@Test
	void omittedOptionalFieldsUseContractDefaults() throws Exception {
		send("""
			{"term":"2027-SPRING","preferences":{"hardConstraints":{"requiredCourseIds":["CSE-017"]}}}
			""").andExpect(status().isOk());
		var problem = capturedProblem();
		assertThat(problem.constraints()).isEqualTo(new SchedulingEngine.Constraints(12, 18, List.of()));
		assertThat(problem.maxResults()).isEqualTo(5);
		assertThat(problem.soft()).isEqualTo(new SchedulingEngine.SoftWeights(0, null, 0, null, 0, List.of(), 0, 0));
	}

	@Test
	void unknownTermReturns422WithoutInvokingEngine() throws Exception {
		when(catalog.termExists(TERM)).thenReturn(false);
		send(VALID).andExpect(status().is(422)).andExpect(jsonPath("$.detail").value("Unknown term"));
		verifyNoInteractions(sections, engine);
	}

	@ParameterizedTest
	@ValueSource(strings = {"CSE-017", "ECO-001"})
	void unknownRequiredOrOptionalCourseReturns422(String courseId) throws Exception {
		when(catalog.findCourse(TERM, courseId)).thenReturn(Optional.empty());
		send(VALID).andExpect(status().is(422));
		verifyNoInteractions(sections, engine);
	}

	@ParameterizedTest
	@ValueSource(ints = {18, 19, 21})
	void mapsCreditsAndScoresWithoutRescoring(int credits) throws Exception {
		var score = new BigDecimal("87.1234567890123456789");
		var breakdown = Map.of("earliestStart", new BigDecimal("0.1234567890123456789"), "daysOff", new BigDecimal("5.00"));
		var result = new SchedulingEngine.Result(List.of(
			new SchedulingEngine.ScoredSchedule(List.of("1"), credits, score, breakdown),
			new SchedulingEngine.ScoredSchedule(List.of("1"), 18, new BigDecimal("99.999"), Map.of())), List.of("Capped"), true);
		when(engine.solve(any(), anyInt())).thenReturn(result);
		var response = send(VALID).andExpect(status().isOk())
			.andExpect(jsonPath("$.term").value(TERM))
			.andExpect(jsonPath("$.schedules[0].rank").value(1))
			.andExpect(jsonPath("$.schedules[0].totalCredits").value(credits))
			.andExpect(jsonPath("$.schedules[0].overload").value(credits > 18))
			.andExpect(jsonPath("$.schedules[1].rank").value(2))
			.andExpect(jsonPath("$.schedules[1].score").value(99.999))
			.andExpect(jsonPath("$.schedules[1].overload").value(false))
			.andExpect(jsonPath("$.schedules[0].sections[0].crn").value("1"))
			.andExpect(jsonPath("$.schedules[0].sections[0].title").value("Course title"))
			.andExpect(jsonPath("$.schedules[0].sections[0].meetings[0].start").value("10:45"))
			.andExpect(jsonPath("$.schedules[0].sections[0].meetings[0].location").value("Room A"))
			.andExpect(jsonPath("$.diagnostics[0]").value("Capped"))
			.andExpect(jsonPath("$.searchLimitReached").value(true)).andReturn().getResponse().getContentAsString();
		// Check raw decimal text, avoiding the JSON test helper's conversion to double.
		assertThat(response).contains("87.1234567890123456789", "0.1234567890123456789");
		verify(engine).solve(any(), eq(200000));
	}

	@Test
	void noFeasibleSchedulesIsA200Result() throws Exception {
		send(VALID).andExpect(status().isOk()).andExpect(jsonPath("$.schedules").isEmpty())
			.andExpect(jsonPath("$.diagnostics[0]").value("No feasible schedules"))
			.andExpect(jsonPath("$.searchLimitReached").value(false));
	}

	@Test
	void unexpectedFailureUsesSharedSafe500() throws Exception {
		when(engine.solve(any(), anyInt())).thenThrow(new IllegalStateException("database-password-secret"));
		send(VALID).andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
			.andExpect(content().string(not(containsString("database-password-secret"))))
			.andExpect(jsonPath("$.trace").doesNotExist());
	}

	private SchedulingEngine.Problem capturedProblem() {
		var captor = ArgumentCaptor.forClass(SchedulingEngine.Problem.class);
		verify(engine).solve(captor.capture(), eq(200000));
		return captor.getValue();
	}

	private ResultActions send(String body) throws Exception {
		return mvc.perform(post("/api/schedules/generate").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private String changed(String original, String path, String value) {
		ObjectNode root = (ObjectNode) json.readTree(original);
		ObjectNode node = root;
		String[] parts = path.split("\\.");
		for (int i = 0; i < parts.length - 1; i++) {
			node = (ObjectNode) node.get(parts[i]);
		}
		if ("DELETE".equals(value)) {
			node.remove(parts[parts.length - 1]);
		} else {
			node.set(parts[parts.length - 1], json.readTree(value));
		}
		return json.writeValueAsString(root);
	}

	private static String optionalIds(int count) {
		return "[" + IntStream.range(0, count).mapToObj(i -> "\"OPT-" + i + "\"")
			.collect(java.util.stream.Collectors.joining(",")) + "]";
	}

	static CatalogSection section(String crn, String courseId) {
		return new CatalogSection(TERM, courseId, crn, "010", 3, "LECTURE", null, CatalogSection.Status.OPEN,
			List.of(new CatalogSection.Meeting(List.of(DayOfWeek.MONDAY), LocalTime.of(10, 45), LocalTime.of(11, 35), "Room A")));
	}
}
