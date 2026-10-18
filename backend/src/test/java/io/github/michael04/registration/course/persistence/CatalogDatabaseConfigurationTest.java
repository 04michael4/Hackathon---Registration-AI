package io.github.michael04.registration.course.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.zaxxer.hikari.HikariDataSource;

import io.github.michael04.registration.course.CourseCatalog;
import io.github.michael04.registration.course.CourseMetadataCatalog;
import io.github.michael04.registration.course.DummyCourseCatalog;
import io.github.michael04.registration.course.SectionCatalog;
import io.github.michael04.registration.course.SectionLookupCatalog;

/**
 * Which catalog is active, and what the database mode needs. No database is contacted: the pool is lazy and the
 * URL below points at a port nothing listens on.
 */
class CatalogDatabaseConfigurationTest {

	/** A password that must never appear in any message, bean description or exception. */
	private static final String SECRET = "unit-test-secret-pw";

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(DummyCourseCatalog.class, CatalogDatabaseConfiguration.class);

	private ApplicationContextRunner databaseMode() {
		return runner.withPropertyValues("app.catalog.source=database", "app.catalog.terms[2027-SPRING]=202710",
				"app.catalog.database.url=jdbc:postgresql://127.0.0.1:1/none", "app.catalog.database.username=reader",
				"app.catalog.database.password=" + SECRET, "app.catalog.database.connection-timeout=300ms");
	}

	@Test
	void theDummyCatalogIsTheDefaultAndNoDatabaseObjectsExist() {
		runner.run(ctx -> {
			assertThat(ctx).hasNotFailed();
			assertThat(ctx).hasSingleBean(CourseCatalog.class).hasSingleBean(DummyCourseCatalog.class);
			assertThat(ctx).doesNotHaveBean(DataSource.class).doesNotHaveBean(SectionCatalog.class)
				.doesNotHaveBean(JdbcCourseCatalog.class).doesNotHaveBean(JdbcSectionCatalog.class);
		});
	}

