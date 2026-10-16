package io.github.michael04.registration.schedule;

import java.util.TreeMap;
import java.util.TreeSet;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import io.github.michael04.registration.common.UnprocessableException;
import io.github.michael04.registration.course.CourseCatalog;
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
		var selected = new TreeSet<>(required);
		var soft = request.preferences().softPreferences();
		if (soft != null && soft.optionalCourseIds() != null) {
			selected.addAll(soft.optionalCourseIds().value());
		}
		var courses = new TreeMap<String, CourseSummary>();
		for (String id : selected) {
			var course = catalog.findCourse(request.term(), id)
				.orElseThrow(() -> new UnprocessableException("One or more selected courses are unknown in this term"));
			if (!id.equals(course.courseId())) {
				throw new IllegalStateException("Catalog returned a different course");
			}
			courses.put(id, course);
		}
		var sectionCatalog = available(sectionCatalogs);
		var engine = available(engines);
		var input = mapper.prepare(request, required, courses,
			sectionCatalog.sectionsFor(request.term(), selected.stream().toList()));
		return mapper.response(request.term(), input, engine.solve(input.problem(), properties.nodeCap()));
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
