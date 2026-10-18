package io.github.michael04.registration.course.persistence;

import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.michael04.registration.course.ConditionCatalog;
import io.github.michael04.registration.course.CourseCondition;

/**
 * Reads {@code catalog_serving.course_condition}. Only that schema has conditions: for {@code catalog_api} and {@code catalog_live} this
 * returns an empty list without touching the database.
 */
final class JdbcConditionCatalog implements ConditionCatalog {

	static final String SERVING_SCHEMA = "catalog_serving";

	static final String CONDITIONS = "SELECT code, message, evidence_level, outcome FROM catalog_serving.course_condition "
			+ "WHERE term_code = ? AND course_id = ? ORDER BY seq, message";

	private final JdbcClient jdbc;

	private final TermMapping mapping;

	private final boolean enabled;

	JdbcConditionCatalog(JdbcClient jdbc, TermMapping mapping, String schema) {
		this.jdbc = jdbc;
		this.mapping = mapping;
		this.enabled = SERVING_SCHEMA.equals(schema);
	}

	@Override
	public List<CourseCondition> conditionsFor(String term, String courseId) {
		if (!enabled) {
			return List.of();
		}
		var banner = mapping.banner(term);
		if (banner.isEmpty() || courseId == null || courseId.isBlank()) {
			return List.of();
		}
		try {
			return jdbc.sql(CONDITIONS).param(banner.get()).param(courseId.trim())
				.query((rs, row) -> new CourseCondition(rs.getString("code"), rs.getString("message"),
						rs.getString("evidence_level"), rs.getString("outcome")))
				.list();
		}
		catch (DataAccessException ex) {
			throw new CatalogUnavailableException();
		}
	}

}
