package io.github.michael04.registration.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.github.michael04.registration.common.preferences.TimeBlock;
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
import tools.jackson.databind.json.JsonMapper;

/** The latest-start rule travels from the request through the mapper and engine adapter into the real solver. */
class ScheduleExplanationLatestStartTest {

	private static final String TERM = "2027-SPRING";
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final List<DayOfWeek> WEEKDAYS = List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);
	// MATH-021 lecture 210 (real times): starts 09:20, ends 10:35
	private static final CatalogSection LECTURE = new CatalogSection(TERM, "MATH-021", "11181", "210", 4, "LECTURE", null, CatalogSection.Status.OPEN,
			List.of(new Meeting(List.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), LocalTime.of(9, 20), LocalTime.of(10, 35), null)));

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> provider(T value) {
		ObjectProvider<T> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(value);
		return provider;
	}

	private static String outcome(HardConstraints hard) {
		var captured = new AtomicReference<String>();
		CourseCatalog catalog = new CourseCatalog() {
			public boolean termExists(String term) { return TERM.equals(term); }
			public Optional<CourseSummary> findCourse(String term, String id) { return Optional.of(new CourseSummary(id, "Calculus I", 4)); }
			public List<CourseSummary> listCourses(String term) { return List.of(); }
		};
		SectionCatalog sections = (term, ids) -> List.of(LECTURE);
		SectionLookupCatalog lookup = (term, crns) -> List.of(LECTURE);
		var service = new ScheduleExplanationService(provider(catalog), provider(sections), provider(lookup), new NativeSchedulingEngine(), new EngineMapper(),
				Validation.buildDefaultValidatorFactory().getValidator(), JSON, facts -> { captured.set(facts); return new ExplainScheduleResponse("ok", List.of()); },
				new SchedulingProperties(200_000));
		service.explain(new ExplainScheduleRequest(TERM, List.of(), new SchedulePreferences(hard, null)));
		return JSON.readTree(captured.get()).get("outcome").asString();
	}

	@Test
	void startBeforeTenAcceptsALectureThatStartsAt0920AndEndsAt1035() {
		assertEquals("VALID_SCHEDULE", outcome(new HardConstraints(List.of("MATH-021"), 4, 18, List.of(), LocalTime.of(10, 0))));
	}

	@Test
	void endByTenRejectsTheSameLecture() {
		var endByTen = new TimeBlock(WEEKDAYS, LocalTime.of(10, 0), LocalTime.of(23, 59));
		assertEquals("NO_VALID_SCHEDULE", outcome(new HardConstraints(List.of("MATH-021"), 4, 18, List.of(endByTen), null)));
	}

	@Test
	void noClassesBeforeTenRejectsTheSameLecture() {
		var morning = new TimeBlock(WEEKDAYS, LocalTime.MIDNIGHT, LocalTime.of(10, 0));
		assertEquals("NO_VALID_SCHEDULE", outcome(new HardConstraints(List.of("MATH-021"), 4, 18, List.of(morning), null)));
	}

	@Test
	void startBeforeNineRejectsIt_andTheStartRuleSurvivesInTheJson() throws Exception {
		assertEquals("NO_VALID_SCHEDULE", outcome(new HardConstraints(List.of("MATH-021"), 4, 18, List.of(), LocalTime.of(9, 0))));
		var json = JSON.writeValueAsString(new HardConstraints(List.of("MATH-021"), 4, 18, List.of(), LocalTime.of(10, 0)));
		assertTrue(json.contains("latestStart"), json);
		assertTrue(!JSON.writeValueAsString(new HardConstraints(List.of("MATH-021"), 4, 18, List.of())).contains("latestStart"), "omitted when unset, so existing responses are unchanged");
		assertEquals(LocalTime.of(10, 0), JSON.readValue("{\"requiredCourseIds\":[\"A-1\"],\"minCredits\":1,\"maxCredits\":18,\"blockedTimes\":[],\"latestStart\":\"10:00\"}", HardConstraints.class).latestStart());
	}
}
