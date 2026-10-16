package io.github.michael04.registration.course.persistence;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import io.github.michael04.registration.course.CatalogSection;
import io.github.michael04.registration.course.SectionCatalog;

/**
 * {@link SectionCatalog} backed by PostgreSQL ({@code CATALOG_SOURCE=database}).
 *
 * <p>Maps the {@code catalog_api} rows to {@link CatalogSection}s. Nothing is invented:
 * <ul>
 * <li>{@code lectureLink} is the parent lecture's CRN for a lab and {@code null} only for a lecture. A lab with no
 * confirmed lecture is never turned into a standalone section: the data is rejected instead.</li>
 * <li>Credits are the stored section credits (a lecture 4, its labs 0), never the course credit repeated.</li>
 * <li>{@link CatalogSection.Status} is {@code UNKNOWN} unless the data says otherwise, since there is no live
 * open/closed information.</li>
 * <li>A section without meeting rows has an empty meeting list (TBA), not a meeting with null times.</li>
 * </ul>
 */
public final class JdbcSectionCatalog implements SectionCatalog {

	private final CatalogDao dao;

	private final TermMapping terms;

	JdbcSectionCatalog(CatalogDao dao, TermMapping terms) {
		this.dao = dao;
		this.terms = terms;
	}

	@Override
	public List<CatalogSection> sectionsFor(String term, List<String> courseIds) {
		var banner = terms.banner(term);
		if (banner.isEmpty() || courseIds == null) {
			return List.of();
		}
		List<String> ids = courseIds.stream().filter(id -> id != null && !id.isBlank()).map(String::trim).distinct()
			.sorted().toList();
		if (ids.isEmpty()) {
			return List.of();
		}
		List<CatalogDao.SectionRow> rows = dao.sections(banner.get(), ids);
		if (rows.isEmpty()) {
			return List.of();
		}
		requireConsistentLinks(rows);
		Map<String, List<CatalogSection.Meeting>> meetings = meetingsByCrn(
				dao.meetings(banner.get(), rows.stream().map(CatalogDao.SectionRow::crn).toList()));
		List<CatalogSection> sections = new ArrayList<>();
		for (CatalogDao.SectionRow row : rows) {
			sections.add(new CatalogSection(term.trim(), row.courseId(), row.crn(), row.sectionCode(), row.credits(),
					row.componentType(), row.lectureCrn(), status(row.status()),
					meetings.getOrDefault(row.crn(), List.of())));
		}
		return List.copyOf(sections);
	}

	/**
	 * Defense in depth behind the SQL rules: a section with an impossible link is a data error, never a standalone
	 * section and never silently dropped.
	 */
	private static void requireConsistentLinks(List<CatalogDao.SectionRow> rows) {
		Set<String> crns = new HashSet<>();
		Set<String> lectures = new HashSet<>();
		for (CatalogDao.SectionRow row : rows) {
			if (row.crn() == null || row.crn().isBlank() || !crns.add(row.crn())) {
				throw inconsistent();
			}
			if (row.credits() < 0 || row.courseId() == null || row.componentType() == null || row.componentType().isBlank()) {
				throw inconsistent();
			}
			if ("LECTURE".equals(row.componentType())) {
				lectures.add(row.crn());
			}
		}
		for (CatalogDao.SectionRow row : rows) {
			boolean lecture = "LECTURE".equals(row.componentType());
			if (lecture != (row.lectureCrn() == null)) {
				throw inconsistent(); // a lecture must have no link; every other component must have one
			}
			if (!lecture && (!lectures.contains(row.lectureCrn()) || !sameCourse(rows, row))) {
				throw inconsistent(); // the parent must be a returned lecture of the same course
			}
		}
	}

	private static boolean sameCourse(List<CatalogDao.SectionRow> rows, CatalogDao.SectionRow component) {
		return rows.stream().anyMatch(r -> r.crn().equals(component.lectureCrn()) && r.courseId().equals(component.courseId()));
	}

	private static Map<String, List<CatalogSection.Meeting>> meetingsByCrn(List<CatalogDao.MeetingRow> rows) {
		Map<String, List<CatalogSection.Meeting>> byCrn = new LinkedHashMap<>();
		for (CatalogDao.MeetingRow row : rows) {
			byCrn.computeIfAbsent(row.crn(), crn -> new ArrayList<>()).add(meeting(row));
		}
		return byCrn;
	}

	private static CatalogSection.Meeting meeting(CatalogDao.MeetingRow row) {
		try {
			if (row.days() == null || row.days().isEmpty()) {
				throw inconsistent();
			}
			var days = new TreeSet<DayOfWeek>();
			row.days().forEach(day -> days.add(DayOfWeek.of(day)));
			LocalTime start = LocalTime.parse(row.start());
			LocalTime end = LocalTime.parse(row.end());
			if (!start.isBefore(end)) {
				throw inconsistent();
			}
			return new CatalogSection.Meeting(List.copyOf(days), start, end, row.location());
		}
		catch (DateTimeException | NullPointerException ex) { // includes DateTimeParseException
			throw inconsistent();
		}
	}

	/** No live open/closed data exists, so anything that isn't exactly OPEN or CLOSED is UNKNOWN, never a guess. */
	private static CatalogSection.Status status(String value) {
		if ("OPEN".equals(value)) {
			return CatalogSection.Status.OPEN;
		}
		if ("CLOSED".equals(value)) {
			return CatalogSection.Status.CLOSED;
		}
		return CatalogSection.Status.UNKNOWN;
	}

	private static IllegalStateException inconsistent() {
		return new IllegalStateException("Inconsistent catalog section data");
	}

}
