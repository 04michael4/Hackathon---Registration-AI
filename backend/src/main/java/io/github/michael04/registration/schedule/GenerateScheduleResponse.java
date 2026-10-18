package io.github.michael04.registration.schedule;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

public record GenerateScheduleResponse(String term, List<ScheduleResponse> schedules,
		List<String> diagnostics, boolean searchLimitReached, List<ElectiveAdviceResponse> electiveAdvice) {

	public GenerateScheduleResponse(String term, List<ScheduleResponse> schedules, List<String> diagnostics,
			boolean searchLimitReached) {
		this(term, schedules, diagnostics, searchLimitReached, List.of());
	}

	/**
	 * For each elective the student typed in: whether it made the suggestions and, if not, why and how to
	 * get it in. {@code status} is IN_TOP_SCHEDULE, IN_OTHER_SUGGESTION, FITS_WITH_CHANGES or CANNOT_FIT.
	 */
	public record ElectiveAdviceResponse(String courseId, String status, String summary, List<String> fixes,
			List<String> alternativeCrns) {}

	public record ScheduleResponse(int rank, BigDecimal score, int totalCredits, boolean overload,
			List<SectionResponse> sections, Map<String, BigDecimal> scoreBreakdown) {}
	public record SectionResponse(String crn, String courseId, String sectionCode, String title,
			int credits, List<MeetingResponse> meetings) {}
	public record MeetingResponse(List<DayOfWeek> days, LocalTime start, LocalTime end, String location) {}
}
