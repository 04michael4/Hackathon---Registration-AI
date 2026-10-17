package io.github.michael04.registration.course;

import java.util.List;
import java.util.Optional;

/** Rich catalog reads for the Course API; separate from the AI's existing summary interface. */
public interface CourseReadCatalog {
	List<CourseResponses.TermResponse> terms();
	boolean termExists(String term);
	CourseResponses.PageResponse search(String term, String query, String subject, int page, int size);
	Optional<CourseResponses.DetailResponse> detail(String term, String courseId);
}
