package io.github.michael04.registration.course;

import java.math.BigDecimal;

/**
 * An instructor's average student rating in one calendar year, as stored (never predicted, never smoothed).
 *
 * @param year the calendar year the ratings were given in
 * @param quality average quality, 1.00 to 5.00 (5 is best)
 * @param difficulty average difficulty, 1.00 to 5.00 (5 is hardest)
 * @param ratings how many ratings the averages rest on
 * @param lowSample true when the source marks the year as a low sample, so a reader should not trust a change on its own
 */
public record YearRating(int year, BigDecimal quality, BigDecimal difficulty, int ratings, boolean lowSample) {
}
