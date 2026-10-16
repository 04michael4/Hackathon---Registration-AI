package io.github.michael04.registration.course;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Proves exactly one {@link CourseCatalog} is active for each {@code app.catalog.source} value,
 * so the dummy can't collide with the future database-backed catalog.
 */
class CourseCatalogSelectionTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(DummyCourseCatalog.class, DatabaseCatalogStandIn.class);

	@Test
	void dummyCatalogIsUsedWhenNothingIsConfigured() {
		runner.run(ctx -> assertThat(ctx)
			.hasSingleBean(CourseCatalog.class)
			.hasSingleBean(DummyCourseCatalog.class));
	}

	@Test
	void dummyCatalogIsUsedWhenExplicitlySelected() {
		runner.withPropertyValues("app.catalog.source=dummy")
			.run(ctx -> assertThat(ctx)
				.hasSingleBean(CourseCatalog.class)
				.hasSingleBean(DummyCourseCatalog.class));
	}

	@Test
	void databaseSelectionReplacesTheDummyWithoutConflict() {
		runner.withPropertyValues("app.catalog.source=database")
			.run(ctx -> assertThat(ctx)
				.hasSingleBean(CourseCatalog.class)
				.hasSingleBean(DatabaseCatalogStandIn.class)
				.doesNotHaveBean(DummyCourseCatalog.class));
	}

	@Test
	void applicationDefaultSelectsTheDummy() {
		// Mirrors application.properties: app.catalog.source=${CATALOG_SOURCE:dummy}
		runner.withPropertyValues("app.catalog.source=${CATALOG_SOURCE:dummy}")
			.run(ctx -> assertThat(ctx).hasSingleBean(DummyCourseCatalog.class));
	}

	/**
	 * Test-only stand-in for David's future database-backed catalog. It shows the annotation
	 * the real class needs; it is not database code.
	 */
	@ConditionalOnProperty(name = CourseCatalog.SOURCE_PROPERTY, havingValue = "database")
	static class DatabaseCatalogStandIn implements CourseCatalog {

		@Override
		public boolean termExists(String term) {
			return false;
		}

		@Override
		public Optional<CourseSummary> findCourse(String term, String courseId) {
			return Optional.empty();
		}

		@Override
		public List<CourseSummary> listCourses(String term) {
			return List.of();
		}

	}

}
