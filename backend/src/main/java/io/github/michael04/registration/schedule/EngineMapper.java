package io.github.michael04.registration.schedule;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

import io.github.michael04.registration.common.preferences.CreditLimits;
import io.github.michael04.registration.common.preferences.SchedulePreferences;
import io.github.michael04.registration.common.preferences.SoftPreferences;
import io.github.michael04.registration.course.CatalogSection;
import io.github.michael04.registration.course.CourseSummary;
import io.github.michael04.registration.engine.CourseOption;
import io.github.michael04.registration.engine.SectionOption;
import io.github.michael04.registration.schedule.GenerateScheduleResponse.MeetingResponse;
import io.github.michael04.registration.schedule.GenerateScheduleResponse.ScheduleResponse;
import io.github.michael04.registration.schedule.GenerateScheduleResponse.SectionResponse;
import io.github.michael04.registration.schedule.integration.NativeSchedulingEngine;
import io.github.michael04.registration.schedule.integration.SchedulingEngine;

@Component
public class EngineMapper {

	/** Input for {@code generate}, which also enforces the optional-course limit (it multiplies the search). */
	public PreparedInput prepare(GenerateScheduleRequest request, Set<String> required,
			Map<String, CourseSummary> courses, List<CatalogSection> sections) {
		var input = prepare(request.term(), request.preferences(), request.includeClosedSections(), request.maxResults(),
			required, courses, sections);
		if (input.problem().courses().stream().filter(course -> !course.required()).count()
				> ScheduleRequestValidator.MAX_OPTIONAL_COURSES) {
			throw new IllegalArgumentException("Too many optional courses for the engine");
		}
		return input;
	}

	/**
	 * The same preparation without the optional-course limit, for manual mode and elective suggestions,
	 * which check placements instead of searching combinations.
	 */
	public PreparedInput prepare(String term, SchedulePreferences preferences, boolean includeClosedSections,
			int maxResults, Set<String> required, Map<String, CourseSummary> courses, List<CatalogSection> sections) {
		Map<String, CatalogSection> eligible = new TreeMap<>();
		for (var section : sections) {
			if (!term.equals(section.term()) || !courses.containsKey(section.courseId())) {
				continue;
			}
			if (section.crn() == null || section.crn().isBlank()
					|| section.componentType() == null || section.componentType().isBlank() || section.status() == null) {
				throw new IllegalStateException("Incomplete catalog section metadata");
			}
			// lectureLink is the lecture's CRN, or null for a lecture; blank or self links are malformed.
			NativeSchedulingEngine.parentCrn(section.crn(), section.lectureLink());
			var previous = eligible.putIfAbsent(section.crn(), section);
			if (previous != null && !previous.equals(section)) {
				throw new IllegalStateException("Conflicting catalog records for a section");
			}
		}
		if (!includeClosedSections) {
			eligible.keySet().retainAll(usableWithoutClosed(eligible.values()));
		}
		var inputs = courses.keySet().stream().sorted()
			.map(id -> new SchedulingEngine.Course(id, required.contains(id), eligible.values().stream()
				.filter(section -> id.equals(section.courseId())).map(this::toSection).toList()))
			.toList();
		var hard = preferences.hardConstraints();
		var constraints = new SchedulingEngine.Constraints(hard.minCredits(), hard.maxCredits(),
			hard.blockedTimes().stream().map(block -> new SchedulingEngine.Meeting(
				block.days().stream().distinct().sorted().toList(), block.start(), block.end())).toList());
		var soft = preferences.softPreferences() == null ? SoftPreferences.none() : preferences.softPreferences();
		var weights = new SchedulingEngine.SoftWeights(
			soft.optionalCourseIds() == null ? 0 : soft.optionalCourseIds().weight(),
			soft.earliestStart() == null ? null : soft.earliestStart().value(),
			soft.earliestStart() == null ? 0 : soft.earliestStart().weight(),
			soft.latestEnd() == null ? null : soft.latestEnd().value(),
			soft.latestEnd() == null ? 0 : soft.latestEnd().weight(),
			soft.daysOff() == null ? List.of() : soft.daysOff().value().stream().distinct().sorted().toList(),
			soft.daysOff() == null ? 0 : soft.daysOff().weight(),
			soft.minimizeGaps() == null ? 0 : soft.minimizeGaps().weight());
		return new PreparedInput(new SchedulingEngine.Problem(inputs, constraints, weights, maxResults), courses, eligible);
	}

