package io.github.michael04.registration.course;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.web.server.ResponseStatusException;

class CourseServiceTest {

	private static final CourseSummary CSE_017 = new CourseSummary("CSE-017", "Programming and Data Structures", 3);

	@Test
	void codeQueriesMatchSubjectAndNumberOnly() {
		assertThat(CourseQuery.matches(CSE_017, "cse 17")).isTrue();
		assertThat(CourseQuery.matches(CSE_017, "CSE017")).isTrue();
		assertThat(CourseQuery.matches(CSE_017, "CSE 170")).isFalse();
		assertThat(CourseQuery.matches(CSE_017, "MATH 17")).isFalse();
	}

	@Test
	void wordQueriesNeedEveryWordInIdOrTitle() {
		assertThat(CourseQuery.matches(CSE_017, "data structures")).isTrue();
		assertThat(CourseQuery.matches(CSE_017, "cse data")).isTrue();
		assertThat(CourseQuery.matches(CSE_017, "data biology")).isFalse();
		assertThat(CourseQuery.matches(CSE_017, " ")).isTrue();
	}

	@Test
	void missingCatalogsAreA503() {
		var empty = new DefaultListableBeanFactory();
		var service = new CourseService(empty.getBeanProvider(CourseCatalog.class), empty.getBeanProvider(SectionCatalog.class));
		assertThatThrownBy(() -> service.search("2027-SPRING", null, null, 0, 20))
			.isInstanceOf(ResponseStatusException.class)
			.hasMessageContaining("503");
	}

}
