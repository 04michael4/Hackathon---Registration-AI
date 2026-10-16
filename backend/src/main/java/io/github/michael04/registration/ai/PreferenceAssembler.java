package io.github.michael04.registration.ai;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import io.github.michael04.registration.ai.RawPreferences.RawTimeBlock;
import io.github.michael04.registration.common.preferences.CreditLimits;
import io.github.michael04.registration.common.preferences.GapsPreference;
import io.github.michael04.registration.common.preferences.HardConstraints;
import io.github.michael04.registration.common.preferences.SchedulePreferences;
import io.github.michael04.registration.common.preferences.SoftPreferences;
import io.github.michael04.registration.common.preferences.TimeBlock;
import io.github.michael04.registration.common.preferences.Weighted;
import io.github.michael04.registration.common.preferences.Weights;
import io.github.michael04.registration.course.CourseSummary;

/**
 * Turns untrusted {@link RawPreferences} into validated {@link SchedulePreferences}.
 * Never throws on bad values: it drops or corrects them and explains why in {@code warnings},
 * so the student can review and fix them in the form.
 */
@Component
public class PreferenceAssembler {

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("H:mm", Locale.ROOT);

	private final CourseMentionResolver resolver;

	public PreferenceAssembler(CourseMentionResolver resolver) {
		this.resolver = resolver;
	}

	public ExtractPreferencesResponse assemble(String term, RawPreferences raw) {
		Result result = new Result();

		Set<String> required = resolveCourses(term, raw.requiredCourses(), result);
		Set<String> optional = resolveCourses(term, raw.optionalCourses(), result);
		optional.removeAll(required);
		if (required.isEmpty()) {
			result.warnings.add("No required courses were identified; add them before generating schedules.");
		}

		int[] credits = creditRange(raw.minCredits(), raw.maxCredits(), result);
		List<TimeBlock> blocked = blockedTimes(raw.blockedTimes(), result);

		SoftPreferences soft = new SoftPreferences(
				optional.isEmpty() ? null : weighted(List.copyOf(optional), raw.optionalCoursesWeight()),
				parseTime(raw.earliestStart(), "earliest start", result).map(t -> weighted(t, raw.earliestStartWeight())).orElse(null),
				parseTime(raw.latestEnd(), "latest end", result).map(t -> weighted(t, raw.latestEndWeight())).orElse(null),
				daysOff(raw.daysOff(), raw.daysOffWeight(), result),
				gaps(raw.minimizeGapsWeight()));
		soft = withoutZeroWeights(soft);

		if (raw.assumptions() != null) {
			raw.assumptions().stream().filter(a -> a != null && !a.isBlank()).map(String::trim).forEach(result.warnings::add);
		}

		SchedulePreferences preferences = new SchedulePreferences(
				new HardConstraints(List.copyOf(required), credits[0], credits[1], blocked), soft);
		return new ExtractPreferencesResponse(preferences, List.copyOf(result.unresolved), List.copyOf(result.warnings));
	}

	private Set<String> resolveCourses(String term, List<String> mentions, Result result) {
		Set<String> ids = new LinkedHashSet<>();
		if (mentions == null) {
			return ids;
		}
		for (String mention : mentions) {
			if (mention == null || mention.isBlank()) {
				continue;
			}
			CourseMentionResolver.Resolution resolution = resolver.resolve(term, mention);
			if (resolution.course().isEmpty()) {
				result.unresolved.add(resolution.mention());
				continue;
			}
			CourseSummary course = resolution.course().get();
			ids.add(course.courseId());
			if (!resolution.exactCode()) {
				result.warnings.add("Mapped '" + resolution.mention() + "' to " + course.courseId() + " (" + course.title() + ")");
			}
		}
		return ids;
	}

