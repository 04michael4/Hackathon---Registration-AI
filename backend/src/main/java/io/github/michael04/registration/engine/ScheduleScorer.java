package io.github.michael04.registration.engine;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Soft-preference scoring, as documented in docs/architecture.md § 7.
 *
 * <p>Each preference's normalized penalty {@code r ∈ [0, 1]} is held as an exact integer number of
 * units, {@code r × SCALE}. {@code SCALE} is divisible by 300, 600 and every count from 1 to 10, so
 * no term depends on floating-point rounding. A preference shows {@code round(4 × weight × r)}
 * points (0–20).
 *
 * <p>Schedules are ranked by {@link #rankKey}: displayed points first, so a better rank never shows
 * a lower score, then the exact weighted units to break ties that rounding created. Both parts
 * only grow as sections are added (gaps excepted, which a partial schedule passes as 0), so the
 * key of a partial schedule is a lower bound for every completion of it.
 */
final class ScheduleScorer {

	static final int SCALE = 12_600;
	static final int TIME_CAP_MINUTES = 300;
	static final int GAP_CAP_MINUTES = 600;

	private static final int POINTS_PER_WEIGHT = 4;
	/** Larger than the biggest possible exact value, 5 preferences × weight 5 × SCALE. */
	private static final long EXACT_RANGE = 1_000_000;

	private final SoftWeights soft;
	private final int optionalCount;
	private final int daysOffMask;

	ScheduleScorer(SoftWeights soft, int optionalCount) {
		this.soft = soft;
		this.optionalCount = optionalCount;
		this.daysOffMask = dayMask(soft.daysOff());
	}

	static int dayMask(Iterable<DayOfWeek> days) {
		int mask = 0;
		for (DayOfWeek day : days) {
			mask |= 1 << day.ordinal();
		}
		return mask;
	}

	/** Weekly minutes by which this meeting starts before {@code earliestStart}. */
	int earlyMinutes(Meeting meeting) {
		if (soft.earliestStart() == null || !meeting.start().isBefore(soft.earliestStart())) {
			return 0;
		}
		return minutesBetween(meeting.start(), soft.earliestStart()) * meeting.days().size();
	}

	/** Weekly minutes by which this meeting ends after {@code latestEnd}. */
	int lateMinutes(Meeting meeting) {
		if (soft.latestEnd() == null || !meeting.end().isAfter(soft.latestEnd())) {
			return 0;
		}
		return minutesBetween(soft.latestEnd(), meeting.end()) * meeting.days().size();
	}

	/** Lower is better. See the class comment. */
	long rankKey(int earlyMinutes, int lateMinutes, int dayMask, int gapMinutes, int skippedOptional) {
		int[] units = units(earlyMinutes, lateMinutes, dayMask, gapMinutes, skippedOptional);
		int[] weights = weights();
		long points = 0;
		long exact = 0;
		for (int i = 0; i < units.length; i++) {
			points += points(weights[i], units[i]);
			exact += (long) weights[i] * units[i];
		}
		return points * EXACT_RANGE + exact;
	}

	/** Points (0–20) per active preference, keyed by {@code SoftWeights.*_KEY}. */
	SortedMap<String, Integer> breakdown(int earlyMinutes, int lateMinutes, int dayMask, int gapMinutes,
			int skippedOptional) {
		int[] units = units(earlyMinutes, lateMinutes, dayMask, gapMinutes, skippedOptional);
		int[] weights = weights();
		SortedMap<String, Integer> breakdown = new TreeMap<>();
		for (int i = 0; i < units.length; i++) {
			if (weights[i] > 0) {
				breakdown.put(KEYS[i], points(weights[i], units[i]));
			}
		}
		return breakdown;
	}

	/** Weekly idle minutes between consecutive classes on the same day. */
	static int gapMinutes(List<Meeting> meetings) {
		int total = 0;
		for (DayOfWeek day : DayOfWeek.values()) {
			List<Meeting> onDay = meetings.stream()
				.filter(m -> m.days().contains(day))
				.sorted((a, b) -> a.start().compareTo(b.start()))
				.toList();
			for (int i = 1; i < onDay.size(); i++) {
				total += Math.max(0, minutesBetween(onDay.get(i - 1).end(), onDay.get(i).start()));
			}
		}
		return total;
	}

	private static final String[] KEYS = { SoftWeights.EARLIEST_START_KEY, SoftWeights.LATEST_END_KEY,
			SoftWeights.DAYS_OFF_KEY, SoftWeights.GAPS_KEY, SoftWeights.OPTIONAL_COURSES_KEY };

	/** Weights in {@link #KEYS} order; a preference without a value counts as weight 0. */
	private int[] weights() {
		return new int[] {
				soft.earliestStart() == null ? 0 : soft.earliestStartWeight(),
				soft.latestEnd() == null ? 0 : soft.latestEndWeight(),
				soft.daysOff().isEmpty() ? 0 : soft.daysOffWeight(),
				soft.gapsWeight(),
				optionalCount == 0 ? 0 : soft.optionalCoursesWeight() };
	}

	/** Normalized penalties {@code r × SCALE} in {@link #KEYS} order. */
	private int[] units(int earlyMinutes, int lateMinutes, int dayMask, int gapMinutes, int skippedOptional) {
		int daysOff = soft.daysOff().size();
		return new int[] {
				Math.min(SCALE, earlyMinutes * (SCALE / TIME_CAP_MINUTES)),
				Math.min(SCALE, lateMinutes * (SCALE / TIME_CAP_MINUTES)),
				daysOff == 0 ? 0 : Integer.bitCount(dayMask & daysOffMask) * (SCALE / daysOff),
				Math.min(SCALE, gapMinutes * (SCALE / GAP_CAP_MINUTES)),
				optionalCount == 0 ? 0 : skippedOptional * (SCALE / optionalCount) };
	}

	/** {@code round(4 × weight × units / SCALE)}, rounding half up. */
	private static int points(int weight, int units) {
		return (POINTS_PER_WEIGHT * weight * units + SCALE / 2) / SCALE;
	}

	private static int minutesBetween(LocalTime from, LocalTime to) {
		return (int) Duration.between(from, to).toMinutes();
	}

}
