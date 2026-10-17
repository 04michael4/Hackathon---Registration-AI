package io.github.michael04.registration.schedule.integration;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.EnumSet;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

import io.github.michael04.registration.engine.BacktrackingScheduleSolver;
import io.github.michael04.registration.engine.CourseOption;
import io.github.michael04.registration.engine.ScheduleProblem;
import io.github.michael04.registration.engine.ScheduleResult;
import io.github.michael04.registration.engine.SectionOption;
import io.github.michael04.registration.engine.SelectionChecker;
import io.github.michael04.registration.engine.SelectionReport;

/**
 * Adapter from the API's {@link SchedulingEngine} boundary to the native engine
 * ({@link BacktrackingScheduleSolver}). Pure translation: no search, scoring or filtering here.
 *
 * <p>{@code Section.lectureLink} is the CRN of the lecture a lab or recitation must be taken with,
 * and {@code null} for a lecture or standalone section. A blank link, or one pointing at the section
 * itself, is malformed catalog data and is rejected rather than read as "standalone". A link to a CRN
 * that isn't in the input (e.g. a closed lecture) makes that section unusable; the engine never picks it.
 *
 * <p>Catalog data the engine rejects (e.g. duplicate CRNs, a lab linked to another lab) throws
 * {@link IllegalStateException}, which the shared advice renders as a safe 500.
 *
 * <p>The boundary's nested records ({@code Constraints}, {@code SoftWeights}, {@code Meeting}) shadow
 * the engine's records of the same names here, so the engine's are written out in full.
 */
@Component
public class NativeSchedulingEngine implements SchedulingEngine {

	/** Engine-owned validation/scoring for an existing selection, used before an AI explanation. */
	public SelectionReport checkSelection(Problem problem, Collection<String> crns) {
		try { return new SelectionChecker().check(toNative(problem), crns); }
		catch (IllegalArgumentException ex) {
			throw new IllegalStateException("Catalog data rejected by the scheduling engine", ex);
		}
	}

	@Override
	public Result solve(Problem problem, int nodeCap) {
		ScheduleProblem nativeProblem;
		try {
			nativeProblem = toNative(problem);
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalStateException("Catalog data rejected by the scheduling engine", ex);
		}
		return fromNative(new BacktrackingScheduleSolver(nodeCap).solve(nativeProblem));
	}

	/**
	 * The engine's parent CRN for a catalog lecture link: {@code null} stays {@code null} (a lecture or
	 * standalone section). A blank or self-referencing link is malformed and throws
	 * {@link IllegalStateException}, so it can't silently turn a lab into a standalone section.
	 */
	public static String parentCrn(String crn, String lectureLink) {
		if (lectureLink != null && (lectureLink.isBlank() || lectureLink.equals(crn))) {
			throw new IllegalStateException("Invalid catalog lecture link");
		}
		return lectureLink;
	}

	private static ScheduleProblem toNative(Problem problem) {
		List<CourseOption> courses = problem.courses().stream()
			.map(course -> new CourseOption(course.courseId(), course.required(),
					course.sections().stream().map(NativeSchedulingEngine::toNative).toList()))
			.toList();
		var constraints = new io.github.michael04.registration.engine.Constraints(problem.constraints().minCredits(),
				problem.constraints().maxCredits(),
				problem.constraints().blockedTimes().stream().map(NativeSchedulingEngine::toNative).toList());
		SoftWeights soft = problem.soft();
		var weights = new io.github.michael04.registration.engine.SoftWeights(
				soft.earliestStart(), soft.earliestStartWeight(),
				soft.latestEnd(), soft.latestEndWeight(),
				soft.daysOff().isEmpty() ? EnumSet.noneOf(DayOfWeek.class) : EnumSet.copyOf(soft.daysOff()),
				soft.daysOffWeight(),
				soft.gapsWeight(),
				soft.optionalCourseWeight());
		return new ScheduleProblem(courses, constraints, weights, problem.maxResults());
	}

	private static SectionOption toNative(Section section) {
		return new SectionOption(section.crn(), section.courseId(), section.componentType(),
				parentCrn(section.crn(), section.lectureLink()), section.credits(),
				section.meetings().stream().map(NativeSchedulingEngine::toNative).toList());
	}

	private static io.github.michael04.registration.engine.Meeting toNative(Meeting meeting) {
		return new io.github.michael04.registration.engine.Meeting(EnumSet.copyOf(meeting.days()), meeting.start(),
				meeting.end());
	}

	private static Result fromNative(ScheduleResult result) {
		List<ScoredSchedule> schedules = result.schedules().stream()
			.map(schedule -> {
				Map<String, BigDecimal> breakdown = new TreeMap<>();
				schedule.breakdown().forEach((key, points) -> breakdown.put(key, BigDecimal.valueOf(points)));
				return new ScoredSchedule(
						schedule.sections().stream().map(SectionOption::crn).toList(),
						schedule.totalCredits(),
						BigDecimal.valueOf(schedule.score()),
						breakdown);
			})
			.toList();
		return new Result(schedules, result.diagnostics(), result.searchLimitReached());
	}

}
