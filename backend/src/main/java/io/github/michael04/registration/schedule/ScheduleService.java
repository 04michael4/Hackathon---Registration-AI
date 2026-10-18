package io.github.michael04.registration.schedule;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import io.github.michael04.registration.common.UnprocessableException;
import io.github.michael04.registration.common.preferences.SchedulePreferences;
import io.github.michael04.registration.course.CourseCatalog;
import io.github.michael04.registration.course.CourseQuery;
import io.github.michael04.registration.course.CourseSummary;
import io.github.michael04.registration.course.SectionCatalog;
import io.github.michael04.registration.schedule.integration.SchedulingEngine;

@Service
public class ScheduleService {
	private final ObjectProvider<CourseCatalog> catalogs;
	private final ObjectProvider<SectionCatalog> sectionCatalogs;
	private final ObjectProvider<SchedulingEngine> engines;
	private final SchedulingProperties properties;
	private final EngineMapper mapper;

	public ScheduleService(ObjectProvider<CourseCatalog> catalogs, ObjectProvider<SectionCatalog> sectionCatalogs,
			ObjectProvider<SchedulingEngine> engines, SchedulingProperties properties, EngineMapper mapper) {
		this.catalogs = catalogs;
		this.sectionCatalogs = sectionCatalogs;
		this.engines = engines;
		this.properties = properties;
		this.mapper = mapper;
	}

	/** Called only after controller Bean Validation succeeds. No I/O or scoring occurs in the engine adapter boundary. */
	public GenerateScheduleResponse generate(GenerateScheduleRequest request) {
		var catalog = available(catalogs);
		if (!catalog.termExists(request.term())) {
			throw new UnprocessableException("Unknown term");
		}
		var required = new TreeSet<>(request.preferences().hardConstraints().requiredCourseIds());
		var selected = selectedCourses(request.preferences(), List.of());
		var courses = resolve(catalog, request.term(), selected);
		var sectionCatalog = available(sectionCatalogs);
		var engine = available(engines);
		var input = mapper.prepare(request, required, courses,
			sectionCatalog.sectionsFor(request.term(), selected.stream().toList()));
		return mapper.response(request.term(), input, engine.solve(input.problem(), properties.nodeCap()));
	}

	/** Manual mode: check the placed sections. Unknown CRNs come back as issues, not errors. */
	public CheckScheduleResponse check(CheckScheduleRequest request) {
		var catalog = available(catalogs);
		if (!catalog.termExists(request.term())) {
			throw new UnprocessableException("Unknown term");
		}
		var required = new TreeSet<>(request.preferences().hardConstraints().requiredCourseIds());
		var selected = selectedCourses(request.preferences(), request.courseIds());
		var courses = resolve(catalog, request.term(), selected);
		var sectionCatalog = available(sectionCatalogs);
		var engine = available(engines);
		var input = mapper.prepare(request.term(), request.preferences(), request.includeClosedSections(), 1, required,
			courses, sectionCatalog.sectionsFor(request.term(), selected.stream().toList()));
		return mapper.checkResponse(request.term(), input, engine.check(input.problem(), request.crns()));
	}

	/**
	 * Electives that fit the schedule {@code crns} as it is. Candidates are catalog courses not already in play,
	 * narrowed by {@code q}/{@code subject} like course search, first {@value #MAX_CANDIDATES} by course ID.
	 */
	public SuggestElectivesResponse suggestElectives(SuggestElectivesRequest request) {
		var catalog = available(catalogs);
		if (!catalog.termExists(request.term())) {
			throw new UnprocessableException("Unknown term");
		}
		var required = new TreeSet<>(request.preferences().hardConstraints().requiredCourseIds());
		var selected = selectedCourses(request.preferences(), request.courseIds());
		var courses = resolve(catalog, request.term(), selected);
		var candidates = new TreeMap<String, CourseSummary>();
		catalog.listCourses(request.term()).stream()
			.filter(c -> !selected.contains(c.courseId()))
			.filter(c -> request.subject() == null || request.subject().isBlank()
				|| CourseQuery.subject(c.courseId()).equalsIgnoreCase(request.subject().trim()))
			.filter(c -> CourseQuery.matches(c, request.q()))
			.sorted(Comparator.comparing(CourseSummary::courseId))
			.limit(MAX_CANDIDATES)
			.forEach(c -> candidates.put(c.courseId(), c));
		var sectionCatalog = available(sectionCatalogs);
		var engine = available(engines);
		var all = new TreeSet<>(selected);
		all.addAll(candidates.keySet());
		var sections = sectionCatalog.sectionsFor(request.term(), all.stream().toList());
		var base = mapper.prepare(request.term(), request.preferences(), request.includeClosedSections(), 1, required,
			courses, sections);
		if (!base.sections().keySet().containsAll(request.crns())) {
			throw new UnprocessableException("One or more sections are not offered for this schedule's courses");
		}
		var pool = mapper.prepare(request.term(), request.preferences(), request.includeClosedSections(), 1, Set.of(),
			candidates, sections);
		var fits = engine.suggestElectives(base.problem(), request.crns(), pool.problem().courses(), request.limit());
		return mapper.suggestionsResponse(request.term(), pool, fits);
	}

	/** Most catalog courses considered per elective-suggestion request; bounds the section lookup. */
	static final int MAX_CANDIDATES = 50;

	/** Required and optional courses from the preferences, plus {@code extra}, de-duplicated and sorted. */
	private static TreeSet<String> selectedCourses(SchedulePreferences preferences, List<String> extra) {
		var selected = new TreeSet<>(preferences.hardConstraints().requiredCourseIds());
		var soft = preferences.softPreferences();
		if (soft != null && soft.optionalCourseIds() != null) {
			selected.addAll(soft.optionalCourseIds().value());
		}
		selected.addAll(extra);
		return selected;
	}

	private static TreeMap<String, CourseSummary> resolve(CourseCatalog catalog, String term, Set<String> ids) {
		var courses = new TreeMap<String, CourseSummary>();
		for (String id : ids) {
			var course = catalog.findCourse(term, id)
				.orElseThrow(() -> new UnprocessableException("One or more selected courses are unknown in this term"));
			if (!id.equals(course.courseId())) {
				throw new IllegalStateException("Catalog returned a different course");
			}
			courses.put(id, course);
		}
		return courses;
	}

	private static <T> T available(ObjectProvider<T> provider) {
		var dependency = provider.getIfAvailable();
		if (dependency == null) {
			// The shared advice renders a safe 503. Never return fabricated schedules or an empty success.
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
		}
		return dependency;
	}
}
