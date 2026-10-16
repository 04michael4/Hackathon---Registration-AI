package io.github.michael04.registration.course;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** How a student's search text matches a course, shared by course search and elective suggestions. */
public final class CourseQuery {

	/** A course code as a student might type it: "CSE 17", "cse017", "CSE-017". */
	private static final Pattern CODE = Pattern.compile("^([A-Za-z]{2,5})\\s*-?\\s*(\\d{1,4})$");

	private CourseQuery() {
	}

	/**
	 * Blank matches everything. A course code ("CSE 17", "cse017") matches by subject and number, ignoring
	 * leading zeros. Anything else matches when every word appears in the course ID or title.
	 */
	public static boolean matches(CourseSummary course, String q) {
		if (q == null || q.isBlank()) {
			return true;
		}
		String query = q.trim();
		Matcher code = CODE.matcher(query);
		if (code.matches()) {
			String number = number(course.courseId());
			return subject(course.courseId()).equalsIgnoreCase(code.group(1)) && !number.isEmpty()
					&& number.chars().allMatch(Character::isDigit)
					&& Integer.parseInt(number) == Integer.parseInt(code.group(2));
		}
		String haystack = (course.courseId() + " " + course.title()).toLowerCase(Locale.ROOT);
		for (String word : query.toLowerCase(Locale.ROOT).split("\\s+")) {
			if (!haystack.contains(word)) {
				return false;
			}
		}
		return true;
	}

	public static String subject(String courseId) {
		int dash = courseId.indexOf('-');
		return dash < 0 ? courseId : courseId.substring(0, dash);
	}

	public static String number(String courseId) {
		int dash = courseId.indexOf('-');
		return dash < 0 ? "" : courseId.substring(dash + 1);
	}

}
