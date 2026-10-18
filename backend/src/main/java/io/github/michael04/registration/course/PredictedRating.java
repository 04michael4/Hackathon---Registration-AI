package io.github.michael04.registration.course;

import java.math.BigDecimal;

/**
 * A rating PREDICTED for the term from students' opinions in earlier terms (Rate My Professors and, later, this app). It is an estimate,
 * not a rating of the course itself: show it as predicted, with its confidence.
 *
 * @param quality predicted quality, 1.00 to 5.00 (5 is best)
 * @param difficulty predicted difficulty, 1.00 to 5.00 (5 is hardest)
 * @param confidence {@code LOW}, {@code MEDIUM} or {@code HIGH}: how much earlier evidence the estimate rests on (the lowest among the
 *        instructors it averages)
 * @param basis always {@code PREDICTED}
 * @param sectionsPredicted lecture sections of the course whose instructors have earlier ratings
 * @param sections lecture sections of the course (a section without a rated instructor adds no value to the average)
 */
public record PredictedRating(BigDecimal quality, BigDecimal difficulty, String confidence, String basis, int sectionsPredicted,
		int sections) {
}