	@Test
	void explicitDummyModeNeedsNoDatabaseSettingsEither() {
		runner.withPropertyValues("app.catalog.source=dummy")
			.run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(DummyCourseCatalog.class).doesNotHaveBean(DataSource.class));
	}

	@Test
	void databaseModeSelectsTheJdbcCatalogsAndReplacesTheDummy() {
		databaseMode().run(ctx -> {
			assertThat(ctx).hasNotFailed();
			assertThat(ctx).hasSingleBean(CourseCatalog.class).hasSingleBean(JdbcCourseCatalog.class);
			assertThat(ctx).hasSingleBean(SectionCatalog.class).hasSingleBean(JdbcSectionCatalog.class);
			assertThat(ctx).hasSingleBean(DataSource.class).hasSingleBean(CourseMetadataCatalog.class)
				.hasSingleBean(SectionLookupCatalog.class);
			assertThat(ctx).doesNotHaveBean(DummyCourseCatalog.class);
		});
	}

	@Test
	void databaseModeStartsEvenThoughTheDatabaseIsDown() {
		databaseMode().run(ctx -> assertThat(ctx).hasNotFailed());
	}

	@Test
	void thePoolIsReadOnlyBoundedAndLazy() {
		databaseMode().run(ctx -> {
			HikariDataSource pool = (HikariDataSource) ctx.getBean(DataSource.class);

			assertThat(pool.isReadOnly()).isTrue();
			assertThat(pool.getMaximumPoolSize()).isEqualTo(4);
			assertThat(pool.getMinimumIdle()).isZero();
			assertThat(pool.getConnectionTimeout()).isEqualTo(300);
			assertThat(pool.getInitializationFailTimeout()).isEqualTo(-1);
			// The server itself refuses writes and runaway queries, whatever the application sends.
			assertThat(pool.getDataSourceProperties().getProperty("options"))
				.contains("default_transaction_read_only=on").contains("statement_timeout=5000");
		});
	}

	@Test
	void thePasswordIsPassedToThePoolButNeverPartOfAnyDescription() {
		databaseMode().run(ctx -> {
			HikariDataSource pool = (HikariDataSource) ctx.getBean(DataSource.class);

			assertThat(pool.getPassword()).isEqualTo(SECRET);
			assertThat(ctx.getBean(CourseCatalog.class).toString()).doesNotContain(SECRET);
			assertThat(ctx.getBean(SectionCatalog.class).toString()).doesNotContain(SECRET);
			assertThat(ctx.getBean("catalogTermMapping").toString()).doesNotContain(SECRET);
		});
	}

	@Test
	void aBlankPasswordIsNotSetSoThePgpassFileCanBeUsed() {
		runner.withPropertyValues("app.catalog.source=database", "app.catalog.terms[2027-SPRING]=202710",
				"app.catalog.database.url=jdbc:postgresql://127.0.0.1:1/none", "app.catalog.database.username=reader",
				"app.catalog.database.password=")
			.run(ctx -> assertThat(((HikariDataSource) ctx.getBean(DataSource.class)).getPassword()).isNull());
	}

	@Test
	void theTermMappingIsAppliedFromConfiguration() {
		databaseMode().run(ctx -> {
			assertThat(ctx.getBean(TermMapping.class).banner("2027-SPRING")).contains("202710");
			assertThat(ctx.getBean(TermMapping.class).banner("202710")).isEmpty();
		});
	}

	@Test
	void aDatabaseOutageIsASafe503WithNoConnectionDetails() {
		databaseMode().run(ctx -> {
			CourseCatalog catalog = ctx.getBean(CourseCatalog.class);

			assertThatThrownBy(() -> catalog.termExists("2027-SPRING")).isInstanceOf(CatalogUnavailableException.class)
				.satisfies(ex -> {
					assertThat(ex.getMessage()).doesNotContain(SECRET).doesNotContain("127.0.0.1").doesNotContain("jdbc");
					assertThat(ex.getCause()).isNull();
				});
			assertThat(catalog.termExists("2027-FALL")).as("unknown terms never need the database").isFalse();
			assertThatThrownBy(() -> ctx.getBean(SectionCatalog.class).sectionsFor("2027-SPRING", java.util.List.of("CSE-007")))
				.isInstanceOf(CatalogUnavailableException.class);
		});
	}

	// ---- misconfiguration fails at startup, with a message that names the setting and leaks nothing ----

	@Test
	void databaseModeWithoutAUrlFailsWithAnActionableMessage() {
		runner.withPropertyValues("app.catalog.source=database", "app.catalog.terms[2027-SPRING]=202710",
				"app.catalog.database.username=reader", "app.catalog.database.password=" + SECRET)
			.run(ctx -> {
				assertThat(ctx).hasFailed();
				assertThat(rootMessage(ctx.getStartupFailure())).contains("DB_URL").doesNotContain(SECRET);
			});
	}

	@Test
	void aNonPostgresUrlIsRejected() {
		runner.withPropertyValues("app.catalog.source=database", "app.catalog.terms[2027-SPRING]=202710",
				"app.catalog.database.url=jdbc:mysql://localhost/x", "app.catalog.database.username=reader")
			.run(ctx -> assertThat(rootMessage(ctx.getStartupFailure())).contains("jdbc:postgresql:"));
	}

	@Test
	void databaseModeWithoutAUsernameFails() {
		runner.withPropertyValues("app.catalog.source=database", "app.catalog.terms[2027-SPRING]=202710",
				"app.catalog.database.url=jdbc:postgresql://127.0.0.1:1/none")
			.run(ctx -> assertThat(rootMessage(ctx.getStartupFailure())).contains("DB_USERNAME"));
	}

	@Test
	void databaseModeWithoutATermMapFails() {
		runner.withPropertyValues("app.catalog.source=database", "app.catalog.database.url=jdbc:postgresql://127.0.0.1:1/none",
				"app.catalog.database.username=reader", "app.catalog.database.password=" + SECRET)
			.run(ctx -> {
				assertThat(rootMessage(ctx.getStartupFailure())).contains("app.catalog.terms").doesNotContain(SECRET);
			});
	}

	@Test
	void aMalformedBannerCodeFailsAtStartup() {
		databaseMode().withPropertyValues("app.catalog.terms[2027-SPRING]=2027")
			.run(ctx -> assertThat(rootMessage(ctx.getStartupFailure())).contains("6-digit"));
	}

	private static String rootMessage(Throwable failure) {
		Throwable t = failure;
		StringBuilder all = new StringBuilder();
		while (t != null) {
			all.append(t.getMessage()).append('\n');
			t = t.getCause();
		}
		return all.toString();
	}

	@Test
	void theSchemaDefaultsToTheVerifiedCatalogAndOnlyAcceptsTheThreeKnownSchemas() {
		databaseMode().run(ctx -> assertThat(ctx.getBean(CatalogProperties.class).database().schema()).isEqualTo("catalog_api"));
		databaseMode().withPropertyValues("app.catalog.database.schema=catalog_live")
			.run(ctx -> assertThat(ctx).hasNotFailed());
		databaseMode().withPropertyValues("app.catalog.database.schema=catalog_serving")
			.run(ctx -> assertThat(ctx).hasNotFailed());
		databaseMode().withPropertyValues("app.catalog.database.schema=catalog_research")
			.run(ctx -> assertThat(ctx).hasFailed());
		databaseMode().withPropertyValues("app.catalog.database.schema=public").run(ctx -> {
			assertThat(ctx).hasFailed();
			assertThat(ctx.getStartupFailure()).hasStackTraceContaining("app.catalog.database.schema must be one of");
		});
	}

}
