package io.github.michael04.registration.course.persistence;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.catalog.database")
public record CatalogDatabaseProperties(String url, String username, String password, String schema) {
	public CatalogDatabaseProperties {
		if (url == null || !url.startsWith("jdbc:postgresql:")) {
			throw new IllegalArgumentException("DB_URL must be a PostgreSQL JDBC URL in database catalog mode");
		}
		username = username == null ? "" : username;
		password = password == null ? "" : password;
		schema = schema == null ? "cse007_snapshot" : schema;
		if (!schema.matches("[a-z_][a-z0-9_]{0,62}")) {
			throw new IllegalArgumentException("CATALOG_SCHEMA must be a lowercase PostgreSQL identifier");
		}
	}

	@Override
	public String toString() {
		return "CatalogDatabaseProperties[url=<configured>, username=<configured>, password=***, schema=" + schema + "]";
	}
}
