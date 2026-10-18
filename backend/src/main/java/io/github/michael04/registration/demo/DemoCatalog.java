package io.github.michael04.registration.demo;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.michael04.registration.course.CatalogSection;
import io.github.michael04.registration.course.CourseCatalog;
import io.github.michael04.registration.course.CourseSummary;
import io.github.michael04.registration.course.SectionCatalog;

/**
 * Feature-flagged demo catalog ({@code CATALOG_SOURCE=demo}): the researched Spring 2027 offerings from a local fixture.
 * Read-only and in memory. It implements the same two read interfaces as the PostgreSQL catalog, so the existing schedule
 * endpoint and engine run on it unchanged. Sections have no seat data, so their status is UNKNOWN (never CLOSED).
 */
public class DemoCatalog implements CourseCatalog, SectionCatalog {

	private final DemoFixture fixture;
	private final Map<String, DemoFixture.Offering> byId = new LinkedHashMap<>();

	public DemoCatalog(DemoFixture fixture) {
		this.fixture = fixture;
		fixture.offerings().stream().sorted(Comparator.comparing(DemoFixture.Offering::offeringId))
			.forEach(o -> byId.put(o.offeringId(), o));
	}

	public String term() {
		return fixture.term();
	}

	public DemoFixture fixture() {
		return fixture;
	}

	public List<DemoFixture.Offering> offerings() {
		return List.copyOf(byId.values());
	}

	public Optional<DemoFixture.Offering> offering(String offeringId) {
		return Optional.ofNullable(byId.get(offeringId));
	}

	@Override
	public boolean termExists(String term) {
		return fixture.term().equals(term);
	}

	@Override
	public Optional<CourseSummary> findCourse(String term, String courseId) {
		if (!termExists(term)) {
			return Optional.empty();
		}
		return offering(courseId).map(DemoCatalog::summary);
	}

	@Override
	public List<CourseSummary> listCourses(String term) {
		return termExists(term) ? byId.values().stream().map(DemoCatalog::summary).toList() : List.of();
	}

	@Override
	public List<CatalogSection> sectionsFor(String term, List<String> courseIds) {
		if (!termExists(term)) {
			return List.of();
		}
		return courseIds.stream().distinct().sorted().map(byId::get).filter(o -> o != null)
			.flatMap(o -> o.sections().stream().map(s -> toSection(term, o, s)))
			.sorted(Comparator.comparing(CatalogSection::courseId).thenComparing(CatalogSection::crn))
			.toList();
	}

	private static CourseSummary summary(DemoFixture.Offering o) {
		return new CourseSummary(o.courseId(), o.title(), o.credits());
	}

	private static CatalogSection toSection(String term, DemoFixture.Offering o, DemoFixture.Section s) {
		return new CatalogSection(term, o.courseId(), s.crn(), s.sectionCode(), s.credits(), s.component(),
			"LAB".equals(s.component()) ? s.lectureCrn() : null, CatalogSection.Status.UNKNOWN,
			s.meetings().stream().map(m -> new CatalogSection.Meeting(m.days(), m.start(), m.end(), m.location())).toList());
	}

}
