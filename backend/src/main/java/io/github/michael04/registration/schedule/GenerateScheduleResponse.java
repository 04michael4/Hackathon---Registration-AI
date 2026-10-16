package io.github.michael04.registration.schedule;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

public record GenerateScheduleResponse(String term, List<ScheduleResponse> schedules,
		List<String> diagnostics, boolean searchLimitReached) {

	public record ScheduleResponse(int rank, BigDecimal score, int totalCredits, boolean overload,
			List<SectionResponse> sections, Map<String, BigDecimal> scoreBreakdown) {}
	public record SectionResponse(String crn, String courseId, String sectionCode, String title,
			int credits, List<MeetingResponse> meetings) {}
	public record MeetingResponse(List<DayOfWeek> days, LocalTime start, LocalTime end, String location) {}
}
