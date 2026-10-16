package io.github.michael04.registration.engine;

import java.util.List;
import java.util.Objects;

/**
 * What happened to one elective (optional course) the student typed in, and how to get it in.
 *
 * @param summary one sentence the UI can show, e.g. "ECO-001 conflicts with CSE-017 in every combination."
 * @param fixes concrete changes that would make it fit, each a sentence; empty when none is needed or found
 * @param alternativeCrns for {@link Status#FITS_WITH_CHANGES}: a schedule of the required courses plus this
 *        elective, best first by score; otherwise empty
 */
public record ElectiveAdvice(String courseId, Status status, String summary, List<String> fixes,
		List<String> alternativeCrns) {

	public enum Status {
		/** It's in suggestion #1. */
		IN_TOP_SCHEDULE,
		/** It's in a lower-ranked suggestion but not #1. */
		IN_OTHER_SUGGESTION,
		/** It fits with the required courses, but only with changes the suggestions didn't make. */
		FITS_WITH_CHANGES,
		/** It can't fit with the required courses under the current constraints. */
		CANNOT_FIT
	}

	public ElectiveAdvice {
		Objects.requireNonNull(courseId, "courseId");
		Objects.requireNonNull(status, "status");
		Objects.requireNonNull(summary, "summary");
		fixes = List.copyOf(Objects.requireNonNull(fixes, "fixes"));
		alternativeCrns = List.copyOf(Objects.requireNonNull(alternativeCrns, "alternativeCrns"));
	}

}
