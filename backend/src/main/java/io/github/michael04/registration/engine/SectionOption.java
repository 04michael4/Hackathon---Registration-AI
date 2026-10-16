package io.github.michael04.registration.engine;

import java.util.List;
import java.util.Objects;

/**
 * One registrable section of a course.
 *
 * <p>A <b>primary</b> section ({@code parentCrn == null}) is a lecture or a standalone section.
 * A <b>linked</b> section (lab, recitation, ...) names its lecture in {@code parentCrn}. If a
 * lecture has linked sections, a schedule that takes the lecture must also take exactly one of
 * its linked sections of each {@code component} type. The engine enforces this.
 *
 * <p>Credits of a lecture and its linked sections are added together, so a course's credits
 * usually go on the lecture and 0 on its labs. An empty {@code meetings} list is allowed (e.g. a
 * section with no scheduled time); such a section conflicts with nothing.
 */
public record SectionOption(String crn, String courseId, String component, String parentCrn, int credits,
		List<Meeting> meetings) {

	public SectionOption {
		requireNonBlank(crn, "crn");
		requireNonBlank(courseId, "courseId");
		requireNonBlank(component, "component");
		if (parentCrn != null && (parentCrn.isBlank() || parentCrn.equals(crn))) {
			throw new IllegalArgumentException("parentCrn must be null or another section's crn: " + parentCrn);
		}
		if (credits < 0) {
			throw new IllegalArgumentException("credits must not be negative: " + credits);
		}
		meetings = List.copyOf(Objects.requireNonNull(meetings, "meetings"));
	}

	/** {@code true} for a lecture or standalone section, {@code false} for a lab, recitation, etc. */
	public boolean isPrimary() {
		return parentCrn == null;
	}

	static void requireNonBlank(String value, String name) {
		Objects.requireNonNull(value, name);
		if (value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
	}

}
