package io.github.michael04.registration.course;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

/**
 * One course with all its sections for a term ({@code GET /api/courses/{courseId}}).
 *
 * @param description {@code null} when the catalog has none
 * @param predictedRating the rating predicted for the term from earlier terms' opinions, or {@code null} when none exists
 * @param conditions what the application has not verified about taking this course; empty when the catalog has none (never means "eligible")
 */
public record CourseDetailResponse(String courseId, String subject, String number, String title, int credits,
		String description, List<Section> sections, List<CourseCondition> conditions, PredictedRating predictedRating) {

	public CourseDetailResponse {
		conditions = conditions == null ? List.of() : List.copyOf(conditions);
	}

	public CourseDetailResponse(String courseId, String subject, String number, String title, int credits, String description,
			List<Section> sections) {
		this(courseId, subject, number, title, credits, description, sections, List.of(), null);
	}

	/**
	 * @param componentType e.g. {@code LECTURE}, {@code LAB}, {@code RECITATION}
	 * @param lectureLink the CRN of the lecture this lab or recitation must be taken with; {@code null} for a lecture
	 * @param instructors empty when the catalog has no instructor data
	 * @param instructorRatings the same instructors with what is predicted for each this term; empty without ratings data
	 */
	public record Section(String crn, String sectionCode, String componentType, String lectureLink, int credits,
			List<String> instructors, CatalogSection.Status status, List<Meeting> meetings, List<InstructorRating> instructorRatings) {

		public Section {
			instructorRatings = instructorRatings == null ? List.of() : List.copyOf(instructorRatings);
		}

		public Section(String crn, String sectionCode, String componentType, String lectureLink, int credits,
				List<String> instructors, CatalogSection.Status status, List<Meeting> meetings) {
			this(crn, sectionCode, componentType, lectureLink, credits, instructors, status, meetings, List.of());
		}

	}

	public record Meeting(List<DayOfWeek> days, LocalTime start, LocalTime end, String location) {
	}

}
