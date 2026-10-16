package io.github.michael04.registration.engine;

import java.util.List;
import java.util.Objects;

/**
 * Whether one section that isn't placed yet could be added to the current manual selection.
 *
 * @param fits {@code true} if adding it breaks no rule; the UI greys out sections where it's {@code false}
 * @param completesLecture {@code true} for a fitting lab or recitation whose lecture is placed and still
 *        needs that component; the UI highlights these to prompt the student
 * @param reasons why it doesn't fit, as sentences the UI can show; empty when it fits
 */
public record SectionFit(String crn, String courseId, boolean fits, boolean completesLecture, List<String> reasons) {

	public SectionFit {
		Objects.requireNonNull(crn, "crn");
		Objects.requireNonNull(courseId, "courseId");
		reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
	}

}
