package io.github.michael04.registration.course.persistence;

import java.util.Properties;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import io.github.michael04.registration.course.CourseCatalog;

/** Database access is opt-in; importing this configuration never runs DDL or seed scripts. */
@Configuration
@ConditionalOnProperty(name = CourseCatalog.SOURCE_PROPERTY, havingValue = "database")
@EnableConfigurationProperties(CatalogDatabaseProperties.class)
@EnableTransactionManagement
public class CatalogPersistenceConfiguration {
	@Bean
	DataSource catalogDataSource(CatalogDatabaseProperties properties) {
		var connection = new Properties();
		connection.setProperty("user", properties.username());
		connection.setProperty("password", properties.password());
		connection.setProperty("currentSchema", properties.schema());
		connection.setProperty("connectTimeout", "5");
		connection.setProperty("socketTimeout", "15");
		return new DriverManagerDataSource(properties.url(), connection);
	}

	@Bean
	JdbcTemplate catalogJdbcTemplate(DataSource catalogDataSource) {
		return new JdbcTemplate(catalogDataSource);
	}

	@Bean
	DataSourceTransactionManager catalogTransactionManager(DataSource catalogDataSource) {
		var manager = new DataSourceTransactionManager(catalogDataSource);
		manager.setEnforceReadOnly(true);
		return manager;
	}

	@Bean
	CatalogRepository catalogRepository(JdbcTemplate catalogJdbcTemplate) {
		return new CatalogRepository(catalogJdbcTemplate);
	}

	@Bean
	DatabaseCourseCatalog databaseCourseCatalog(CatalogRepository repository) {
		return new DatabaseCourseCatalog(repository);
	}
}
