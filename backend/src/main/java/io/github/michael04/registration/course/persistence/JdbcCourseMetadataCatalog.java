package io.github.michael04.registration.course.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.michael04.registration.course.CourseMetadataCatalog;
import io.github.michael04.registration.course.CourseResponses;

/** Reads display fields from the same authoritative Data tables using the existing read-only pool. */
final class JdbcCourseMetadataCatalog implements CourseMetadataCatalog {

	private final JdbcClient jdbc;
	private final TermMapping mapping;

	JdbcCourseMetadataCatalog(JdbcClient jdbc, TermMapping mapping) {
		this.jdbc = jdbc;
		this.mapping = mapping;
	}

	@Override
	public List<CourseResponses.TermResponse> terms() {
		return guarded(() -> {
			Map<String, String> apiByBanner = new TreeMap<>();
			mapping.apiTerms().forEach(api -> apiByBanner.put(mapping.banner(api).orElseThrow(), api));
			return jdbc.sql("""
				SELECT t.term_code, t.name, (a.captured_at AT TIME ZONE 'America/New_York')::date snapshot_date
				FROM cse007_snapshot.term t LEFT JOIN cse007_snapshot.source_asset a ON a.source_id = t.source_id
				ORDER BY t.term_code
				""")
				.query((rs, row) -> new CourseResponses.TermResponse(apiByBanner.get(rs.getString("term_code")),
					rs.getString("name"), rs.getObject("snapshot_date", LocalDate.class)))
				.list().stream().filter(term -> term.term() != null)
				.sorted(java.util.Comparator.comparing(CourseResponses.TermResponse::term)).toList();
		});
	}

	@Override
	public Detail detail(String term, String courseId) {
		var banner = mapping.banner(term);
		if (banner.isEmpty()) return new Detail(null, Map.of());
		return guarded(() -> {
			String description = jdbc.sql("""
				SELECT c.description FROM cse007_snapshot.course c
				WHERE c.code = ? AND EXISTS (SELECT 1 FROM cse007_snapshot.section s
				JOIN cse007_snapshot.term t ON t.term_id = s.term_id
				WHERE s.course_id = c.course_id AND t.term_code = ?)
				""").param(courseId).param(banner.get()).query((rs, row) -> rs.getString("description"))
				.list().stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
			Map<String, List<String>> instructors = new TreeMap<>();
			jdbc.sql("""
				SELECT s.crn, s.instructor FROM cse007_snapshot.section s
				JOIN cse007_snapshot.course c ON c.course_id = s.course_id
				JOIN cse007_snapshot.term t ON t.term_id = s.term_id
				WHERE t.term_code = ? AND c.code = ? ORDER BY s.crn
				""").param(banner.get()).param(courseId).query((rs, row) -> {
					String instructor = rs.getString("instructor");
					instructors.put(rs.getString("crn"), instructor == null || instructor.isBlank()
						? List.of() : List.of(instructor));
					return rs.getString("crn");
				}).list();
			return new Detail(description, Map.copyOf(instructors));
		});
	}

	private static <T> T guarded(Supplier<T> query) {
		try { return query.get(); }
		catch (DataAccessException ex) { throw new CatalogUnavailableException(); }
	}
}
