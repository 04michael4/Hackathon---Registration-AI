package io.github.michael04.registration.schedule;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import io.github.michael04.registration.schedule.GenerateScheduleResponse.SectionResponse;

/** Electives that fit the given schedule as it is, best first. */
public record SuggestElectivesResponse(String term, List<Suggestion> suggestions) {

	/**
	 * @param sections the sections to add (a lecture plus one linked section per component)
	 * @param totalCredits, overload, score, scoreBreakdown: the schedule after adding it; the score counts time
	 *        preferences (earliest start, latest end, days off, gaps)
	 */
	public record Suggestion(String courseId, String title, List<SectionResponse> sections, int totalCredits,
			boolean overload, BigDecimal score, Map<String, BigDecimal> scoreBreakdown) {}
}
