package io.github.michael04.registration.course.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Runs the catalog queries on PostgreSQL through the read-only {@code catalog_api} views (or, when configured, the
 * same-shaped {@code catalog_live} views) and nothing else. The views (data/catalog-api, data/catalog-promotion) expose a course only when every section is certified for scheduling, so an uncertified
 * section can never be returned here.
 *
 * <p>A database failure becomes {@link CatalogUnavailableException}. Only the exception class is logged: driver
 * messages can contain the connection URL.
 */
final class JdbcCatalogDao implements CatalogDao {

	private static final Logger log = LoggerFactory.getLogger(JdbcCatalogDao.class);

	// The exact queries, also documented in data/catalog-api/README.md. Banner term codes only.
	static final String TERM_EXISTS = "SELECT EXISTS (SELECT 1 FROM catalog_api.course_offering WHERE term_code = ?)";

	static final String COURSES = "SELECT course_id, title, credits FROM catalog_api.course_offering "
			+ "WHERE term_code = ? AND schedulable ORDER BY course_id";

	static final String COURSE = "SELECT course_id, title, credits FROM catalog_api.course_offering "
			+ "WHERE term_code = ? AND course_id = ? AND schedulable";

	static final String SECTIONS = "SELECT course_id, crn, section_code, credits, component_type, lecture_crn, status "
			+ "FROM catalog_api.section_offering WHERE term_code = ? AND course_id = ANY(?) ORDER BY course_id, crn";

	static final String MEETINGS = "SELECT crn, array_to_string(array_agg(day_of_week ORDER BY day_of_week), ',') AS days, "
			+ "start_time, end_time, location FROM catalog_api.section_meeting WHERE term_code = ? AND crn = ANY(?) "
			+ "GROUP BY crn, start_time, end_time, location ORDER BY crn, start_time, end_time, location";

	/**
	 * The only schemas the adapter may read. All expose the same three views: catalog_api (the verified snapshot), catalog_live (the same plus
	 * approved promoted courses) and catalog_serving (the researched READY and CONDITIONAL courses, with their conditions; data/catalog-serving).
	 */
	static final List<String> ALLOWED_SCHEMAS = List.of("catalog_api", "catalog_live", "catalog_serving");

	private final JdbcClient jdbc;

	private final String termExistsSql;

	private final String coursesSql;

	private final String courseSql;

	private final String sectionsSql;

	private final String meetingsSql;

	JdbcCatalogDao(JdbcClient jdbc) {
		this(jdbc, "catalog_api");
	}

	/**
	 * @param schema {@code catalog_api} (the verified snapshot, the default), {@code catalog_live} (the same plus
	 * approved, currently-servable promoted courses) or {@code catalog_serving} (researched courses with conditions). Anything else is refused, so a setting can never point the
	 * adapter at another schema.
	 */
	JdbcCatalogDao(JdbcClient jdbc, String schema) {
		if (!ALLOWED_SCHEMAS.contains(schema)) {
			throw new IllegalArgumentException("app.catalog.database.schema must be one of " + ALLOWED_SCHEMAS);
		}
		this.jdbc = jdbc;
		this.termExistsSql = inSchema(TERM_EXISTS, schema);
		this.coursesSql = inSchema(COURSES, schema);
		this.courseSql = inSchema(COURSE, schema);
		this.sectionsSql = inSchema(SECTIONS, schema);
		this.meetingsSql = inSchema(MEETINGS, schema);
	}

	static String inSchema(String sql, String schema) {
		return sql.replace("catalog_api.", schema + ".");
	}

	@Override
	public boolean termHasCourses(String bannerTerm) {
		return guarded(() -> Boolean.TRUE.equals(
				jdbc.sql(termExistsSql).param(bannerTerm).query(Boolean.class).single()));
	}

	@Override
	public List<CourseRow> courses(String bannerTerm) {
		return guarded(() -> jdbc.sql(coursesSql).param(bannerTerm).query(JdbcCatalogDao::courseRow).list());
	}

	@Override
	public Optional<CourseRow> course(String bannerTerm, String courseId) {
		return guarded(() -> jdbc.sql(courseSql).param(bannerTerm).param(courseId).query(JdbcCatalogDao::courseRow)
			.optional());
	}

	@Override
	public List<SectionRow> sections(String bannerTerm, List<String> courseIds) {
		return guarded(() -> jdbc.sql(sectionsSql).param(bannerTerm).param(courseIds.toArray(String[]::new))
			.query(JdbcCatalogDao::sectionRow).list());
	}

	@Override
	public List<MeetingRow> meetings(String bannerTerm, List<String> crns) {
		return guarded(() -> jdbc.sql(meetingsSql).param(bannerTerm).param(crns.toArray(String[]::new))
			.query(JdbcCatalogDao::meetingRow).list());
	}

	private static CourseRow courseRow(ResultSet rs, int row) throws SQLException {
		return new CourseRow(rs.getString("course_id"), rs.getString("title"), rs.getInt("credits"));
	}

	private static SectionRow sectionRow(ResultSet rs, int row) throws SQLException {
		return new SectionRow(rs.getString("course_id"), rs.getString("crn"), rs.getString("section_code"),
				rs.getInt("credits"), rs.getString("component_type"), rs.getString("lecture_crn"), rs.getString("status"));
	}

	private static MeetingRow meetingRow(ResultSet rs, int row) throws SQLException {
		List<Integer> days = new ArrayList<>();
		String joined = rs.getString("days");
		if (joined != null && !joined.isBlank()) {
			for (String day : joined.split(",")) {
				days.add(Integer.valueOf(day.trim()));
			}
		}
		return new MeetingRow(rs.getString("crn"), days, rs.getString("start_time"), rs.getString("end_time"),
				rs.getString("location"));
	}

	private static <T> T guarded(java.util.function.Supplier<T> query) {
		try {
			return query.get();
		}
		catch (DataAccessException ex) {
			log.warn("Catalog database query failed ({})", ex.getClass().getSimpleName());
			throw new CatalogUnavailableException();
		}
	}

}
