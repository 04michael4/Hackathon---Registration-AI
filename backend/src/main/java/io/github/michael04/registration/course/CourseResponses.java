package io.github.michael04.registration.course;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** Public response shapes from endpoints 2–4 of docs/api-contract.md. */
public final class CourseResponses {
	private CourseResponses() {}

	public record TermResponse(String term, String name, LocalDate catalogSnapshotDate) {}
	public record TermsResponse(List<TermResponse> items) {}
	public record SummaryResponse(String courseId, String subject, String number, String title,
			BigDecimal credits, int sectionCount) {}
	public record PageResponse(List<SummaryResponse> items, int page, int size, long totalItems, long totalPages) {}
	public record DetailResponse(String courseId, String subject, String number, String title,
			BigDecimal credits, String description, List<SectionResponse> sections) {}
	public record SectionResponse(String crn, String sectionCode, List<String> instructors,
			CatalogSection.Status status, List<MeetingResponse> meetings) {}
	public record MeetingResponse(List<DayOfWeek> days, LocalTime start, LocalTime end, String location) {}
}