	public GenerateScheduleResponse response(String term, PreparedInput input, SchedulingEngine.Result result) {
		List<ScheduleResponse> schedules = new ArrayList<>();
		for (var schedule : result.schedules()) {
			var sections = schedule.sectionCrns().stream().map(crn -> sectionResponse(input, crn)).toList();
			schedules.add(new ScheduleResponse(schedules.size() + 1, Objects.requireNonNull(schedule.score()),
				schedule.totalCredits(), CreditLimits.isOverload(schedule.totalCredits()), sections,
				Objects.requireNonNull(schedule.scoreBreakdown())));
		}
		var advice = result.electiveAdvice().stream()
			.map(a -> new GenerateScheduleResponse.ElectiveAdviceResponse(a.courseId(), a.status(), a.summary(), a.fixes(),
				a.alternativeCrns()))
			.toList();
		return new GenerateScheduleResponse(term, schedules, result.diagnostics(), result.searchLimitReached(), advice);
	}

	public CheckScheduleResponse checkResponse(String term, PreparedInput input, SchedulingEngine.Check check) {
		return new CheckScheduleResponse(term,
			check.sectionCrns().stream().map(crn -> sectionResponse(input, crn)).toList(),
			check.totalCredits(), CreditLimits.isOverload(check.totalCredits()),
			Objects.requireNonNull(check.score()), Objects.requireNonNull(check.scoreBreakdown()), check.complete(),
			check.issues().stream()
				.map(i -> new CheckScheduleResponse.IssueResponse(i.type(), i.severity(), i.crns(), i.message()))
				.toList(),
			check.candidates().stream()
				.map(c -> new CheckScheduleResponse.CandidateResponse(c.crn(), c.courseId(), c.fits(), c.completesLecture(),
					c.reasons()))
				.toList());
	}

	public SuggestElectivesResponse suggestionsResponse(String term, PreparedInput pool,
			List<SchedulingEngine.ElectiveFit> fits) {
		return new SuggestElectivesResponse(term, fits.stream()
			.map(f -> new SuggestElectivesResponse.Suggestion(f.courseId(), pool.courses().get(f.courseId()).title(),
				f.crns().stream().map(crn -> sectionResponse(pool, crn)).toList(), f.totalCredits(),
				CreditLimits.isOverload(f.totalCredits()), Objects.requireNonNull(f.score()),
				Objects.requireNonNull(f.scoreBreakdown())))
			.toList());
	}

	/** Display data for one section of the prepared input; a CRN outside it is an integration failure. */
	SectionResponse sectionResponse(PreparedInput input, String crn) {
		var section = input.sections().get(crn);
		if (section == null) {
			throw new IllegalStateException("Engine returned a section outside the prepared input");
		}
		return new SectionResponse(section.crn(), section.courseId(), section.sectionCode(),
			input.courses().get(section.courseId()).title(), section.credits(),
			section.meetings().stream().map(meeting -> new MeetingResponse(
				meeting.days().stream().distinct().sorted().toList(), meeting.start(), meeting.end(), meeting.location()))
				.toList());
	}

	/**
	 * CRNs left once closed sections are removed, using the engine's own rule
	 * ({@link CourseOption#excluding}): a lecture that loses every linked section of one component
	 * type (e.g. all its labs are closed) goes too, since it can't be taken without one, and so do
	 * the remaining linked sections of a removed lecture.
	 */
	private static Set<String> usableWithoutClosed(Collection<CatalogSection> sections) {
		Map<String, List<SectionOption>> byCourse = new TreeMap<>();
		Set<String> closed = new HashSet<>();
		for (var section : sections) {
			if (section.status() == CatalogSection.Status.CLOSED) {
				closed.add(section.crn());
			}
			byCourse.computeIfAbsent(section.courseId(), id -> new ArrayList<>())
				.add(new SectionOption(section.crn(), section.courseId(), section.componentType(),
					NativeSchedulingEngine.parentCrn(section.crn(), section.lectureLink()), 0, List.of()));
		}
		// Used only for membership checks, never iterated.
		Set<String> usable = new HashSet<>();
		try {
			byCourse.forEach((id, list) -> new CourseOption(id, true, list)
				.excluding(section -> closed.contains(section.crn()))
				.sections().forEach(section -> usable.add(section.crn())));
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalStateException("Invalid catalog lecture links", ex);
		}
		return usable;
	}

	private SchedulingEngine.Section toSection(CatalogSection section) {
		return new SchedulingEngine.Section(section.crn(), section.courseId(), section.credits(),
			section.componentType(), section.lectureLink(), section.meetings().stream().map(meeting -> {
				if (meeting.days() == null || meeting.days().isEmpty() || meeting.days().stream().anyMatch(Objects::isNull)
						|| meeting.start() == null || meeting.end() == null || !meeting.start().isBefore(meeting.end())) {
					throw new IllegalStateException("Invalid catalog meeting; unscheduled sections must use an empty list");
				}
				return new SchedulingEngine.Meeting(meeting.days().stream().distinct().sorted().toList(),
					meeting.start(), meeting.end());
			}).toList());
	}

	public record PreparedInput(SchedulingEngine.Problem problem, Map<String, CourseSummary> courses,
			Map<String, CatalogSection> sections) {}
}
