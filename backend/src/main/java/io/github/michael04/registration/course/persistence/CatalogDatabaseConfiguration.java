package io.github.michael04.registration.course.persistence;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import io.github.michael04.registration.course.CourseCatalog;
import io.github.michael04.registration.course.SectionCatalog;

/**
 * Registers the PostgreSQL-backed catalogs, and the connection pool they need, only when
 * {@code app.catalog.source=database} (env var {@code CATALOG_SOURCE}). In any other mode nothing here exists, so the
 * default application starts with no database and no {@code DataSource}.
 *
 * <p>The pool is created by hand, not by Spring Boot's JDBC auto-configuration, because that would try to create a
 * {@code DataSource} in every mode and stop the default application from starting.
 *
 * <p>Access is read-only at the session level (the server refuses writes), with a statement timeout. The pool does not
 * connect at startup, so a database that is down surfaces as a safe 503 on the first request instead of a crash.
 */
@Configuration
@ConditionalOnProperty(name = CourseCatalog.SOURCE_PROPERTY, havingValue = "database")
@EnableConfigurationProperties(CatalogProperties.class)
public class CatalogDatabaseConfiguration {

	private static final Logger log = LoggerFactory.getLogger(CatalogDatabaseConfiguration.class);

	@Bean(destroyMethod = "close")
	DataSource catalogDataSource(CatalogProperties properties) {
		CatalogProperties.Database db = properties.database();
		if (db.url() == null || db.url().isBlank()) {
			throw new IllegalStateException("CATALOG_SOURCE=database needs DB_URL (app.catalog.database.url), "
					+ "for example jdbc:postgresql://localhost:5432/registration");
		}
		if (!db.url().startsWith("jdbc:postgresql:")) {
			throw new IllegalStateException("app.catalog.database.url must be a jdbc:postgresql: URL");
		}
		if (db.username() == null || db.username().isBlank()) {
			throw new IllegalStateException("CATALOG_SOURCE=database needs DB_USERNAME (app.catalog.database.username)");
		}
		if (!JdbcCatalogDao.ALLOWED_SCHEMAS.contains(db.schema())) {
			throw new IllegalStateException(
					"app.catalog.database.schema must be one of " + JdbcCatalogDao.ALLOWED_SCHEMAS + " (default catalog_api)");
		}
		if (db.maxPoolSize() < 1) {
			throw new IllegalStateException("app.catalog.database.max-pool-size must be at least 1");
		}
		HikariConfig config = new HikariConfig();
		config.setPoolName("catalog-readonly");
		config.setJdbcUrl(db.url());
		config.setUsername(db.username());
		if (db.hasPassword()) {
			config.setPassword(db.password());
		}
		config.setMaximumPoolSize(db.maxPoolSize());
		config.setMinimumIdle(0);
		config.setConnectionTimeout(db.connectionTimeout().toMillis());
		config.setReadOnly(true);
		// Do not connect at startup: a database that is down becomes a 503 on use, not a failed boot.
		config.setInitializationFailTimeout(-1);
		// Enforced by the server for the whole session, whatever the application tries to run.
		config.addDataSourceProperty("options",
				"-c default_transaction_read_only=on -c statement_timeout=" + db.queryTimeout().toMillis());
		config.addDataSourceProperty("ApplicationName", "registration-catalog");
		return new HikariDataSource(config);
	}

	@Bean
	CatalogDao catalogDao(DataSource catalogDataSource, CatalogProperties properties) {
		return new JdbcCatalogDao(JdbcClient.create(catalogDataSource), properties.database().schema());
	}

	@Bean
	TermMapping catalogTermMapping(CatalogProperties properties) {
		TermMapping mapping = TermMapping.of(properties.terms());
		log.info("Database catalog enabled (read-only pool); API terms: {}", mapping.apiTerms());
		return mapping;
	}

	@Bean
	CourseCatalog jdbcCourseCatalog(CatalogDao dao, TermMapping mapping) {
		return new JdbcCourseCatalog(dao, mapping);
	}

	@Bean
	SectionCatalog jdbcSectionCatalog(CatalogDao dao, TermMapping mapping) {
		return new JdbcSectionCatalog(dao, mapping);
	}

}
