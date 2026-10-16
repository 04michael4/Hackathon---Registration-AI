package io.github.michael04.registration.schedule.integration;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * API-owned adapter boundary pending Sunny's actual ScheduleSolver implementation.
 * No production implementation is registered until that solver exists. An adapter must map
 * to the solver's native records without implementing search or scoring here.
 */
public interface SchedulingEngine {
	Result solve(Problem problem, int nodeCap);

	record Problem(List<Course> courses, Constraints constraints, SoftWeights soft, int maxResults) {}
	record Course(String courseId, boolean required, List<Section> sections) {}
	record Section(String crn, String courseId, int credits, String componentType,
			String lectureLink, List<Meeting> meetings) {}
	record Meeting(List<DayOfWeek> days, LocalTime start, LocalTime end) {}
	record Constraints(int minCredits, int maxCredits, List<Meeting> blockedTimes) {}
	record SoftWeights(int optionalCourseWeight, LocalTime earliestStart, int earliestStartWeight,
			LocalTime latestEnd, int latestEndWeight, List<DayOfWeek> daysOff, int daysOffWeight, int gapsWeight) {}
	/** CRNs refer to the exact prepared input snapshot. No scores or missing breakdowns are synthesized. */
	record ScoredSchedule(List<String> sectionCrns, int totalCredits, BigDecimal score,
			Map<String, BigDecimal> scoreBreakdown) {}
	record Result(List<ScoredSchedule> schedules, List<String> diagnostics, boolean searchLimitReached) {}
}
