package io.github.michael04.registration.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * One choice for a course: a lecture (or standalone section) plus exactly one of its linked
 * sections per component type. {@code sections} has the lecture first, then the linked
 * sections by component. {@code key} is their CRNs joined, unique within a problem.
 */
record Bundle(String courseId, List<SectionOption> sections, int credits, List<Meeting> meetings, String key) {

	private static final Comparator<SectionOption> BY_CRN = Comparator.comparing(SectionOption::crn);

	static Bundle of(String courseId, List<SectionOption> sections) {
		int credits = 0;
		List<Meeting> meetings = new ArrayList<>();
		for (SectionOption section : sections) {
			credits += section.credits();
			meetings.addAll(section.meetings());
		}
		String key = sections.stream().map(SectionOption::crn).collect(Collectors.joining(","));
		return new Bundle(courseId, List.copyOf(sections), credits, List.copyOf(meetings), key);
	}

	/**
	 * All bundles of a course, ordered by lecture CRN, then by linked CRNs. Linked sections whose
	 * lecture is missing never appear.
	 */
	static List<Bundle> expand(CourseOption course) {
		List<SectionOption> lectures = course.sections().stream()
			.filter(SectionOption::isPrimary)
			.sorted(BY_CRN)
			.toList();
		List<Bundle> bundles = new ArrayList<>();
		for (SectionOption lecture : lectures) {
			SortedMap<String, List<SectionOption>> linkedByComponent = new TreeMap<>();
			for (SectionOption section : course.sections()) {
				if (lecture.crn().equals(section.parentCrn())) {
					linkedByComponent.computeIfAbsent(section.component(), c -> new ArrayList<>()).add(section);
				}
			}
			List<List<SectionOption>> combinations = List.of(List.of(lecture));
			for (List<SectionOption> options : linkedByComponent.values()) {
				options.sort(BY_CRN);
				List<List<SectionOption>> extended = new ArrayList<>();
				for (List<SectionOption> combination : combinations) {
					for (SectionOption option : options) {
						List<SectionOption> next = new ArrayList<>(combination);
						next.add(option);
						extended.add(next);
					}
				}
				combinations = extended;
			}
			for (List<SectionOption> combination : combinations) {
				bundles.add(of(course.courseId(), combination));
			}
		}
		return bundles;
	}

	/** {@code true} if two of this bundle's own meetings overlap, e.g. a lab during its lecture. */
	boolean hasInternalConflict() {
		for (int i = 0; i < meetings.size(); i++) {
			for (int j = i + 1; j < meetings.size(); j++) {
				if (meetings.get(i).overlaps(meetings.get(j))) {
					return true;
				}
			}
		}
		return false;
	}

	boolean overlapsAny(List<Meeting> others) {
		for (Meeting meeting : meetings) {
			for (Meeting other : others) {
				if (meeting.overlaps(other)) {
					return true;
				}
			}
		}
		return false;
	}

	boolean conflictsWith(Bundle other) {
		return overlapsAny(other.meetings);
	}

}
