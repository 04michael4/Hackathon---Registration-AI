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
	record Constraints(int minCredits, int maxCredits, List<Meeting> blockedTimes, LocalTime latestStart) {
		/** Without a latest-start rule. */
		public Constraints(int minCredits, int maxCredits, List<Meeting> blockedTimes) {
			this(minCredits, maxCredits, blockedTimes, null);
		}
	}
	record SoftWeights(int optionalCourseWeight, LocalTime earliestStart, int earliestStartWeight,
			LocalTime latestEnd, int latestEndWeight, List<DayOfWeek> daysOff, int daysOffWeight, int gapsWeight,
			int minBreakMinutes, int minBreakWeight) {
		/** Without a minimum-break wish. */
		public SoftWeights(int optionalCourseWeight, LocalTime earliestStart, int earliestStartWeight,
				LocalTime latestEnd, int latestEndWeight, List<DayOfWeek> daysOff, int daysOffWeight, int gapsWeight) {
			this(optionalCourseWeight, earliestStart, earliestStartWeight, latestEnd, latestEndWeight, daysOff,
					daysOffWeight, gapsWeight, 0, 0);
		}
	}
	/** CRNs refer to the exact prepared input snapshot. No scores or missing breakdowns are synthesized. */
	record ScoredSchedule(List<String> sectionCrns, int totalCredits, BigDecimal score,
			Map<String, BigDecimal> scoreBreakdown) {}
	/** {@code electiveAdvice}: one entry per elective in the problem (see {@link ElectiveAdvice}). */
	record Result(List<ScoredSchedule> schedules, List<String> diagnostics, boolean searchLimitReached,
			List<ElectiveAdvice> electiveAdvice) {
		public Result(List<ScoredSchedule> schedules, List<String> diagnostics, boolean searchLimitReached) {
			this(schedules, diagnostics, searchLimitReached, List.of());
		}
	}

	/** Why a typed elective did or didn't make the suggestions, and how to get it in. */
	record ElectiveAdvice(String courseId, String status, String summary, List<String> fixes,
			List<String> alternativeCrns) {}

	/** A hand-built (manual mode) selection checked against the hard rules, scored like generated schedules. */
	record Check(List<String> sectionCrns, int totalCredits, BigDecimal score, Map<String, BigDecimal> scoreBreakdown,
			boolean complete, List<Issue> issues, List<Candidate> candidates) {}
	/** {@code severity} is CONFLICT (something placed breaks a rule) or INCOMPLETE (something is missing). */
	record Issue(String type, String severity, List<String> crns, String message) {}
	/** One unplaced section: whether it would fit, and whether it completes a placed lecture (e.g. its lab). */
	record Candidate(String crn, String courseId, boolean fits, boolean completesLecture, List<String> reasons) {}

	/** An elective that fits a schedule as it is, with the sections to add. */
	record ElectiveFit(String courseId, List<String> crns, int totalCredits, BigDecimal score,
			Map<String, BigDecimal> scoreBreakdown) {}

	/** Manual mode: check the placed {@code crns} against {@code problem}'s courses and constraints. */
	default Check check(Problem problem, List<String> crns) {
		throw new UnsupportedOperationException("check is not supported by this engine");
	}

	/** Electives from {@code candidates} that fit the schedule {@code scheduleCrns}, best first. */
	default List<ElectiveFit> suggestElectives(Problem problem, List<String> scheduleCrns, List<Course> candidates,
			int limit) {
		throw new UnsupportedOperationException("suggestElectives is not supported by this engine");
	}
}