	private static int[] creditRange(Integer requestedMin, Integer requestedMax, Result result) {
		if (requestedMin == null && requestedMax == null) {
			result.warnings.add("Credit range not mentioned; used defaults "
					+ CreditLimits.DEFAULT_MIN + "–" + CreditLimits.DEFAULT_MAX);
		}
		// Only a minimum given ("at least 19"): raise the default maximum to meet it instead of lowering the minimum.
		boolean onlyMinimum = requestedMax == null && requestedMin != null;
		int max;
		if (requestedMax != null) {
			max = requestedMax;
		}
		else if (requestedMin != null) {
			max = Math.max(CreditLimits.DEFAULT_MAX, requestedMin);
		}
		else {
			max = CreditLimits.DEFAULT_MAX;
		}
		if (max > CreditLimits.HARD_CAP) {
			result.warnings.add("Requested " + max + " credits; capped at the " + CreditLimits.HARD_CAP + "-credit maximum");
			max = CreditLimits.HARD_CAP;
		}
		else if (max < 1) {
			result.warnings.add("Ignored invalid maximum of " + max + " credits; used " + CreditLimits.DEFAULT_MAX);
			max = CreditLimits.DEFAULT_MAX;
		}
		int min = requestedMin == null ? Math.min(CreditLimits.DEFAULT_MIN, max) : requestedMin;
		if (min < 1) {
			result.warnings.add("Ignored invalid minimum of " + min + " credits");
			min = Math.min(CreditLimits.DEFAULT_MIN, max);
		}
		if (min > max) {
			// With only a minimum, max was derived from it, so this means the minimum hit the hard cap (already warned).
			if (!onlyMinimum) {
				result.warnings.add("Minimum of " + min + " credits was above the maximum; set minimum to " + max);
			}
			min = max;
		}
		if (CreditLimits.isOverload(max)) {
			result.warnings.add("More than " + CreditLimits.NORMAL_LOAD_MAX + " credits is an overload and needs approval (more than "
					+ CreditLimits.FIRST_SEMESTER_NORMAL_LOAD_MAX + " for first-semester students)");
		}
		return new int[] { min, max };
	}

	private static List<TimeBlock> blockedTimes(List<RawTimeBlock> rawBlocks, Result result) {
		List<TimeBlock> blocks = new ArrayList<>();
		if (rawBlocks == null) {
			return blocks;
		}
		for (RawTimeBlock raw : rawBlocks) {
			if (raw == null) {
				continue;
			}
			List<DayOfWeek> days = parseDays(raw.days(), result);
			Optional<LocalTime> start = parseTime(raw.start(), "blocked-time start", result);
			Optional<LocalTime> end = parseTime(raw.end(), "blocked-time end", result);
			if (days.isEmpty() || start.isEmpty() || end.isEmpty()) {
				result.warnings.add("Ignored an incomplete blocked time");
				continue;
			}
			if (!start.get().isBefore(end.get())) {
				result.warnings.add("Ignored blocked time " + start.get() + "–" + end.get() + ": start must be before end");
				continue;
			}
			blocks.add(new TimeBlock(days, start.get(), end.get()));
		}
		return blocks;
	}

	private static Weighted<List<DayOfWeek>> daysOff(List<String> rawDays, Integer weight, Result result) {
		List<DayOfWeek> days = parseDays(rawDays, result);
		return days.isEmpty() ? null : weighted(days, weight);
	}

	private static GapsPreference gaps(Integer weight) {
		return weight == null ? null : new GapsPreference(Weights.clamp(weight));
	}

	/** Weight 0 means "ignore", so those preferences are dropped from the output. */
	private static SoftPreferences withoutZeroWeights(SoftPreferences s) {
		return new SoftPreferences(
				nonZero(s.optionalCourseIds()), nonZero(s.earliestStart()), nonZero(s.latestEnd()), nonZero(s.daysOff()),
				s.minimizeGaps() == null || s.minimizeGaps().weight() == 0 ? null : s.minimizeGaps());
	}

	private static <T> Weighted<T> nonZero(Weighted<T> w) {
		return w == null || w.weight() == 0 ? null : w;
	}

	private static <T> Weighted<T> weighted(T value, Integer weight) {
		return new Weighted<>(value, weight == null ? Weights.DEFAULT : Weights.clamp(weight));
	}

	private static List<DayOfWeek> parseDays(List<String> rawDays, Result result) {
		List<DayOfWeek> days = new ArrayList<>();
		if (rawDays == null) {
			return days;
		}
		for (String raw : rawDays) {
			if (raw == null || raw.isBlank()) {
				continue;
			}
			try {
				days.add(DayOfWeek.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
			}
			catch (IllegalArgumentException ex) {
				result.warnings.add("Ignored unrecognized day '" + raw.trim() + "'");
			}
		}
		return days.stream().distinct().sorted().toList();
	}

	private static Optional<LocalTime> parseTime(String raw, String label, Result result) {
		if (raw == null || raw.isBlank()) {
			return Optional.empty();
		}
		try {
			return Optional.of(LocalTime.parse(raw.trim(), TIME));
		}
		catch (DateTimeParseException ex) {
			result.warnings.add("Ignored invalid " + label + " time '" + raw.trim() + "' (expected HH:mm)");
			return Optional.empty();
		}
	}

	private static final class Result {

		final List<String> unresolved = new ArrayList<>();

		final List<String> warnings = new ArrayList<>();

	}

}
