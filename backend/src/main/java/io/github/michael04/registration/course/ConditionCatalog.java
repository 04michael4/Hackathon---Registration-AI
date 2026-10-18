package io.github.michael04.registration.course;

import java.util.List;

/** The conditions that travel with a served course. Empty when the active catalog has none (the verified snapshot has no conditions). */
public interface ConditionCatalog {

	List<CourseCondition> conditionsFor(String term, String courseId);

}
