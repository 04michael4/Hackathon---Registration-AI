package io.github.michael04.registration.course;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import io.github.michael04.registration.common.NotFoundException;

/** Course search and course detail over whichever {@link CourseCatalog} and {@link SectionCatalog} are active. */
@Service
public class CourseService {

	/** A course code as a student might type it: "CSE 17", "cse017", "CSE-017". */
	private static final Pattern CODE = Pattern.compile("^([A-Za-z]{2,5})\\s*-?\\s*(\\d{1,4})$");

	private final ObjectProvider<CourseCatalog> catalogs;
	private final ObjectProvider<SectionCatalog> sectionCatalogs;

	public CourseService(ObjectProvider<CourseCatalog> catalogs, ObjectProvider<SectionCatalog> sectionCatalogs) {
		this.catalogs = catalogs;
		this.sectionCatalogs = sectionCatalogs;
	}

	public CourseSearchResponse search(String term, String q, String subject, int page, int size) {
		CourseCatalog catalog = available(catalogs);
		if (!catalog.termExists(term)) {
			throw new NotFoundException("Unknown term");
		}
		List<CourseSummary> matches = catalog.listCourses(term).stream()
			.filter(c -> subject == null || subject.isBlank() || subject(c.courseId()).equalsIgnoreCase(subject.trim()))
			.filter(c -> matches(c, q))
			.sorted(Comparator.comparing(CourseSummary::courseId))
			.toList();
		int from = (int) Math.min((long) page * size, matches.size());
		List<CourseSummary> pageItems = matches.subList(from, Math.min(from + size, matches.size()));
		Map<String, Long> lectureCounts = pageItems.isEmpty() ? Map.of()
				: available(sectionCatalogs).sectionsFor(term, pageItems.stream().map(CourseSummary::courseId).toList())
					.stream()
					.filter(s -> s.lectureLink() == null)
					.collect(Collectors.groupingBy(CatalogSection::courseId, Collectors.counting()));
		List<CourseSearchResponse.Item> items = pageItems.stream()
			.map(c -> new CourseSearchResponse.Item(c.courseId(), subject(c.courseId()), number(c.courseId()), c.title(),
					c.credits(), lectureCounts.getOrDefault(c.courseId(), 0L).intValue()))
			.toList();
		int totalPages = (int) ((matches.size() + (long) size - 1) / size);
		return new CourseSearchResponse(items, page, size, matches.size(), totalPages);
	}

	public CourseDetailResponse detail(String term, String courseId) {
		CourseCatalog catalog = available(catalogs);
		if (!catalog.termExists(term)) {
			throw new NotFoundException("Unknown term");
		}
		CourseSummary course = catalog.findCourse(term, courseId)
			.orElseThrow(() -> new NotFoundException("Unknown course"));
		List<CourseDetailResponse.Section> sections = available(sectionCatalogs).sectionsFor(term, List.of(courseId))
			.stream()
			.filter(s -> s.courseId().equals(courseId) && s.term().equals(term))
			.sorted(Comparator.comparing(CatalogSection::crn))
			.map(s -> new CourseDetailResponse.Section(s.crn(), s.sectionCode(), s.componentType(), s.lectureLink(),
					s.credits(), List.of(), s.status(),
					s.meetings().stream()
						.map(m -> new CourseDetailResponse.Meeting(m.days().stream().distinct().sorted().toList(),
								m.start(), m.end(), m.location()))
						.toList()))
			.toList();
		return new CourseDetailResponse(course.courseId(), subject(course.courseId()), number(course.courseId()),
				course.title(), course.credits(), null, sections);
	}

	/**
	 * Blank matches everything. A course code ("CSE 17", "cse017") matches by subject and number, ignoring
	 * leading zeros. Anything else matches when every word appears in the course ID or title.
	 */
	static boolean matches(CourseSummary course, String q) {
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

	static String subject(String courseId) {
		int dash = courseId.indexOf('-');
		return dash < 0 ? courseId : courseId.substring(0, dash);
	}

	static String number(String courseId) {
		int dash = courseId.indexOf('-');
		return dash < 0 ? "" : courseId.substring(dash + 1);
	}

	private static <T> T available(ObjectProvider<T> provider) {
		T dependency = provider.getIfAvailable();
		if (dependency == null) {
			// Rendered as the shared, safe 503.
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
		}
		return dependency;
	}

}
