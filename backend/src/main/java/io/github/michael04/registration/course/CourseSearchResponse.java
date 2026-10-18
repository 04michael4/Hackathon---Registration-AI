package io.github.michael04.registration.course;

import java.util.List;

/** A page of course search results ({@code GET /api/courses}). */
public record CourseSearchResponse(List<Item> items, int page, int size, long totalItems, int totalPages) {

	/**
	 * @param sectionCount lectures and standalone sections offered this term (linked labs and recitations
	 *        are not counted separately)
	 * @param predictedRating the rating predicted for the term, or {@code null} when none exists (new instructors, no data)
	 */
	public record Item(String courseId, String subject, String number, String title, int credits, int sectionCount,
			PredictedRating predictedRating) {

		public Item(String courseId, String subject, String number, String title, int credits, int sectionCount) {
			this(courseId, subject, number, title, credits, sectionCount, null);
		}

	}

}
