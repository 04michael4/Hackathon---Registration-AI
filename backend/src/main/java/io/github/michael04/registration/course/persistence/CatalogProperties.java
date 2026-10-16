package io.github.michael04.registration.course.persistence;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for the PostgreSQL-backed catalog ({@code CATALOG_SOURCE=database}). Only read in that mode.
 *
 * <p>{@code terms} maps each API term to its Banner term code, explicitly, for example
 * {@code app.catalog.terms[2027-SPRING]=202710}. No code derives one from the other.
 *
 * <p>{@code database} holds the connection. Credentials come from the environment
 * ({@code DB_URL}, {@code DB_USERNAME}, {@code DB_PASSWORD}); nothing is hard-coded and nothing here is ever logged.
 * A blank password is allowed so the PostgreSQL driver can fall back to a {@code PGPASSFILE}.
 */
@ConfigurationProperties("app.catalog")
public record CatalogProperties(@DefaultValue Map<String, String> terms, @DefaultValue Database database) {

	public record Database(String url, String username, String password,
			@DefaultValue("4") int maxPoolSize,
			@DefaultValue("3s") Duration connectionTimeout,
			@DefaultValue("5s") Duration queryTimeout) {

		public boolean hasPassword() {
			return password != null && !password.isBlank();
		}

	}

}
