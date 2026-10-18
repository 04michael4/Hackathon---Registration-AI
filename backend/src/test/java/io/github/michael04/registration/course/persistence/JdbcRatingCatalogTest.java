package io.github.michael04.registration.course.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The rating catalog only reads two catalog_serving views, and returns nothing (without touching a database) elsewhere. */
class JdbcRatingCatalogTest {

	private static final TermMapping TERMS = TermMapping.of(Map.of("2027-SPRING", "202710"));

	@Test
	void theQueriesReadOnlyTheServingRatingViewsAndAreParameterized() {
		for (String sql : List.of(JdbcRatingCatalog.COURSE_RATINGS, JdbcRatingCatalog.INSTRUCTORS)) {
			assertThat(sql).startsWith("SELECT").contains("term_code = ?");
			assertThat(sql).doesNotContain("professor_ratings", "catalog_research", "catalog_live", "catalog_api", "'");
			assertThat(sql.toUpperCase()).doesNotContain("INSERT ", "UPDATE ", "DELETE ", "DROP ", "ALTER ", "TRUNCATE ", "CREATE ", ";");
		}
		assertThat(JdbcRatingCatalog.COURSE_RATINGS).contains("catalog_serving.course_rating");
		assertThat(JdbcRatingCatalog.INSTRUCTORS).contains("catalog_serving.section_instructor");
	}

	@Test
	void otherSchemasHaveNoRatingsAndNeverQuery() {
		for (String schema : List.of("catalog_api", "catalog_live")) {
			var catalog = new JdbcRatingCatalog(null, TERMS, schema);   // a null JdbcClient would throw if it queried
			assertThat(catalog.courseRatings("2027-SPRING", List.of("CSE-017"))).isEmpty();
			assertThat(catalog.instructorsByCrn("2027-SPRING", "CSE-017")).isEmpty();
		}
	}

	@Test
	void anUnknownTermAnEmptyListOrABlankCourseHasNoRatings() {
		var catalog = new JdbcRatingCatalog(null, TERMS, "catalog_serving");
		assertThat(catalog.courseRatings("2099-FALL", List.of("CSE-017"))).isEmpty();
		assertThat(catalog.courseRatings("2027-SPRING", List.of())).isEmpty();
		assertThat(catalog.instructorsByCrn("202710", "CSE-017")).isEmpty();     // a Banner code is not an API term
		assertThat(catalog.instructorsByCrn("2027-SPRING", " ")).isEmpty();
	}

}
