package io.github.michael04.registration.engine;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Complete input to {@link ScheduleSolver#solve}. The order of {@code courses} and of each
 * course's sections does not affect the result.
 *
 * <p>Course IDs must be unique, and CRNs must be unique across all courses.
 */
public record ScheduleProblem(List<CourseOption> courses, Constraints constraints, SoftWeights soft,
		int maxResults) {

	public ScheduleProblem {
		courses = List.copyOf(Objects.requireNonNull(courses, "courses"));
		Objects.requireNonNull(constraints, "constraints");
		Objects.requireNonNull(soft, "soft");
		if (maxResults < 1) {
			throw new IllegalArgumentException("maxResults must be at least 1: " + maxResults);
		}
		// Used only for membership checks, never iterated.
		Set<String> courseIds = new HashSet<>();
		Set<String> crns = new HashSet<>();
		for (CourseOption course : courses) {
			if (!courseIds.add(course.courseId())) {
				throw new IllegalArgumentException("duplicate courseId: " + course.courseId());
			}
			for (SectionOption section : course.sections()) {
				if (!crns.add(section.crn())) {
					throw new IllegalArgumentException("duplicate crn: " + section.crn());
				}
			}
		}
	}

}
