package io.github.michael04.registration.course;

import java.math.BigDecimal;

/**
 * One instructor of a section and what is PREDICTED for them this term (see {@link PredictedRating}).
 *
 * @param name the instructor as Banner lists them ("Last, First")
 * @param predictedQuality {@code null} when the instructor has no earlier rating (never a made-up number)
 * @param predictedDifficulty {@code null} together with the quality
 * @param confidence {@code LOW}, {@code MEDIUM}, {@code HIGH}; {@code null} without a prediction
 * @param ratingCount earlier ratings the prediction rests on; {@code null} without a prediction
 * @param courseSpecific true when students rated this instructor for this very course
 */
public record InstructorRating(String name, BigDecimal predictedQuality, BigDecimal predictedDifficulty, String confidence,
		Integer ratingCount, boolean courseSpecific) {
}
