package io.github.michael04.registration.course.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The condition catalog only ever reads catalog_serving, and returns nothing (without touching a database) for the other schemas. */
class JdbcConditionCatalogTest {

	private static final TermMapping TERMS = TermMapping.of(Map.of("2027-SPRING", "202710"));

	@Test
	void theQueryReadsOnlyTheServingConditionViewAndIsParameterized() {
		String sql = JdbcConditionCatalog.CONDITIONS;
		assertThat(sql).startsWith("SELECT").contains("catalog_serving.course_condition").contains("term_code = ?").contains("course_id = ?");
		assertThat(sql).doesNotContain("catalog_research", "catalog_live", "catalog_api", "cse007_snapshot", "'");
		assertThat(sql.toUpperCase()).doesNotContain("INSERT ", "UPDATE ", "DELETE ", "DROP ", "ALTER ", "TRUNCATE ", "CREATE ", ";");
	}

	@Test
	void otherSchemasHaveNoConditionsAndNeverQuery() {
		for (String schema : List.of("catalog_api", "catalog_live")) {
			// A null JdbcClient would throw if the catalog tried to query.
			assertThat(new JdbcConditionCatalog(null, TERMS, schema).conditionsFor("2027-SPRING", "CSE-017")).isEmpty();
		}
	}

	@Test
	void anUnknownTermOrBlankCourseHasNoConditions() {
		var catalog = new JdbcConditionCatalog(null, TERMS, "catalog_serving");
		assertThat(catalog.conditionsFor("2099-FALL", "CSE-017")).isEmpty();
		assertThat(catalog.conditionsFor("202710", "CSE-017")).isEmpty();   // a Banner code is not an API term
		assertThat(catalog.conditionsFor("2027-SPRING", " ")).isEmpty();
		assertThat(catalog.conditionsFor("2027-SPRING", null)).isEmpty();
	}

}
