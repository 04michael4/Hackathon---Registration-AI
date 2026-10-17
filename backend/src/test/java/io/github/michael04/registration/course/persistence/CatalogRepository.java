package io.github.michael04.registration.course.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import io.github.michael04.registration.course.CatalogSection;
import io.github.michael04.registration.course.CourseResponses;

/** Read-only, parameterized queries over David's cse007_snapshot schema (V1–V4). */
@Transactional(readOnly = true, transactionManager = "catalogTransactionManager")
public class CatalogRepository {
	private final JdbcTemplate jdbc;

	public CatalogRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public record CourseRow(String courseId, String subject, String number, String title,
			BigDecimal credits, String description) {}
	private record SectionRow(String id, String courseId, String crn, String sectionCode, BigDecimal credits,
			String component, String instructor, String availability, String meetingStatus) {}
	private record Group(String parent, String component, Integer count, String assessment) {}
	private record MeetingKey(LocalTime start, LocalTime end, String location) {}

	public List<CourseResponses.TermResponse> terms() {
		return jdbc.query("""
			SELECT t.term_code, t.name, (a.captured_at AT TIME ZONE 'America/New_York')::date snapshot_date
			FROM term t JOIN source_asset a ON a.source_id = t.source_id ORDER BY t.term_code
			""", (rs, row) -> new CourseResponses.TermResponse(rs.getString("term_code"), rs.getString("name"),
				rs.getObject("snapshot_date", LocalDate.class)));
	}

