package io.github.michael04.registration.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.michael04.registration.ai.ScheduleFacts.SectionFact;
import io.github.michael04.registration.common.preferences.HardConstraints;
import io.github.michael04.registration.common.preferences.SchedulePreferences;
import io.github.michael04.registration.common.preferences.SoftPreferences;
import io.github.michael04.registration.common.preferences.TimeBlock;
import io.github.michael04.registration.common.preferences.Weighted;
import io.github.michael04.registration.course.CatalogSection.Meeting;

class ScheduleFactsTest {

	private static final List<DayOfWeek> MWF = List.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY);
	private static final List<DayOfWeek> WEEKDAYS = List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);

	private static SectionFact section(String course, String start, String end) {
		return new SectionFact("1", course, course, 3, List.of(new Meeting(MWF, LocalTime.parse(start), LocalTime.parse(end), null)));
	}

	private static SchedulePreferences prefs(SoftPreferences soft) {
		var before10 = new TimeBlock(WEEKDAYS, LocalTime.MIDNIGHT, LocalTime.of(10, 0));
		return new SchedulePreferences(new HardConstraints(List.of("CSE-140", "MATH-021"), 6, 18, List.of(before10)), soft);
	}

	@SuppressWarnings("unchecked")
	@Test
	void reportsTheRealBreakAndAMissedBreakWishInsteadOfClaimingItWasMet() {
		var sections = List.of(section("CSE-140", "10:00", "10:50"), section("MATH-021", "11:35", "12:50"));
		var soft = new SoftPreferences(null, null, null, null, null, new Weighted<>(60, 3));

		Map<String, Object> facts = ScheduleFacts.compute(sections, prefs(soft));

		assertEquals(45, facts.get("shortestBreakMinutes"));
		assertEquals("10:00", facts.get("earliestClassStart"));
		var results = (List<Map<String, Object>>) facts.get("preferenceResults");
		assertEquals(1, results.size());
		assertEquals("minBreakMinutes", results.get(0).get("preference"));
		assertEquals(false, results.get(0).get("satisfied"), "a 45-minute break does not satisfy a 60-minute wish");
		assertTrue(results.get(0).get("detail").toString().contains("45"));
		var hard = (List<Map<String, Object>>) facts.get("hardConstraintResults");
		assertEquals(true, hard.get(0).get("satisfied"), "nothing starts before 10:00");
	}

	@SuppressWarnings("unchecked")
	@Test
	void marksAMetBreakWishAndAViolatedHardRule() {
		var sections = List.of(section("CSE-140", "09:00", "09:50"), section("MATH-021", "11:30", "12:45"));
		var soft = new SoftPreferences(null, new Weighted<>(LocalTime.of(10, 0), 4), null, null, null, new Weighted<>(60, 3));

		Map<String, Object> facts = ScheduleFacts.compute(sections, prefs(soft));

		var results = (List<Map<String, Object>>) facts.get("preferenceResults");
		assertEquals(true, results.stream().filter(r -> r.get("preference").equals("minBreakMinutes")).findFirst().orElseThrow().get("satisfied"));
		assertEquals(false, results.stream().filter(r -> r.get("preference").equals("earliestStart")).findFirst().orElseThrow().get("satisfied"));
		var hard = (List<Map<String, Object>>) facts.get("hardConstraintResults");
		assertFalse((boolean) hard.get(0).get("satisfied"), "a 09:00 class breaks the 'no classes before 10' rule and must be reported as such");
	}
}
