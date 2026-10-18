package io.github.michael04.registration.course;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import io.github.michael04.registration.common.NotFoundException;

/** Course search and course detail over whichever {@link CourseCatalog} and {@link SectionCatalog} are active. */
@Service
public class CourseService {

	private final ObjectProvider<CourseCatalog> catalogs;
	private final ObjectProvider<SectionCatalog> sectionCatalogs;
	private final ObjectProvider<CourseMetadataCatalog> metadataCatalogs;
	private final ObjectProvider<ConditionCatalog> conditionCatalogs;
	private final ObjectProvider<RatingCatalog> ratingCatalogs;

	@Autowired
	public CourseService(ObjectProvider<CourseCatalog> catalogs, ObjectProvider<SectionCatalog> sectionCatalogs,
			ObjectProvider<CourseMetadataCatalog> metadataCatalogs, ObjectProvider<ConditionCatalog> conditionCatalogs,
			ObjectProvider<RatingCatalog> ratingCatalogs) {
		this.catalogs = catalogs;
		this.sectionCatalogs = sectionCatalogs;
		this.metadataCatalogs = metadataCatalogs;
		this.conditionCatalogs = conditionCatalogs;
		this.ratingCatalogs = ratingCatalogs;
	}

	/** Without a rating catalog: no predicted ratings and no instructor ratings. */
	public CourseService(ObjectProvider<CourseCatalog> catalogs, ObjectProvider<SectionCatalog> sectionCatalogs,
			ObjectProvider<CourseMetadataCatalog> metadataCatalogs, ObjectProvider<ConditionCatalog> conditionCatalogs) {
		this(catalogs, sectionCatalogs, metadataCatalogs, conditionCatalogs, null);
	}

	/** For callers and tests that have no condition catalog: details then carry an empty conditions list. */
	public CourseService(ObjectProvider<CourseCatalog> catalogs, ObjectProvider<SectionCatalog> sectionCatalogs,
			ObjectProvider<CourseMetadataCatalog> metadataCatalogs) {
		this(catalogs, sectionCatalogs, metadataCatalogs, null);
	}

	public CourseResponses.TermsResponse terms() {
		return new CourseResponses.TermsResponse(available(metadataCatalogs).terms());
	}

	public CourseSearchResponse search(String term, String q, String subject, int page, int size) {
		CourseCatalog catalog = available(catalogs);
		if (!catalog.termExists(term)) {
			throw new NotFoundException("Unknown term");
		}
		List<CourseSummary> matches = catalog.listCourses(term).stream()
			.filter(c -> subject == null || subject.isBlank()
				|| CourseQuery.subject(c.courseId()).equalsIgnoreCase(subject.trim()))
			.filter(c -> CourseQuery.matches(c, q))
			.sorted(Comparator.comparing(CourseSummary::courseId))
			.toList();
		int from = (int) Math.min((long) page * size, matches.size());
		List<CourseSummary> pageItems = matches.subList(from, Math.min(from + size, matches.size()));
		Map<String, Long> lectureCounts = pageItems.isEmpty() ? Map.of()
				: available(sectionCatalogs).sectionsFor(term, pageItems.stream().map(CourseSummary::courseId).toList())
					.stream()
					.filter(s -> s.lectureLink() == null)
					.collect(Collectors.groupingBy(CatalogSection::courseId, Collectors.counting()));
		RatingCatalog ratings = ratingCatalogs == null ? null : ratingCatalogs.getIfAvailable();
		Map<String, PredictedRating> predicted = ratings == null || pageItems.isEmpty() ? Map.of()
				: ratings.courseRatings(term, pageItems.stream().map(CourseSummary::courseId).toList());
		List<CourseSearchResponse.Item> items = pageItems.stream()
			.map(c -> new CourseSearchResponse.Item(c.courseId(), CourseQuery.subject(c.courseId()),
					CourseQuery.number(c.courseId()), c.title(), c.credits(), lectureCounts.getOrDefault(c.courseId(), 0L).intValue(),
					predicted.get(c.courseId())))
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
		CourseMetadataCatalog metadata = metadataCatalogs.getIfAvailable();
		var detail = metadata == null ? new CourseMetadataCatalog.Detail(null, Map.of()) : metadata.detail(term, courseId);
		RatingCatalog ratings = ratingCatalogs == null ? null : ratingCatalogs.getIfAvailable();
		Map<String, List<InstructorRating>> instructorRatings = ratings == null ? Map.of() : ratings.instructorsByCrn(term, courseId);
		List<CourseDetailResponse.Section> sections = available(sectionCatalogs).sectionsFor(term, List.of(courseId))
			.stream()
			.filter(s -> s.courseId().equals(courseId) && s.term().equals(term))
			.sorted(Comparator.comparing(CatalogSection::crn))
			.map(s -> new CourseDetailResponse.Section(s.crn(), s.sectionCode(), s.componentType(), s.lectureLink(),
					s.credits(), instructorNames(detail, instructorRatings, s.crn()), s.status(),
					s.meetings().stream()
						.map(m -> new CourseDetailResponse.Meeting(m.days().stream().distinct().sorted().toList(),
								m.start(), m.end(), m.location()))
						.toList(),
					instructorRatings.getOrDefault(s.crn(), List.of())))
			.toList();
		ConditionCatalog conditionCatalog = conditionCatalogs == null ? null : conditionCatalogs.getIfAvailable();
		List<CourseCondition> conditions = conditionCatalog == null ? List.of() : conditionCatalog.conditionsFor(term, courseId);
		return new CourseDetailResponse(course.courseId(), CourseQuery.subject(course.courseId()),
				CourseQuery.number(course.courseId()), course.title(), course.credits(), detail.description(), sections, conditions,
				ratings == null ? null : ratings.courseRatings(term, List.of(courseId)).get(courseId));
	}

	/** Instructors stored with the course metadata win; otherwise the names of the rating catalog (ordered by name). */
	private static List<String> instructorNames(CourseMetadataCatalog.Detail detail, Map<String, List<InstructorRating>> ratings,
			String crn) {
		List<String> stored = detail.instructorsByCrn().getOrDefault(crn, List.of());
		return !stored.isEmpty() ? stored : ratings.getOrDefault(crn, List.of()).stream().map(InstructorRating::name).toList();
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