	public boolean termExists(String term) {
		return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM term WHERE term_code = ?)",
			Boolean.class, term));
	}

	public Optional<CourseRow> course(String term, String courseId) {
		return jdbc.query(COURSES + " AND c.code = ? ORDER BY c.code", CatalogRepository::courseRow, term, courseId)
			.stream().findFirst();
	}

	public List<CourseRow> courses(String term) {
		return jdbc.query(COURSES + " ORDER BY c.code", CatalogRepository::courseRow, term);
	}

	private static final String COURSES = """
		SELECT c.code, c.subject, c.course_number, c.title, c.credits, c.description
		FROM course c WHERE EXISTS(SELECT 1 FROM section s JOIN term t ON t.term_id = s.term_id
		WHERE s.course_id = c.course_id AND t.term_code = ?)
		""";

	private static CourseRow courseRow(ResultSet rs, int row) throws SQLException {
		return new CourseRow(rs.getString("code"), rs.getString("subject"), rs.getString("course_number"),
			rs.getString("title"), rs.getBigDecimal("credits"), rs.getString("description"));
	}

	public CourseResponses.PageResponse search(String term, String query, String subject, int page, int size) {
		var args = new ArrayList<Object>();
		args.add(term);
		var where = new StringBuilder(" WHERE t.term_code = ?");
		if (subject != null) {
			where.append(" AND c.subject = ?");
			args.add(subject.toUpperCase(Locale.ROOT));
		}
		if (query != null && !query.isBlank()) {
			String text = query.strip().toLowerCase(Locale.ROOT);
			String code = text.replaceAll("[\\s-]", "");
			where.append(" AND (replace(lower(c.code), '-', '') LIKE ? ESCAPE '!' OR (");
			args.add(like(code));
			String[] words = text.split("\\s+");
			for (int i = 0; i < words.length; i++) {
				if (i > 0) where.append(" AND ");
				where.append("lower(c.title) LIKE ? ESCAPE '!'");
				args.add(like(words[i]));
			}
			where.append("))");
		}
		String from = " FROM course c JOIN section s ON s.course_id = c.course_id JOIN term t ON t.term_id = s.term_id";
		long total = jdbc.queryForObject("SELECT count(DISTINCT c.course_id)" + from + where, Long.class, args.toArray());
		var paged = new ArrayList<>(args);
		paged.add(size);
		paged.add((long) page * size);
		var items = jdbc.query("""
			SELECT c.code, c.subject, c.course_number, c.title, c.credits, count(s.section_id) section_count
			""" + from + where + " GROUP BY c.course_id ORDER BY c.code LIMIT ? OFFSET ?",
			(rs, row) -> new CourseResponses.SummaryResponse(rs.getString("code"), rs.getString("subject"),
				rs.getString("course_number"), rs.getString("title"), rs.getBigDecimal("credits"), rs.getInt("section_count")),
			paged.toArray());
		return new CourseResponses.PageResponse(items, page, size, total, (total + size - 1) / size);
	}

	private static String like(String value) {
		return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
	}

	public Optional<CourseResponses.DetailResponse> detail(String term, String courseId) {
		return course(term, courseId).map(course -> {
			var rows = sectionRows(term, List.of(courseId));
			var meetings = meetings(term, List.of(courseId));
			return new CourseResponses.DetailResponse(course.courseId(), course.subject(), course.number(), course.title(),
				course.credits(), course.description(), rows.stream().map(section -> new CourseResponses.SectionResponse(
					section.crn(), section.sectionCode(), section.instructor() == null || section.instructor().isBlank()
						? List.of() : List.of(section.instructor()), status(section.availability()),
					validatedMeetings(section, meetings))).toList());
		});
	}

	public List<CatalogRequirement> requirements(String courseId) {
		return jdbc.query("""
			SELECT r.requirement_type, r.requirement_expression::text expression, r.assessment_status
			FROM course_requirement r JOIN course c ON c.course_id = r.course_id
			WHERE c.code = ? ORDER BY r.requirement_type
			""", (rs, row) -> new CatalogRequirement(rs.getString("requirement_type"), rs.getString("expression"),
				rs.getString("assessment_status")), courseId);
	}

	public List<CatalogSection> schedulingSections(String term, List<String> courseIds) {
		if (courseIds.isEmpty()) return List.of();
		var ids = courseIds.stream().distinct().sorted().toList();
		var rows = sectionRows(term, ids);
		var meetings = meetings(term, ids);
		var args = arguments(term, ids);
		Map<String, String> parents = new TreeMap<>();
		Map<String, String> linkedComponents = new TreeMap<>();
		jdbc.query("""
			SELECT child.crn child_crn, parent.crn parent_crn, l.required_component_group component
			FROM section_link l JOIN section child ON child.section_id = l.compatible_section_id
			JOIN section parent ON parent.section_id = l.primary_section_id
			JOIN term t ON t.term_id = child.term_id JOIN course c ON c.course_id = child.course_id
			WHERE t.term_code = ? AND c.code IN (
			""" + placeholders(ids.size()) + ") ORDER BY child.crn, parent.crn", rs -> {
			String child = rs.getString("child_crn");
			String parent = rs.getString("parent_crn");
			String previous = parents.putIfAbsent(child, parent);
			if (previous != null && !previous.equals(parent)) {
				throw new IllegalStateException("Multiple lecture parents are not supported by the native engine contract");
			}
			linkedComponents.put(parent + "/" + rs.getString("component"), child);
		}, args);
		var groups = jdbc.query("""
			SELECT s.crn, g.required_component_group, g.required_count, g.assessment_status
			FROM section_component_group g JOIN section s ON s.section_id = g.primary_section_id
			JOIN term t ON t.term_id = s.term_id JOIN course c ON c.course_id = s.course_id
			WHERE t.term_code = ? AND c.code IN (
			""" + placeholders(ids.size()) + ") ORDER BY s.crn, g.required_component_group",
			(rs, row) -> new Group(rs.getString("crn"), rs.getString("required_component_group"),
				rs.getObject("required_count", Integer.class), rs.getString("assessment_status")), args);
		var unusable = new TreeSet<String>();
		for (var group : groups) {
			if (!Integer.valueOf(1).equals(group.count()) || !"USER_CONFIRMED".equals(group.assessment())) {
				throw new IllegalStateException("Required component count has not been confirmed as one");
			}
			if (!linkedComponents.containsKey(group.parent() + "/" + group.component())) unusable.add(group.parent());
		}
		for (var row : rows) {
			if (("LAB".equals(row.component()) || "RECITATION".equals(row.component())) && !parents.containsKey(row.crn())) {
				throw new IllegalStateException("Linked catalog component has no confirmed lecture association");
			}
		}
		return rows.stream().filter(row -> !unusable.contains(row.crn())
				&& (parents.get(row.crn()) == null || !unusable.contains(parents.get(row.crn()))))
			.map(row -> new CatalogSection(term, row.courseId(), row.crn(), row.sectionCode(), integralCredits(row.credits()),
				row.component(), parents.get(row.crn()), status(row.availability()), validatedMeetings(row, meetings).stream()
					.map(m -> new CatalogSection.Meeting(m.days(), m.start(), m.end(), m.location())).toList()))
			.toList();
	}

	public List<CatalogSection> sectionsByCrn(String term, List<String> crns) {
		if (crns.isEmpty()) return List.of();
		var ids = crns.stream().distinct().sorted().toList();
		var courses = jdbc.query("""
			SELECT DISTINCT c.code FROM section s JOIN course c ON c.course_id = s.course_id
			JOIN term t ON t.term_id = s.term_id WHERE t.term_code = ? AND s.crn IN (
			""" + placeholders(ids.size()) + ") ORDER BY c.code", (rs, row) -> rs.getString("code"), arguments(term, ids));
		return schedulingSections(term, courses).stream().filter(s -> ids.contains(s.crn())).toList();
	}

	private List<SectionRow> sectionRows(String term, List<String> courseIds) {
		return jdbc.query("""
			SELECT s.section_id::text id, c.code, s.crn, s.section_code, s.credits, s.component_type,
			s.instructor, s.search_status_text, s.meeting_status
			FROM section s JOIN term t ON t.term_id = s.term_id JOIN course c ON c.course_id = s.course_id
			WHERE t.term_code = ? AND c.code IN (
			""" + placeholders(courseIds.size()) + ") ORDER BY s.crn",
			(rs, row) -> new SectionRow(rs.getString("id"), rs.getString("code"), rs.getString("crn"),
				rs.getString("section_code"), rs.getBigDecimal("credits"), rs.getString("component_type"),
				rs.getString("instructor"), rs.getString("search_status_text"), rs.getString("meeting_status")),
			arguments(term, courseIds));
	}

	private Map<String, List<CourseResponses.MeetingResponse>> meetings(String term, List<String> courseIds) {
		Map<String, Map<MeetingKey, List<DayOfWeek>>> grouped = new LinkedHashMap<>();
		jdbc.query("""
			SELECT s.section_id::text id, m.day_of_week, m.start_time, m.end_time, m.location
			FROM meeting m JOIN section s ON s.section_id = m.section_id JOIN course c ON c.course_id = s.course_id
			JOIN term t ON t.term_id = s.term_id WHERE t.term_code = ? AND c.code IN (
			""" + placeholders(courseIds.size()) + ") ORDER BY s.crn, m.start_time, m.end_time, m.location NULLS FIRST, m.day_of_week",
			rs -> {
				var key = new MeetingKey(rs.getObject("start_time", LocalTime.class), rs.getObject("end_time", LocalTime.class),
					rs.getString("location"));
				grouped.computeIfAbsent(rs.getString("id"), id -> new LinkedHashMap<>())
					.computeIfAbsent(key, ignored -> new ArrayList<>()).add(DayOfWeek.of(rs.getInt("day_of_week")));
			}, arguments(term, courseIds));
		Map<String, List<CourseResponses.MeetingResponse>> result = new TreeMap<>();
		grouped.forEach((id, values) -> result.put(id, values.entrySet().stream().map(entry ->
			new CourseResponses.MeetingResponse(entry.getValue().stream().distinct().sorted().toList(),
				entry.getKey().start(), entry.getKey().end(), entry.getKey().location())).toList()));
		return result;
	}

	private static List<CourseResponses.MeetingResponse> validatedMeetings(SectionRow section,
			Map<String, List<CourseResponses.MeetingResponse>> meetings) {
		var values = meetings.getOrDefault(section.id(), List.of());
		if (("SCHEDULED".equals(section.meetingStatus()) && values.isEmpty())
				|| (!"SCHEDULED".equals(section.meetingStatus()) && !values.isEmpty())) {
			throw new IllegalStateException("Catalog meeting status disagrees with its meeting rows");
		}
		return values;
	}

	private static CatalogSection.Status status(String value) {
		// Historical seat-count snippets are not authoritative OPEN/CLOSED labels.
		if (value != null) {
			try { return CatalogSection.Status.valueOf(value.strip().toUpperCase(Locale.ROOT)); }
			catch (IllegalArgumentException ignored) { /* Keep unknown status honest. */ }
		}
		return CatalogSection.Status.UNKNOWN;
	}

	static int integralCredits(BigDecimal credits) {
		if (credits == null || credits.signum() < 0) throw new IllegalStateException("Catalog credits are unknown or negative");
		try { return credits.intValueExact(); }
		catch (ArithmeticException ex) { throw new IllegalStateException("Native engine requires integral credits", ex); }
	}

	private static String placeholders(int size) {
		return String.join(",", java.util.Collections.nCopies(size, "?"));
	}

	private static Object[] arguments(String term, List<String> courseIds) {
		var args = new ArrayList<Object>();
		args.add(term);
		args.addAll(courseIds);
		return args.toArray();
	}
}
