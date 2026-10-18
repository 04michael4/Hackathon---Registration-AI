package io.github.michael04.registration.course;

import java.util.List;
import java.util.Map;

/** Display metadata only; CourseCatalog and SectionCatalog remain authoritative for eligibility and sections. */
public interface CourseMetadataCatalog {

	List<CourseResponses.TermResponse> terms();

	Detail detail(String term, String courseId);

	record Detail(String description, Map<String, List<String>> instructorsByCrn) {}
}
