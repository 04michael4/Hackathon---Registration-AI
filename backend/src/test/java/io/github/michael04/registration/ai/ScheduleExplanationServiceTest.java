package io.github.michael04.registration.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import io.github.michael04.registration.common.preferences.HardConstraints;
import io.github.michael04.registration.common.preferences.SchedulePreferences;
import io.github.michael04.registration.common.preferences.SoftPreferences;
import io.github.michael04.registration.common.preferences.TimeBlock;
import io.github.michael04.registration.common.preferences.Weighted;
import io.github.michael04.registration.course.CatalogSection;
import io.github.michael04.registration.course.CatalogSection.Meeting;
import io.github.michael04.registration.course.CourseCatalog;
import io.github.michael04.registration.course.CourseSummary;
import io.github.michael04.registration.course.SectionCatalog;
import io.github.michael04.registration.course.SectionLookupCatalog;
import io.github.michael04.registration.schedule.EngineMapper;
import io.github.michael04.registration.schedule.SchedulingProperties;
import io.github.michael04.registration.schedule.integration.NativeSchedulingEngine;
import jakarta.validation.Validation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The explanation facts come from the real engine and catalog data; Claude (a fake here) only phrases them. */
class ScheduleExplanationServiceTest {

	private static final String TERM = "2027-SPRING";
	private static final List<DayOfWeek> MWF = List.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY);
	private static final List<DayOfWeek> WEEKDAYS = List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);
	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static CatalogSection section(String course, String crn, String start, String end) {
		return new CatalogSection(TERM, course, crn, "010", 3, "LECTURE", null, CatalogSection.Status.OPEN,
				List.of(new Meeting(MWF, LocalTime.parse(start), LocalTime.parse(end), null)));
	}

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> provider(T value) {
		ObjectProvider<T> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(value);
		return provider;
	}

	private static ScheduleExplanationService service(List<CatalogSection> all, AtomicReference<String> captured) {
		CourseCatalog catalog = new CourseCatalog() {
			public boolean termExists(String term) { return TERM.equals(term); }
			public Optional<CourseSummary> findCourse(String term, String id) { return Optional.of(new CourseSummary(id, id + " title", 3)); }
			public List<CourseSummary> listCourses(String term) { return List.of(); }
		};
		SectionCatalog sections = (term, ids) -> all.stream().filter(s -> ids.contains(s.courseId())).toList();
		SectionLookupCatalog lookup = (term, crns) -> all.stream().filter(s -> crns.contains(s.crn())).toList();
		ScheduleExplainer explainer = facts -> { captured.set(facts); return new ExplainScheduleResponse("ok", List.of("h")); };
		return new ScheduleExplanationService(provider(catalog), provider(sections), provider(lookup), new NativeSchedulingEngine(),
				new EngineMapper(), Validation.buildDefaultValidatorFactory().getValidator(), JSON, explainer, new SchedulingProperties(200_000));
	}

	private static SchedulePreferences prefs(List<String> required, int minCredits, SoftPreferences soft) {
		var before10 = new TimeBlock(WEEKDAYS, LocalTime.MIDNIGHT, LocalTime.of(10, 0));
		return new SchedulePreferences(new HardConstraints(required, minCredits, 18, List.of(before10)), soft);
	}

	@Test
	void withNoCrnsTheEngineSearchesAndTheFactsHoldRealBreakNumbers() {
		var captured = new AtomicReference<String>();
		var all = List.of(section("CSE-140", "11", "10:00", "10:50"), section("MATH-021", "21", "11:35", "12:50"), section("MATH-021", "22", "13:30", "14:45"));
		var soft = new SoftPreferences(null, null, null, null, null, new Weighted<>(60, 5));

		service(all, captured).explain(new ExplainScheduleRequest(TERM, List.of(), prefs(List.of("CSE-140", "MATH-021"), 6, soft)));

		JsonNode facts = JSON.readTree(captured.get());
		assertEquals("VALID_SCHEDULE", facts.get("outcome").asString());
		var computed = facts.get("computed");
		assertEquals(160, computed.get("shortestBreakMinutes").asInt(), "the engine chose the section that gives the longer break");
		assertTrue(computed.get("preferenceResults").get(0).get("satisfied").asBoolean());
		assertEquals("10:00", computed.get("earliestClassStart").asString().substring(0, 5));
	}

	@Test
	void whenNoScheduleSatisfiesAHardRuleTheFactsSayWhyAndOfferOnlyVerifiedChanges() {
		var captured = new AtomicReference<String>();
		var all = List.of(section("CSE-140", "1", "09:00", "09:50"));

		service(all, captured).explain(new ExplainScheduleRequest(TERM, List.of(), prefs(List.of("CSE-140"), 3, null)));

		JsonNode facts = JSON.readTree(captured.get());
		assertEquals("NO_VALID_SCHEDULE", facts.get("outcome").asString());
		assertFalse(facts.has("sections"), "no schedule is presented as if it were valid");
		assertTrue(facts.get("changesVerifiedByEngine").toString().contains("blocked time is dropped"));
	}

	@Test
	void aNamedScheduleIsStillExplainedWithItsOwnFacts() {
		var captured = new AtomicReference<String>();
		var all = List.of(section("CSE-140", "11", "10:00", "10:50"));

		service(all, captured).explain(new ExplainScheduleRequest(TERM, List.of("11"), prefs(List.of("CSE-140"), 3, null)));

		assertEquals("VALID_SCHEDULE", JSON.readTree(captured.get()).get("outcome").asString());
	}
}
