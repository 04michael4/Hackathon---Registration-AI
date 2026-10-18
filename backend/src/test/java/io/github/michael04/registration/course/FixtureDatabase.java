package io.github.michael04.registration.course;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

/** Explicit opt-in PostgreSQL fixtures. Only loopback databases named course_schedule_test* are accepted. */
public final class FixtureDatabase {
	public final String url;
	public final String user;
	public final String password;
	public final String schema;

	public FixtureDatabase(String schema) {
		this.url = required("TEST_DATABASE_URL");
		this.user = System.getenv().getOrDefault("TEST_DATABASE_USER", "postgres");
		this.password = System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "");
		this.schema = schema;
		var uri = URI.create(url.substring("jdbc:".length()));
		if (!url.startsWith("jdbc:postgresql://") || !("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()))
				|| !uri.getPath().matches("/course_schedule_test[a-z0-9_]*") || uri.getQuery() != null
				|| !schema.matches("course_api_[a-z0-9_]{1,40}")) {
			throw new IllegalArgumentException("Tests require an isolated loopback course_schedule_test database and course_api_ schema");
		}
	}

	public void prepare() {
		try {
			ensureDatabase();
			try (var connection = connect(); var statement = connection.createStatement();
				var resource = FixtureDatabase.class.getResourceAsStream("/db/course-api-test-schema.sql")) {
				if (resource == null) throw new IllegalStateException("Test schema resource is missing");
				statement.execute("CREATE SCHEMA " + schema);
				statement.execute("SET search_path TO " + schema);
				statement.execute(new String(resource.readAllBytes(), StandardCharsets.UTF_8));
				seed(connection);
			}
		}
		catch (Exception ex) { throw new IllegalStateException("Could not provision the isolated test database", ex); }
	}

	/** Executes a read-only copy of Data's original SQL only in this validated, isolated test database. */
	public void prepareSource(Path sql) {
		try {
			ensureDatabase();
			String script = Files.readString(sql).replace("cse007_snapshot", schema);
			try (var connection = connect(); var statement = connection.createStatement()) { statement.execute(script); }
		}
		catch (Exception ex) { throw new IllegalStateException("Could not load the source SQL into the isolated test database", ex); }
	}

	private void ensureDatabase() throws SQLException {
		String database = URI.create(url.substring(5)).getPath().substring(1);
		String admin = url.substring(0, url.lastIndexOf('/') + 1) + "postgres";
		try (var connection = DriverManager.getConnection(admin, user, password);
			var query = connection.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")) {
			query.setString(1, database);
			try (var result = query.executeQuery()) {
				if (!result.next()) connection.createStatement().execute("CREATE DATABASE " + database);
			}
		}
	}

	public Connection connect() throws SQLException {
		return DriverManager.getConnection(url, user, password);
	}

	public void clean() throws SQLException {
		// The constructor validates both identifiers before this database-local cleanup is possible.
		try (var connection = connect(); var statement = connection.createStatement()) {
			statement.execute("DROP SCHEMA " + schema + " CASCADE");
		}
	}

