package io.github.michael04.registration.engine;

import static java.time.DayOfWeek.FRIDAY;
import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.WEDNESDAY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class MeetingTest {

	private static final LocalTime NINE = LocalTime.of(9, 0);
	private static final LocalTime NINE_FIFTY = LocalTime.of(9, 50);

	@Test
	void daysIterateMondayToSundayRegardlessOfInputOrder() {
		Set<DayOfWeek> days = new LinkedHashSet<>(List.of(FRIDAY, MONDAY, WEDNESDAY));

		Meeting meeting = new Meeting(days, NINE, NINE_FIFTY);

		assertEquals(List.of(MONDAY, WEDNESDAY, FRIDAY), new ArrayList<>(meeting.days()));
	}

	@Test
	void meetingsWithSameDaysInDifferentOrderAreEqual() {
		Meeting a = new Meeting(new LinkedHashSet<>(List.of(FRIDAY, MONDAY)), NINE, NINE_FIFTY);
		Meeting b = new Meeting(Set.of(MONDAY, FRIDAY), NINE, NINE_FIFTY);

		assertEquals(a, b);
	}

	@Test
	void daysAreUnmodifiable() {
		Meeting meeting = new Meeting(Set.of(MONDAY), NINE, NINE_FIFTY);

		assertThrows(UnsupportedOperationException.class, () -> meeting.days().add(FRIDAY));
	}

	@Test
	void rejectsEmptyDays() {
		assertThrows(IllegalArgumentException.class, () -> new Meeting(Set.of(), NINE, NINE_FIFTY));
	}

	@Test
	void rejectsStartNotBeforeEnd() {
		assertThrows(IllegalArgumentException.class, () -> new Meeting(Set.of(MONDAY), NINE, NINE));
		assertThrows(IllegalArgumentException.class, () -> new Meeting(Set.of(MONDAY), NINE_FIFTY, NINE));
	}

	@Test
	void rejectsNulls() {
		assertThrows(NullPointerException.class, () -> new Meeting(null, NINE, NINE_FIFTY));
		assertThrows(NullPointerException.class, () -> new Meeting(Set.of(MONDAY), null, NINE_FIFTY));
		assertThrows(NullPointerException.class, () -> new Meeting(Set.of(MONDAY), NINE, null));
	}

}
