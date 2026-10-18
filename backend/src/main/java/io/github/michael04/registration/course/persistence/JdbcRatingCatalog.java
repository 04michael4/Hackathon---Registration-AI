package io.github.michael04.registration.course.persistence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.michael04.registration.course.InstructorRating;
import io.github.michael04.registration.course.PredictedRating;
import io.github.michael04.registration.course.RatingCatalog;

/**
 * Reads {@code catalog_serving.course_rating} and {@code catalog_serving.section_instructor}. Only that schema has them: for the others
 * every method returns an empty result without touching the database. If the views cannot be read (for example ratings are not installed)
 * the result is empty too, because ratings are supplementary and must never take the course endpoints down.
 */
final class JdbcRatingCatalog implements RatingCatalog {

	private static final Logger log = LoggerFactory.getLogger(JdbcRatingCatalog.class);

	static final String COURSE_RATINGS = "SELECT course_id, pred_quality, pred_difficulty, confidence, sections_predicted, sections "
			+ "FROM catalog_serving.course_rating WHERE term_code = ? AND course_id = ANY(?)";

	static final String INSTRUCTORS = "SELECT crn, instructor_name, pred_quality, pred_difficulty, confidence, rating_count, course_specific "
			+ "FROM catalog_serving.section_instructor WHERE term_code = ? AND course_id = ? ORDER BY crn, instructor_name";

	private final JdbcClient jdbc;

	private final TermMapping mapping;

	private final boolean enabled;

	JdbcRatingCatalog(JdbcClient jdbc, TermMapping mapping, String schema) {
		this.jdbc = jdbc;
		this.mapping = mapping;
		this.enabled = JdbcConditionCatalog.SERVING_SCHEMA.equals(schema);
	}

	@Override
	public Map<String, PredictedRating> courseRatings(String term, List<String> courseIds) {
		var banner = mapping.banner(term);
		if (!enabled || banner.isEmpty() || courseIds == null || courseIds.isEmpty()) {
			return Map.of();
		}
		try {
			Map<String, PredictedRating> out = new TreeMap<>();
			jdbc.sql(COURSE_RATINGS).param(banner.get()).param(courseIds.toArray(String[]::new))
				.query((rs, row) -> out.put(rs.getString("course_id"),
						new PredictedRating(rs.getBigDecimal("pred_quality"), rs.getBigDecimal("pred_difficulty"), rs.getString("confidence"),
								"PREDICTED", rs.getInt("sections_predicted"), rs.getInt("sections"))))
				.list();
			return Map.copyOf(out);
		}
		catch (DataAccessException ex) {
			log.warn("Predicted ratings unavailable ({})", ex.getClass().getSimpleName());
			return Map.of();
		}
	}

	@Override
	public Map<String, List<InstructorRating>> instructorsByCrn(String term, String courseId) {
		var banner = mapping.banner(term);
		if (!enabled || banner.isEmpty() || courseId == null || courseId.isBlank()) {
			return Map.of();
		}
		try {
			Map<String, List<InstructorRating>> out = new LinkedHashMap<>();
			jdbc.sql(INSTRUCTORS).param(banner.get()).param(courseId.trim()).query((rs, row) -> {
				Object count = rs.getObject("rating_count");
				out.computeIfAbsent(rs.getString("crn"), crn -> new ArrayList<>())
					.add(new InstructorRating(rs.getString("instructor_name"), rs.getBigDecimal("pred_quality"), rs.getBigDecimal("pred_difficulty"),
							rs.getString("confidence"), count == null ? null : ((Number) count).intValue(), rs.getBoolean("course_specific")));
				return rs.getString("crn");
			}).list();
			out.replaceAll((crn, list) -> List.copyOf(list));
			return Map.copyOf(out);
		}
		catch (DataAccessException ex) {
			log.warn("Instructors unavailable ({})", ex.getClass().getSimpleName());
			return Map.of();
		}
	}

}
