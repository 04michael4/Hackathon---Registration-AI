package io.github.michael04.registration.course;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

/**
 * Read model, not a persistence entity.
 *
 * <p>{@code lectureLink} is the CRN of the lecture a lab or recitation must be taken with, and
 * {@code null} for a lecture or standalone section. It comes from the catalog's lecture-lab relation
 * and is never inferred. A blank or self-referencing link is malformed and fails the request. A lecture with linked sections is always scheduled with exactly one of them
 * per {@code componentType}.
 */
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
