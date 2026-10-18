package io.github.michael04.registration.course;

import java.util.List;
import java.util.Map;

/**
 * Predicted ratings and instructors for the served courses. Supplementary: when the data is missing or unavailable the catalog returns
 * empty results and the course endpoints still work.
 */
public interface RatingCatalog {

	/** Predicted rating per course ID, only for courses that have one. */
	Map<String, PredictedRating> courseRatings(String term, List<String> courseIds);

	/** The instructors of a course's sections by CRN, ordered by name, with their predictions. */
	Map<String, List<InstructorRating>> instructorsByCrn(String term, String courseId);

}
