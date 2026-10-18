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
import io.github.michael04.registration.course.YearRating;

/**
 * Reads {@code catalog_serving.course_rating} and {@code catalog_serving.section_instructor}, plus the yearly history in
 * {@code professor_ratings.instructor_year_rating} (see {@link #YEARLY_HISTORY}). Only the serving schema has ratings: for the others
 * every method returns an empty result without touching the database. If the views cannot be read (for example ratings are not installed)
 * the result is empty too, because ratings are supplementary and must never take the course endpoints down.
 */
final class JdbcRatingCatalog implements RatingCatalog {

	private static final Logger log = LoggerFactory.getLogger(JdbcRatingCatalog.class);

	static final String COURSE_RATINGS = "SELECT course_id, pred_quality, pred_difficulty, confidence, sections_predicted, sections "
			+ "FROM catalog_serving.course_rating WHERE term_code = ? AND course_id = ANY(?)";

	static final String INSTRUCTORS = "SELECT crn, instructor_name, pred_quality, pred_difficulty, confidence, rating_count, course_specific "
			+ "FROM catalog_serving.section_instructor WHERE term_code = ? AND course_id = ? ORDER BY crn, instructor_name";

	/**
	 * The one read outside catalog_serving: professor_ratings' yearly trend view, which the read-only role can already SELECT. Joined on the
	 * Banner name that catalog_serving.section_instructor returns. A catalog_serving view over it would keep the single-schema rule.
	 */
	static final String YEARLY_HISTORY = "SELECT banner_name, term_year, avg_quality, avg_difficulty, num_ratings, sample_note "
			+ "FROM professor_ratings.instructor_year_rating WHERE banner_name = ANY(?) ORDER BY banner_name, term_year";

	private static final int MAX_HISTORY_NAMES = 100;

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

	@Override
	public Map<String, List<YearRating>> yearlyHistory(List<String> instructorNames) {
		List<String> names = instructorNames == null ? List.of()
				: instructorNames.stream().filter(n -> n != null && !n.isBlank()).distinct().limit(MAX_HISTORY_NAMES).toList();
		if (!enabled || names.isEmpty()) {
			return Map.of();
		}
		try {
			Map<String, List<YearRating>> out = new TreeMap<>();
			jdbc.sql(YEARLY_HISTORY).param(names.toArray(String[]::new)).query((rs, row) -> {
				out.computeIfAbsent(rs.getString("banner_name"), name -> new ArrayList<>())
					.add(new YearRating(rs.getInt("term_year"), rs.getBigDecimal("avg_quality"), rs.getBigDecimal("avg_difficulty"),
							rs.getInt("num_ratings"), "LOW_SAMPLE".equals(rs.getString("sample_note"))));
				return row;
			}).list();
			out.replaceAll((name, years) -> List.copyOf(years));
			return Map.copyOf(out);
		}
		catch (DataAccessException ex) {
			log.warn("Rating history unavailable ({})", ex.getClass().getSimpleName());
			return Map.of();
		}
	}

}
