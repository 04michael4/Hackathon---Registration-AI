package io.github.michael04.registration.ai;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import io.github.michael04.registration.course.CourseCatalog;
import io.github.michael04.registration.course.CourseSummary;

/**
 * Maps what a student typed ("cse 17", "Calc 1") to a real course in the catalog.
 * Course IDs only ever come from the catalog, so the AI can't invent one.
 *
 * <ol>
 * <li>Code-like mentions ("CSE 17", "cse017", "CSE-017") are normalized to {@code CSE-017}
 * and looked up exactly.</li>
 * <li>Anything else, including code-like mentions that aren't real codes ("Calc 1"), is matched
 * against course titles; it resolves only if exactly one course matches, otherwise it's left unresolved.</li>
 * </ol>
 */
@Component
public class CourseMentionResolver {

	private static final Pattern COURSE_CODE = Pattern.compile("^([A-Za-z]{2,4})[\\s-]*(\\d{1,3})$");

	private static final Set<String> STOP_WORDS = Set.of("a", "an", "and", "the", "of", "to", "in", "for", "course", "class");

	private static final Map<String, String> ROMAN = Map.of("1", "i", "2", "ii", "3", "iii", "4", "iv");

	private final CourseCatalog catalog;

	public CourseMentionResolver(CourseCatalog catalog) {
		this.catalog = catalog;
	}

	public Resolution resolve(String term, String mention) {
		String trimmed = mention == null ? "" : mention.trim();
		Matcher code = COURSE_CODE.matcher(trimmed);
		if (code.matches()) {
			String courseId = code.group(1).toUpperCase(Locale.ROOT) + "-"
					+ String.format(Locale.ROOT, "%03d", Integer.parseInt(code.group(2)));
			Optional<CourseSummary> exact = catalog.findCourse(term, courseId);
			if (exact.isPresent()) {
				return new Resolution(trimmed, exact, true);
			}
			// Not a real code; it may be a nickname that looks like one ("Calc 1"), so try titles.
		}
		List<String> wanted = tokens(trimmed).stream().filter(t -> !STOP_WORDS.contains(t)).toList();
		if (wanted.isEmpty()) {
			return new Resolution(trimmed, Optional.empty(), false);
		}
		List<CourseSummary> matches = catalog.listCourses(term).stream()
				.filter(course -> matchesTitle(wanted, tokens(course.title())))
				.toList();
		Optional<CourseSummary> match = matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
		return new Resolution(trimmed, match, false);
	}

	/** Every wanted word must match a title word: numbers exactly (1 = I), other words as a prefix ("calc" → "calculus"). */
	private static boolean matchesTitle(List<String> wanted, List<String> titleWords) {
		return wanted.stream().allMatch(w -> titleWords.stream().anyMatch(t -> isNumeral(w) ? t.equals(w) : t.startsWith(w)));
	}

	private static boolean isNumeral(String token) {
		return ROMAN.containsValue(token);
	}

	private static List<String> tokens(String text) {
		return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
				.filter(t -> !t.isBlank())
				.map(t -> ROMAN.getOrDefault(t, t))
				.toList();
	}

	/**
	 * @param exactCode {@code true} if the mention was a course code, {@code false} if matched by title
	 */
	public record Resolution(String mention, Optional<CourseSummary> course, boolean exactCode) {

		public boolean resolved() {
			return course.isPresent();
		}

	}

}
