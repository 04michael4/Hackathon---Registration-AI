package io.github.michael04.registration.engine;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A course the student wants, with all of its sections: lectures, standalone sections, and the
 * labs or recitations linked to them (see {@link SectionOption}).
 *
 * <p>A required course must appear exactly once in every returned schedule; an optional one
 * may be left out (at a soft-preference cost). To drop sections that shouldn't be considered,
 * such as closed ones, use {@link #excluding}. A linked section whose lecture is missing is
 * never chosen. An empty {@code sections} list is allowed; for a
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

	/**
	 * Returns a copy without the sections matching {@code exclude}, e.g. closed ones. Use this
	 * rather than filtering {@code sections} by hand: a lecture that loses every linked section of
	 * some component type is removed too, because it can no longer be taken with its required
	 * lab or recitation. Linked sections of a removed lecture are removed as well.
	 */
	public CourseOption excluding(Predicate<SectionOption> exclude) {
		Objects.requireNonNull(exclude, "exclude");
		// Used only for membership checks, never iterated.
		Set<String> removed = new HashSet<>();
		for (SectionOption section : sections) {
			if (exclude.test(section)) {
				removed.add(section.crn());
			}
		}
		for (SectionOption lecture : sections) {
			if (!lecture.isPrimary() || removed.contains(lecture.crn())) {
				continue;
			}
			// component -> does any section of it remain? Order-independent lookup only.
			Map<String, Boolean> componentRemains = new HashMap<>();
			for (SectionOption section : sections) {
				if (lecture.crn().equals(section.parentCrn())) {
					componentRemains.merge(section.component(), !removed.contains(section.crn()), Boolean::logicalOr);
				}
			}
			if (componentRemains.containsValue(false)) {
				removed.add(lecture.crn());
			}
		}
		List<SectionOption> kept = sections.stream()
			.filter(s -> !removed.contains(s.crn()) && (s.isPrimary() || !removed.contains(s.parentCrn())))
			.toList();
		return new CourseOption(courseId, required, kept);
	}

}
