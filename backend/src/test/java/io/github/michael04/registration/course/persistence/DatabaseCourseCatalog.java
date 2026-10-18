package io.github.michael04.registration.course.persistence;

import java.util.List;
import java.util.Optional;

import io.github.michael04.registration.course.CatalogSection;
import io.github.michael04.registration.course.CourseCatalog;
import io.github.michael04.registration.course.CourseReadCatalog;
import io.github.michael04.registration.course.CourseResponses;
import io.github.michael04.registration.course.CourseSummary;
import io.github.michael04.registration.course.SectionCatalog;
import io.github.michael04.registration.course.SectionLookupCatalog;

/** One authoritative database catalog shared by Course, Schedule and AI services. */
public class DatabaseCourseCatalog implements CourseCatalog, SectionCatalog, CourseReadCatalog, SectionLookupCatalog {
	private final CatalogRepository repository;

	public DatabaseCourseCatalog(CatalogRepository repository) {
		this.repository = repository;
	}

	@Override
	public List<CourseResponses.TermResponse> terms() {
		return repository.terms();
	}

	@Override
	public boolean termExists(String term) {
		return repository.termExists(term);
	}

	@Override
	public Optional<CourseSummary> findCourse(String term, String courseId) {
		return repository.course(term, courseId).map(course -> new CourseSummary(course.courseId(),
			course.title(), CatalogRepository.integralCredits(course.credits())));
	}

	@Override
	public List<CourseSummary> listCourses(String term) {
		return repository.courses(term).stream().map(course -> new CourseSummary(course.courseId(),
			course.title(), CatalogRepository.integralCredits(course.credits()))).toList();
	}

	@Override
	public List<CatalogSection> sectionsFor(String term, List<String> courseIds) {
		return repository.schedulingSections(term, courseIds);
	}

	@Override
	public CourseResponses.PageResponse search(String term, String query, String subject, int page, int size) {
		return repository.search(term, query, subject, page, size);
	}

	@Override
	public Optional<CourseResponses.DetailResponse> detail(String term, String courseId) {
		return repository.detail(term, courseId);
	}

	@Override
	public List<CatalogSection> sectionsByCrn(String term, List<String> crns) {
		return repository.sectionsByCrn(term, crns);
	}
}
