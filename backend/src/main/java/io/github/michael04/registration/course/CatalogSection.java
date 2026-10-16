package io.github.michael04.registration.course;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

/** Read model, not a persistence entity. lectureLink is opaque catalog metadata, never inferred. */
public record CatalogSection(String term, String courseId, String crn, String sectionCode,
		int credits, String componentType, String lectureLink, Status status, List<Meeting> meetings) {

	public CatalogSection {
		meetings = meetings == null ? List.of() : List.copyOf(meetings);
	}

	public enum Status { OPEN, CLOSED, UNKNOWN }

	/** Unscheduled/TBA sections have no meetings, not a meeting with null times. */
	public record Meeting(List<DayOfWeek> days, LocalTime start, LocalTime end, String location) {
	}
}