	private static void seed(Connection c) throws SQLException {
		execute(c, "INSERT INTO source_asset VALUES (?, '2026-10-01T12:00:00Z', '2026-10-16T12:00:00Z')", id("source"));
		execute(c, "INSERT INTO term VALUES (?, 'TEST-2027', 'Synthetic Spring 2027', ?)", id("term"), id("source"));
		execute(c, "INSERT INTO term VALUES (?, 'TEST-EMPTY', 'Empty synthetic term', ?)", id("empty-term"), id("source"));
		course(c, "CSE-007", "Synthetic Introduction to Programming", "4.0");
		course(c, "MATH-021", "Synthetic Calculus I", "4.0");
		course(c, "ENGL-001", "Synthetic Composition", "3.0");
		course(c, "CSE-099", "Synthetic Fractional Credit", "1.5");
		section(c, "12275", "CSE-007", "010", "LECTURE", "4.0", "OPEN", "SCHEDULED");
		section(c, "13132", "CSE-007", "060", "LAB", "0.0", "OPEN", "SCHEDULED");
		section(c, "12638", "CSE-007", "062", "LAB", "0.0", "CLOSED", "SCHEDULED");
		section(c, "49999", "CSE-007", "064", "LAB", "0.0", "OPEN", "SCHEDULED");
		meeting(c, "12275", 1, "09:20", "10:35", "Synthetic Hall 1");
		meeting(c, "12275", 4, "14:00", "14:50", null);
		for (int day : new int[] {3, 5}) {
			meeting(c, "13132", day, "09:20", "10:35", "Synthetic Lab 112");
			meeting(c, "12638", day, "10:45", "12:00", "Synthetic Lab 11");
		}
		meeting(c, "49999", 1, "09:30", "10:00", "Conflicting synthetic lab");
		execute(c, "INSERT INTO section_component_group VALUES (?, 'LAB', 1, 'USER_CONFIRMED')", id("12275"));
		for (String lab : new String[] {"13132", "12638", "49999"}) {
			execute(c, "INSERT INTO section_link VALUES (?, ?, 'LAB')", id("12275"), id(lab));
		}
		section(c, "40100", "MATH-021", "010", "LECTURE", "4.0", "OPEN", "SCHEDULED");
		section(c, "40101", "MATH-021", "020", "LECTURE", "4.0", "OPEN", "SCHEDULED");
		meeting(c, "40100", 1, "09:30", "10:45", "Synthetic Hall 2");
		meeting(c, "40101", 2, "10:30", "11:45", "Synthetic Hall 3");
		section(c, "40200", "ENGL-001", "010", "LECTURE", "3.0", "UNKNOWN", "TBA");
		section(c, "40999", "CSE-099", "010", "LECTURE", "1.5", "OPEN", "ASYNCHRONOUS");
		execute(c, "INSERT INTO course_requirement VALUES (?, 'PREREQUISITE', '{\"op\":\"COURSE\",\"code\":\"CSE-007\"}', 'RULE_DEFINED')", id("MATH-021"));
		execute(c, "INSERT INTO course_requirement VALUES (?, 'COREQUISITE', null, 'USER_CONFIRMED_NONE')", id("CSE-007"));
		execute(c, "INSERT INTO course_requirement VALUES (?, 'PREREQUISITE', null, 'UNKNOWN')", id("CSE-007"));
		for (int i = 0; i < 10; i++) {
			String code = "OPT-" + (100 + i);
			course(c, code, "Synthetic Optional " + i, "1.0");
			section(c, "50" + (100 + i), code, "010", "LECTURE", "1.0", "OPEN", "ASYNCHRONOUS");
		}
		for (int credits : new int[] {18, 19, 21}) {
			String code = "OVR-0" + credits;
			course(c, code, "Synthetic Overload " + credits, credits + ".0");
			section(c, "60" + credits, code, "010", "LECTURE", credits + ".0", "OPEN", "ASYNCHRONOUS");
		}
	}

	private static void course(Connection c, String code, String title, String credits) throws SQLException {
		execute(c, "INSERT INTO course VALUES (?, ?, ?, ?, ?, ?, ?::numeric)", id(code), code, code.split("-")[0],
			code.split("-")[1], title, "Synthetic description for " + code, credits);
	}
	private static void section(Connection c, String crn, String code, String section, String component,
			String credits, String status, String meetingStatus) throws SQLException {
		execute(c, "INSERT INTO section VALUES (?, ?, ?, ?, ?, ?::numeric, ?, ?, ?, ?)", id(crn), id(code), id("term"),
			crn, section, credits, component, "Synthetic Instructor, Alex", status, meetingStatus);
	}
	private static void meeting(Connection c, String crn, int day, String start, String end, String location) throws SQLException {
		execute(c, "INSERT INTO meeting VALUES (?, ?, ?, ?::time, ?::time, ?)", id(crn + day + start), id(crn), day, start, end, location);
	}
	private static void execute(Connection c, String sql, Object... args) throws SQLException {
		try (var statement = c.prepareStatement(sql)) {
			for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
			statement.executeUpdate();
		}
	}
	private static UUID id(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)); }
	private static String required(String variable) {
		String value = System.getenv(variable);
		if (value == null || value.isBlank()) throw new IllegalStateException(variable + " must identify an isolated PostgreSQL test database");
		return value;
	}

	/** Keeps fixtures for a separately launched application during real HTTP verification. */
	public static void main(String[] args) {
		var fixture = new FixtureDatabase("course_api_live");
		fixture.prepare();
		System.out.println("Prepared isolated HTTP fixtures in schema " + fixture.schema);
	}
}
