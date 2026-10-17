package io.github.michael04.registration.demo;

import java.util.List;
import java.util.Locale;

/** What the student chose to tell the demo. Every field is optional; a missing field means "unknown", never "eligible". */
public record DemoProfile(String classStanding, String level, String college, List<String> majors) {

	public static final DemoProfile NONE = new DemoProfile(null, null, null, List.of());

	public DemoProfile {
		classStanding = blankToNull(classStanding);
		level = blankToNull(level);
		college = blankToNull(college);
		majors = majors == null ? List.of() : majors.stream().filter(m -> m != null && !m.isBlank())
			.map(m -> m.trim().toUpperCase(Locale.ROOT)).distinct().toList();
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s.trim().toUpperCase(Locale.ROOT);
	}

}
