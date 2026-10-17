package io.github.michael04.registration.course.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.github.michael04.registration.course.CourseCatalog;
import io.github.michael04.registration.course.CourseReadCatalog;
import io.github.michael04.registration.course.DummyCourseCatalog;
import io.github.michael04.registration.course.SectionCatalog;

class CatalogPersistenceConfigurationTest {
	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(CatalogPersistenceConfiguration.class, DummyCourseCatalog.class);

	@Test void databaseModeProvidesOneAuthoritativeCatalogWithoutOpeningAConnectionAtStartup() {
		runner.withPropertyValues("app.catalog.source=database", "app.catalog.database.url=jdbc:postgresql://127.0.0.1:1/unreachable")
			.run(context -> {
				assertThat(context).hasNotFailed().hasSingleBean(CourseCatalog.class).hasSingleBean(SectionCatalog.class)
					.hasSingleBean(CourseReadCatalog.class).doesNotHaveBean(DummyCourseCatalog.class);
				assertThat(context.getBean(CourseCatalog.class)).isSameAs(context.getBean(SectionCatalog.class));
			});
	}
	@Test void developmentModeDoesNotRequireDatabaseProperties() {
		runner.withPropertyValues("app.catalog.source=dummy").run(context -> assertThat(context).hasNotFailed()
			.hasSingleBean(DummyCourseCatalog.class).doesNotHaveBean(CourseReadCatalog.class));
	}
	@Test void databaseModeRejectsMissingUrl() {
		runner.withPropertyValues("app.catalog.source=database").run(context -> assertThat(context).hasFailed());
	}
	@ParameterizedTest @ValueSource(strings = {"", "other.schema", "public;drop table course", "Uppercase", "1schema"})
	void schemaNamesCannotChangeTheConnectionSearchPath(String schema) {
		assertThatThrownBy(() -> new CatalogDatabaseProperties("jdbc:postgresql://localhost/test", "user", "secret", schema))
			.isInstanceOf(IllegalArgumentException.class);
	}
	@Test void propertyDiagnosticsNeverPrintCredentialsOrUrl() {
		assertThat(new CatalogDatabaseProperties("jdbc:postgresql://localhost/db?password=url-secret", "user-secret", "password-secret", "test").toString())
			.doesNotContain("url-secret", "user-secret", "password-secret");
	}
}
