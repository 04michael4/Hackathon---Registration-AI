package io.github.michael04.registration.schedule;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

/** Test provisioning only: loads the unchanged, checked-in Data schema and catalog_api views in a fresh database. */
public final class AuthoritativeCatalogFixture {
	public final String url;
	public final String username;
	public final String password;

	public AuthoritativeCatalogFixture(boolean synthetic) {
		String configured = System.getenv("TEST_DATABASE_URL");
		if (configured == null || !configured.startsWith("jdbc:postgresql://")) {
			throw new IllegalArgumentException("TEST_DATABASE_URL must identify an isolated loopback PostgreSQL test database");
		}
		URI uri = URI.create(configured.substring(5));
		if (!("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost())) || uri.getQuery() != null
				|| uri.getUserInfo() != null || !uri.getPath().matches("/course_schedule_test[a-z0-9_]*")) {
			throw new IllegalArgumentException("Only loopback course_schedule_test databases are allowed for fixtures");
		}
		username = System.getenv().getOrDefault("TEST_DATABASE_USER", "postgres");
		password = System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "");
		String prefix = configured.substring(0, configured.lastIndexOf('/') + 1);
		String database = "course_schedule_test_" + UUID.randomUUID().toString().replace("-", "");
		url = prefix + database;
		try (var admin = DriverManager.getConnection(prefix + "postgres", username, password);
			var statement = admin.createStatement()) {
			statement.execute("CREATE DATABASE " + database);
		}
		catch (Exception ex) { throw new IllegalStateException("Could not create the isolated test database", ex); }
		try (var connection = connect(); var statement = connection.createStatement()) {
			Path repo = Path.of("..").toAbsolutePath().normalize();
			statement.execute(Files.readString(repo.resolve("data/cse007-snapshot/database.sql")));
			statement.execute(Files.readString(repo.resolve("data/catalog-api/V1__catalog_api_views.sql")));
			// psql display/ON_ERROR_STOP directives are client commands; execute all of its actual SQL assertions via JDBC.
			statement.execute(Files.readString(repo.resolve("data/catalog-api/verify_catalog_api.sql")).lines()
				.filter(line -> !line.stripLeading().startsWith("\\")).collect(java.util.stream.Collectors.joining("\n")));
			if (synthetic) {
				try (var resource = getClass().getResourceAsStream("/db/schedule-integration-fixtures.sql")) {
					if (resource == null) throw new IllegalStateException("Test fixtures missing");
					statement.execute(new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
				}
			}
		}
		catch (Exception ex) { throw new IllegalStateException("Could not load authoritative catalog SQL into the isolated database", ex); }
		System.out.println("Isolated catalog fixture: " + url);
	}

	public Connection connect() throws java.sql.SQLException {
		return DriverManager.getConnection(url, username, password);
	}

	/** Keep fixture databases for inspection; never drop schemas/databases or touch a production connection. */
	public static void main(String[] args) {
		var fixture = new AuthoritativeCatalogFixture(args.length > 0 && "synthetic".equals(args[0]));
		System.out.println("FIXTURE_DATABASE_URL=" + fixture.url);
	}
}
