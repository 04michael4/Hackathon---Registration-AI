package io.github.michael04.registration.schedule;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import io.github.michael04.registration.schedule.GenerateScheduleResponse.SectionResponse;

/**
 * Result of a manual-mode check.
 *
 * @param complete no conflicts and nothing missing: a usable schedule
 * @param issues CONFLICT issues (show red) first, then INCOMPLETE ones (show a prompt)
 * @param candidates one per unplaced section of every course in play: grey out where {@code fits} is false
 *        and show {@code reasons}; highlight where {@code completesLecture} is true
 */
public record CheckScheduleResponse(String term, List<SectionResponse> sections, int totalCredits, boolean overload,
		BigDecimal score, Map<String, BigDecimal> scoreBreakdown, boolean complete, List<IssueResponse> issues,
		List<CandidateResponse> candidates) {

	public record IssueResponse(String type, String severity, List<String> crns, String message) {}

	public record CandidateResponse(String crn, String courseId, boolean fits, boolean completesLecture,
			List<String> reasons) {}
}
