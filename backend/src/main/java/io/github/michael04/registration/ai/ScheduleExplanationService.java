package io.github.michael04.registration.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import io.github.michael04.registration.ai.ScheduleFacts.SectionFact;
import io.github.michael04.registration.common.UnprocessableException;
import io.github.michael04.registration.common.preferences.HardConstraints;
import io.github.michael04.registration.common.preferences.SchedulePreferences;
import io.github.michael04.registration.course.CatalogSection;
import io.github.michael04.registration.course.CourseCatalog;
import io.github.michael04.registration.course.CourseSummary;
import io.github.michael04.registration.course.SectionCatalog;
import io.github.michael04.registration.course.SectionLookupCatalog;
import io.github.michael04.registration.schedule.EngineMapper;
import io.github.michael04.registration.schedule.GenerateScheduleRequest;
import io.github.michael04.registration.schedule.SchedulingProperties;
import io.github.michael04.registration.schedule.integration.SchedulingEngine;
import jakarta.validation.Validator;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the facts Claude explains. Claude never decides anything here: the sections are reloaded from the catalog and
 * checked by the engine, and every statement about breaks, start times and rules is computed in Java
 * ({@link ScheduleFacts}). With no sections given, the engine searches itself: a schedule it finds is explained, and when
 * none exists the facts carry the engine's diagnostics plus the single changes it verified would make one possible.
 */
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
	private final SchedulingProperties properties;

	public ScheduleExplanationService(ObjectProvider<CourseCatalog> catalogs, ObjectProvider<SectionCatalog> sections,
			ObjectProvider<SectionLookupCatalog> lookups, SchedulingEngine engine, EngineMapper mapper,
			Validator validator, JsonMapper json, ScheduleExplainer explainer, SchedulingProperties properties) {
		this.catalogs = catalogs;
		this.sections = sections;
		this.lookups = lookups;
		this.engine = engine;
		this.mapper = mapper;
		this.validator = validator;
		this.json = json;
		this.explainer = explainer;
		this.properties = properties;
	}

	public ExplainScheduleResponse explain(ExplainScheduleRequest request) {
		var generation = new GenerateScheduleRequest(request.term(), request.preferences(), 1, true);
		if (!validator.validate(generation).isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
		var catalog = available(catalogs);
		if (!catalog.termExists(request.term())) throw new UnprocessableException("Term is not available");
		var required = new TreeSet<>(request.preferences().hardConstraints().requiredCourseIds());
		var ids = new TreeSet<>(required);
		var soft = request.preferences().softPreferences();
		if (soft != null && soft.optionalCourseIds() != null) ids.addAll(soft.optionalCourseIds().value());

		var crns = new TreeSet<>(request.crns());
		if (crns.isEmpty()) {
			// No sections named: let the engine search. A schedule it finds is explained like any other.
			var found = search(request, catalog, required, ids);
			if (found.noSchedule() != null) return explainer.explain(json.writeValueAsString(found.noSchedule()));
			crns.addAll(found.crns());
		}
		var selected = available(lookups).sectionsByCrn(request.term(), crns.stream().toList());
		if (!new TreeSet<>(selected.stream().map(s -> s.crn()).toList()).equals(crns)) {
			throw new UnprocessableException("One or more sections are unknown in this term");
		}
		selected.forEach(s -> ids.add(s.courseId()));
		var courses = new TreeMap<String, CourseSummary>();
		for (String id : ids) courses.put(id, catalog.findCourse(request.term(), id)
			.orElseThrow(() -> new UnprocessableException("One or more courses are unknown in this term")));
		var prepared = mapper.prepare(generation, required, courses,
			available(sections).sectionsFor(request.term(), ids.stream().toList()));
		var checked = engine.check(prepared.problem(), crns.stream().toList());
		if (!checked.complete()) throw new UnprocessableException("Selected sections do not form a complete feasible schedule");
		var placed = selected.stream().map(s -> new SectionFact(s.crn(), s.courseId(), courses.get(s.courseId()).title(),
				s.credits(), s.meetings())).toList();
		var facts = new LinkedHashMap<String, Object>();
		facts.put("outcome", "VALID_SCHEDULE");
		facts.put("term", request.term());
		facts.put("sections", placed);
		facts.put("totalCredits", checked.totalCredits());
		facts.put("score", checked.score());
		facts.put("scoreBreakdown", checked.scoreBreakdown());
		facts.put("preferences", request.preferences());
		facts.put("computed", ScheduleFacts.compute(placed, request.preferences()));
		return explainer.explain(json.writeValueAsString(facts));
	}

	private record Search(List<String> crns, Map<String, Object> noSchedule) {}

	/** Runs the same search as Generate. Returns the best schedule's CRNs, or the facts that explain why there is none. */
	private Search search(ExplainScheduleRequest request, CourseCatalog catalog, TreeSet<String> required, TreeSet<String> ids) {
		var courses = new TreeMap<String, CourseSummary>();
		for (String id : ids) courses.put(id, catalog.findCourse(request.term(), id)
			.orElseThrow(() -> new UnprocessableException("One or more courses are unknown in this term")));
		var catalogSections = available(sections).sectionsFor(request.term(), ids.stream().toList());
		var base = solve(request.term(), request.preferences(), required, courses, catalogSections);
		if (!base.schedules().isEmpty()) return new Search(base.schedules().get(0).sectionCrns(), null);

		var facts = new LinkedHashMap<String, Object>();
		facts.put("outcome", "NO_VALID_SCHEDULE");
		facts.put("term", request.term());
		facts.put("requiredCourses", required.stream().map(id -> id + " " + courses.get(id).title()).toList());
		facts.put("preferences", request.preferences());
		facts.put("engineDiagnostics", base.diagnostics());
		facts.put("searchLimitReached", base.searchLimitReached());
		facts.put("changesVerifiedByEngine", relaxations(request, required, courses, catalogSections));
		return new Search(List.of(), facts);
	}

	/**
	 * Single changes, each re-run through the real engine: dropping one blocked time, lowering the minimum credits, or
	 * leaving out one required course. Only changes that produce a valid schedule are reported as working.
	 */
	private List<String> relaxations(ExplainScheduleRequest request, TreeSet<String> required,
			TreeMap<String, CourseSummary> courses, List<CatalogSection> catalogSections) {
		var results = new ArrayList<String>();
		var prefs = request.preferences();
		var hard = prefs.hardConstraints();
		for (int i = 0; i < hard.blockedTimes().size(); i++) {
			var blocks = new ArrayList<>(hard.blockedTimes());
			var removed = blocks.remove(i);
			var variant = new SchedulePreferences(new HardConstraints(hard.requiredCourseIds(), hard.minCredits(), hard.maxCredits(), blocks, hard.latestStart()), prefs.softPreferences());
			if (!solve(request.term(), variant, required, courses, catalogSections).schedules().isEmpty()) {
				results.add("A valid schedule exists if this blocked time is dropped: " + removed.days() + " " + removed.start() + "-" + removed.end());
			}
		}
		if (hard.minCredits() > 1) {
			var variant = new SchedulePreferences(new HardConstraints(hard.requiredCourseIds(), 1, hard.maxCredits(), hard.blockedTimes(), hard.latestStart()), prefs.softPreferences());
			if (!solve(request.term(), variant, required, courses, catalogSections).schedules().isEmpty()) {
				results.add("A valid schedule exists if the minimum credits (" + hard.minCredits() + ") is lowered");
			}
		}
		if (required.size() > 1) {
			for (String dropped : required) {
				var others = new TreeSet<>(required);
				others.remove(dropped);
				var reduced = new TreeMap<>(courses);
				reduced.remove(dropped);
				var variant = new SchedulePreferences(new HardConstraints(List.copyOf(others), hard.minCredits(), hard.maxCredits(), hard.blockedTimes(), hard.latestStart()), prefs.softPreferences());
				var remaining = catalogSections.stream().filter(s -> !dropped.equals(s.courseId())).toList();
				if (!solve(request.term(), variant, others, reduced, remaining).schedules().isEmpty()) {
					results.add("A valid schedule exists without the required course " + dropped);
				}
			}
		}
		if (results.isEmpty()) results.add("None of these single changes produced a valid schedule: dropping one blocked time, lowering the minimum credits, or leaving out one required course");
		return results;
	}

	private SchedulingEngine.Result solve(String term, SchedulePreferences preferences, TreeSet<String> required,
			Map<String, CourseSummary> courses, List<CatalogSection> catalogSections) {
		var request = new GenerateScheduleRequest(term, preferences, 1, true);
		var input = mapper.prepare(request, required, courses, catalogSections);
		return engine.solve(input.problem(), properties.nodeCap());
	}

	private static <T> T available(ObjectProvider<T> provider) {
		T result = provider.getIfAvailable();
		if (result == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
		return result;
	}
}
