package io.github.michael04.registration.course.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import io.github.michael04.registration.course.CourseSummary;

class JdbcCourseCatalogTest {

	private final FakeCatalogDao dao = new FakeCatalogDao();

	private final JdbcCourseCatalog catalog = new JdbcCourseCatalog(dao, TermMapping.of(Map.of("2027-SPRING", "202710")));

	@Test
	void theApiTermIsTranslatedToTheBannerCodeBeforeAnyQuery() {
		assertThat(catalog.termExists("2027-SPRING")).isTrue();

		assertThat(dao.calls).containsExactly("termHasCourses[202710]");
	}

	@Test
	void aTermThatIsNotConfiguredIsUnknownWithoutTouchingTheDatabase() {
		assertThat(catalog.termExists("2027-FALL")).isFalse();
		assertThat(catalog.termExists("202710")).isFalse();
		assertThat(catalog.termExists(null)).isFalse();
		assertThat(catalog.findCourse("2027-FALL", "CSE-007")).isEmpty();
		assertThat(catalog.listCourses("202710")).isEmpty();

		assertThat(dao.calls).isEmpty();
	}

	@Test
	void aConfiguredTermWithNoCoursesDoesNotExist() {
		dao.courses = List.of();

		assertThat(catalog.termExists("2027-SPRING")).isFalse();
	}

	@Test
	void findCourseReturnsTheCourseSummary() {
		assertThat(catalog.findCourse("2027-SPRING", "CSE-007"))
			.contains(new CourseSummary("CSE-007", "Introduction to Programming", 4));
		assertThat(dao.calls).containsExactly("course[202710, CSE-007]");
	}

	@Test
	void anUnknownCourseIsEmpty() {
		assertThat(catalog.findCourse("2027-SPRING", "CSE-017")).isEmpty();
		assertThat(catalog.findCourse("2027-SPRING", "cse-007")).isEmpty();
	}

	@Test
	void aBlankOrNullCourseIdIsEmptyWithoutAQuery() {
		assertThat(catalog.findCourse("2027-SPRING", null)).isEmpty();
		assertThat(catalog.findCourse("2027-SPRING", " ")).isEmpty();

		assertThat(dao.calls).isEmpty();
	}

	@Test
	void listCoursesIsOrderedByCourseId() {
		dao.courses = List.of(new CatalogDao.CourseRow("MATH-021", "Calculus I", 4), FakeCatalogDao.CSE_007,
				new CatalogDao.CourseRow("CSE-017", "Programming and Data Structures", 3));

		assertThat(catalog.listCourses("2027-SPRING")).extracting(CourseSummary::courseId)
			.containsExactly("CSE-007", "CSE-017", "MATH-021");
	}

	@Test
	void creditsAndTitlesAreExactlyWhatTheViewReturns() {
		CourseSummary course = catalog.listCourses("2027-SPRING").get(0);

		assertThat(course.credits()).isEqualTo(4);
		assertThat(course.title()).isEqualTo("Introduction to Programming");
	}

	@Test
	void aDatabaseFailureIsPropagatedAsASafe503() {
		dao.failure = new CatalogUnavailableException();

		assertThatThrownBy(() -> catalog.termExists("2027-SPRING")).isInstanceOf(ResponseStatusException.class)
			.satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
		assertThatThrownBy(() -> catalog.listCourses("2027-SPRING")).isInstanceOf(CatalogUnavailableException.class);
		assertThatThrownBy(() -> catalog.findCourse("2027-SPRING", "CSE-007")).isInstanceOf(CatalogUnavailableException.class);
	}

}
