package io.github.michael04.registration.course;

import java.util.List;

/**
 * The yearly rating history of one instructor who teaches a section of the course this term.
 *
 * @param name the instructor as Banner lists them ("Last, First")
 * @param years oldest first; only years that have ratings
 */
public record InstructorRatingHistory(String name, List<YearRating> years) {

	public InstructorRatingHistory {
		years = years == null ? List.of() : List.copyOf(years);
	}

}
