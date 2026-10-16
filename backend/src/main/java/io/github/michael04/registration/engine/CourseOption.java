package io.github.michael04.registration.engine;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A course the student wants, with all of its sections: lectures, standalone sections, and the
 * labs or recitations linked to them (see {@link SectionOption}).
 *
 * <p>A required course must appear exactly once in every returned schedule; an optional one
 * may be left out (at a soft-preference cost). The caller filters out sections it doesn't want
 * considered, such as closed ones, before building this. A linked section whose lecture was
 * filtered out is simply never chosen. An empty {@code sections} list is allowed; for a
 * required course the engine reports it in the diagnostics.
 */
public record CourseOption(String courseId, boolean required, List<SectionOption> sections) {

	public CourseOption {
		SectionOption.requireNonBlank(courseId, "courseId");
		sections = List.copyOf(Objects.requireNonNull(sections, "sections"));
		// Used only for lookups, never iterated.
		Map<String, SectionOption> byCrn = new HashMap<>();
		for (SectionOption section : sections) {
			if (!section.courseId().equals(courseId)) {
				throw new IllegalArgumentException(
						"section " + section.crn() + " belongs to " + section.courseId() + ", not " + courseId);
			}
			byCrn.put(section.crn(), section);
		}
		for (SectionOption section : sections) {
			SectionOption parent = section.isPrimary() ? null : byCrn.get(section.parentCrn());
			if (parent != null && !parent.isPrimary()) {
				throw new IllegalArgumentException("section " + section.crn() + " is linked to "
						+ parent.crn() + ", which is itself a linked section");
			}
		}
	}

}
