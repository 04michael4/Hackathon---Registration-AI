package io.github.michael04.registration.demo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import io.github.michael04.registration.common.preferences.HardConstraints;
import io.github.michael04.registration.common.preferences.SchedulePreferences;
import io.github.michael04.registration.common.preferences.SoftPreferences;
import io.github.michael04.registration.common.preferences.Weighted;
import io.github.michael04.registration.schedule.GenerateScheduleRequest;
import io.github.michael04.registration.schedule.GenerateScheduleResponse;
import io.github.michael04.registration.schedule.ScheduleService;

/**
 * Runs the existing schedule service on the demo catalog and adds what the shared response cannot carry: which course each
 * section belongs to, whether it is a lab, and the eligibility warnings. The engine and the shared endpoint are untouched.
 */
public class DemoScheduleService {

	private final DemoCatalog catalog;
	private final ScheduleService scheduleService;

	public DemoScheduleService(DemoCatalog catalog, ScheduleService scheduleService) {
		this.catalog = catalog;
		this.scheduleService = scheduleService;
	}

	public DemoScheduleResponse generate(DemoGenerateRequest request) {
		DemoProfile profile = request.profile() == null ? DemoProfile.NONE : request.profile().toProfile();
		TreeSet<String> required = new TreeSet<>(request.courseIds());
		List<String> optional = request.optionalCourseIds().stream().filter(id -> !required.contains(id)).distinct().sorted().toList();
		SoftPreferences soft = optional.isEmpty() ? null : new SoftPreferences(new Weighted<>(optional, 3), null, null, null, null);
		var preferences = new SchedulePreferences(
				new HardConstraints(List.copyOf(required), request.minCredits(), request.maxCredits(), List.of()), soft);
		GenerateScheduleResponse response = scheduleService.generate(
				new GenerateScheduleRequest(catalog.term(), preferences, request.maxResults(), false));

		Set<String> selected = new LinkedHashSet<>(required);
		selected.addAll(optional);
		Map<String, DemoEligibility.CourseReport> reports = new LinkedHashMap<>();
		for (String id : selected) {
			catalog.offering(id).ifPresent(o -> reports.put(id, DemoEligibility.report(o, profile)));
		}
		List<DemoScheduleResponse.Schedule> schedules = new ArrayList<>();
		for (var s : response.schedules()) {
			List<DemoScheduleResponse.SectionView> sections = s.sections().stream().map(this::view).toList();
			Set<String> ids = new TreeSet<>();
			sections.forEach(v -> ids.add(v.courseId()));
			List<DemoWarning> warnings = new ArrayList<>();
			boolean override = false;
			for (String id : ids) {
				var report = reports.computeIfAbsent(id, k -> DemoEligibility.report(catalog.offering(k).orElseThrow(), profile));
				warnings.addAll(report.warnings());
				override |= DemoEligibility.OVERRIDE_REQUIRED.equals(report.status());
			}
			warnings.addAll(exclusionsWithin(ids));
			schedules.add(new DemoScheduleResponse.Schedule(s.rank(), s.score(), s.totalCredits(), s.overload(),
					override ? DemoEligibility.OVERRIDE_REQUIRED : DemoEligibility.UNVERIFIED, sections, List.copyOf(warnings)));
		}
		return new DemoScheduleResponse(response.term(), catalog.fixture().caution(), List.copyOf(selected), schedules,
				response.diagnostics(), response.searchLimitReached(), List.copyOf(reports.values()));
	}

	/** Courses the student picked that exclude each other's credit: shown, never silently dropped. */
	private List<DemoWarning> exclusionsWithin(Set<String> ids) {
		List<DemoWarning> out = new ArrayList<>();
		for (String id : ids) {
			catalog.offering(id).ifPresent(o -> o.creditExclusions().stream().filter(e -> ids.contains(e.with()) && id.compareTo(e.with()) < 0)
				.forEach(e -> out.add(new DemoWarning("CREDIT_EXCLUSION_IN_SELECTION", "WARNING", "UNVERIFIED", "CATALOG", id,
						id + " and " + e.with() + " are both in this timetable: " + e.text() + "."))));
		}
		return out;
	}

	private DemoScheduleResponse.SectionView view(GenerateScheduleResponse.SectionResponse s) {
		var offering = catalog.offering(s.courseId()).orElseThrow();
		String component = offering.sections().stream().filter(x -> x.crn().equals(s.crn())).map(DemoFixture.Section::component).findFirst().orElse("LECTURE");
		return new DemoScheduleResponse.SectionView(s.crn(), s.courseId(), s.title(), offering.topic(), s.sectionCode(), component, s.credits(),
				s.meetings().stream().map(m -> new DemoScheduleResponse.MeetingView(m.days(), m.start(), m.end(), m.location())).toList());
	}

}
