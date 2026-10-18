package io.github.michael04.registration.ai;

import java.util.List;
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
import io.github.michael04.registration.course.SectionLookupCatalog;
import io.github.michael04.registration.schedule.EngineMapper;
import io.github.michael04.registration.schedule.GenerateScheduleRequest;
import io.github.michael04.registration.schedule.integration.SchedulingEngine;
import jakarta.validation.Validator;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ScheduleExplanationService {
	private final ObjectProvider<CourseCatalog> catalogs;
	private final ObjectProvider<SectionCatalog> sections;
	private final ObjectProvider<SectionLookupCatalog> lookups;
	private final SchedulingEngine engine;
	private final EngineMapper mapper;
	private final Validator validator;
	private final JsonMapper json;
	private final ScheduleExplainer explainer;

	public ScheduleExplanationService(ObjectProvider<CourseCatalog> catalogs, ObjectProvider<SectionCatalog> sections,
			ObjectProvider<SectionLookupCatalog> lookups, SchedulingEngine engine, EngineMapper mapper,
			Validator validator, JsonMapper json, ScheduleExplainer explainer) {
		this.catalogs = catalogs;
		this.sections = sections;
		this.lookups = lookups;
		this.engine = engine;
		this.mapper = mapper;
		this.validator = validator;
		this.json = json;
		this.explainer = explainer;
	}

	public ExplainScheduleResponse explain(ExplainScheduleRequest request) {
		var generation = new GenerateScheduleRequest(request.term(), request.preferences(), 1, true);
		if (!validator.validate(generation).isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
		var catalog = available(catalogs);
		if (!catalog.termExists(request.term())) throw new UnprocessableException("Term is not available");
		var crns = new TreeSet<>(request.crns());
		var selected = available(lookups).sectionsByCrn(request.term(), crns.stream().toList());
		if (!new TreeSet<>(selected.stream().map(s -> s.crn()).toList()).equals(crns)) {
			throw new UnprocessableException("One or more sections are unknown in this term");
		}
		var required = new TreeSet<>(request.preferences().hardConstraints().requiredCourseIds());
		var ids = new TreeSet<>(required);
		selected.forEach(s -> ids.add(s.courseId()));
		var soft = request.preferences().softPreferences();
		if (soft != null && soft.optionalCourseIds() != null) ids.addAll(soft.optionalCourseIds().value());
		var courses = new TreeMap<String, CourseSummary>();
		for (String id : ids) courses.put(id, catalog.findCourse(request.term(), id)
			.orElseThrow(() -> new UnprocessableException("One or more courses are unknown in this term")));
		var prepared = mapper.prepare(generation, required, courses,
			available(sections).sectionsFor(request.term(), ids.stream().toList()));
		var checked = engine.check(prepared.problem(), crns.stream().toList());
		if (!checked.complete()) throw new UnprocessableException("Selected sections do not form a complete feasible schedule");
		var facts = new TreeMap<String, Object>();
		facts.put("term", request.term());
		facts.put("sections", selected.stream().map(s -> new SectionFact(s.crn(), s.courseId(), courses.get(s.courseId()).title(),
				s.credits(), s.meetings())).toList());
		facts.put("totalCredits", checked.totalCredits());
		facts.put("score", checked.score());
		facts.put("scoreBreakdown", checked.scoreBreakdown());
		facts.put("preferences", request.preferences());
		return explainer.explain(json.writeValueAsString(facts));
	}

	private record SectionFact(String crn, String courseId, String title, int credits,
			List<io.github.michael04.registration.course.CatalogSection.Meeting> meetings) {}

	private static <T> T available(ObjectProvider<T> provider) {
		T result = provider.getIfAvailable();
		if (result == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
		return result;
	}
}
