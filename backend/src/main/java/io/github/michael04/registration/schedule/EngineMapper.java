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

	public PreparedInput prepare(GenerateScheduleRequest request, Set<String> required,
			Map<String, CourseSummary> courses, List<CatalogSection> sections) {
		Map<String, CatalogSection> eligible = new TreeMap<>();
		for (var section : sections) {
			if (!request.term().equals(section.term()) || !courses.containsKey(section.courseId())) {
				continue;
			}
			if (section.crn() == null || section.crn().isBlank()
					|| section.componentType() == null || section.componentType().isBlank() || section.status() == null) {
				throw new IllegalStateException("Incomplete catalog section metadata");
			}
			var previous = eligible.putIfAbsent(section.crn(), section);
			if (previous != null && !previous.equals(section)) {
				throw new IllegalStateException("Conflicting catalog records for a section");
			}
		}
		if (!request.includeClosedSections()) {
			eligible.keySet().retainAll(usableWithoutClosed(eligible.values()));
		}
		var inputs = courses.keySet().stream().sorted()
			.map(id -> new SchedulingEngine.Course(id, required.contains(id), eligible.values().stream()
				.filter(section -> id.equals(section.courseId())).map(this::toSection).toList()))
			.toList();
		if (inputs.stream().filter(course -> !course.required()).count() > 10) {
			throw new IllegalArgumentException("At most 10 optional courses may reach the engine");
		}
		var hard = request.preferences().hardConstraints();
		var constraints = new SchedulingEngine.Constraints(hard.minCredits(), hard.maxCredits(),
			hard.blockedTimes().stream().map(block -> new SchedulingEngine.Meeting(
				block.days().stream().distinct().sorted().toList(), block.start(), block.end())).toList());
		var soft = request.preferences().softPreferences() == null
			? SoftPreferences.none() : request.preferences().softPreferences();
		var weights = new SchedulingEngine.SoftWeights(
			soft.optionalCourseIds() == null ? 0 : soft.optionalCourseIds().weight(),
			soft.earliestStart() == null ? null : soft.earliestStart().value(),
			soft.earliestStart() == null ? 0 : soft.earliestStart().weight(),
			soft.latestEnd() == null ? null : soft.latestEnd().value(),
			soft.latestEnd() == null ? 0 : soft.latestEnd().weight(),
			soft.daysOff() == null ? List.of() : soft.daysOff().value().stream().distinct().sorted().toList(),
			soft.daysOff() == null ? 0 : soft.daysOff().weight(),
			soft.minimizeGaps() == null ? 0 : soft.minimizeGaps().weight());
		return new PreparedInput(new SchedulingEngine.Problem(inputs, constraints, weights, request.maxResults()),
			courses, eligible);
	}

	public GenerateScheduleResponse response(String term, PreparedInput input, SchedulingEngine.Result result) {
		List<ScheduleResponse> schedules = new ArrayList<>();
		for (var schedule : result.schedules()) {
			var sections = schedule.sectionCrns().stream().map(crn -> {
				var section = input.sections().get(crn);
				if (section == null) {
					throw new IllegalStateException("Engine returned a section outside the prepared input");
				}
				return new SectionResponse(section.crn(), section.courseId(), section.sectionCode(),
					input.courses().get(section.courseId()).title(), section.credits(),
					section.meetings().stream().map(meeting -> new MeetingResponse(
						meeting.days().stream().distinct().sorted().toList(), meeting.start(), meeting.end(), meeting.location()))
						.toList());
			}).toList();
			schedules.add(new ScheduleResponse(schedules.size() + 1, Objects.requireNonNull(schedule.score()),
				schedule.totalCredits(), CreditLimits.isOverload(schedule.totalCredits()), sections,
				Objects.requireNonNull(schedule.scoreBreakdown())));
		}
		return new GenerateScheduleResponse(term, schedules, result.diagnostics(), result.searchLimitReached());
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
