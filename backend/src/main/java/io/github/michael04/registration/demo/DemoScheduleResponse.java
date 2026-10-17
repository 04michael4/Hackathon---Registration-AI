package io.github.michael04.registration.demo;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

/**
 * Response of the demo endpoint: the engine's timetables plus, for every course involved, what is and is not verified.
 * {@code status} is OVERRIDE_REQUIRED when a known restriction is failed, otherwise UNVERIFIED. It is never "eligible".
 */
public record DemoScheduleResponse(String term, String caution, List<String> selectedCourseIds, List<Schedule> schedules,
		List<String> diagnostics, boolean searchLimitReached, List<DemoEligibility.CourseReport> courses) {

	public record Schedule(int rank, BigDecimal score, int totalCredits, boolean overload, String status,
			List<SectionView> sections, List<DemoWarning> warnings) {
	}

	public record SectionView(String crn, String courseId, String title, String topic, String sectionCode, String component,
			int credits, List<MeetingView> meetings) {
	}

	public record MeetingView(List<DayOfWeek> days, LocalTime start, LocalTime end, String location) {
	}

}
