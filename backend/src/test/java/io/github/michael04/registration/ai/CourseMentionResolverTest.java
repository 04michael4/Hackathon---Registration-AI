package io.github.michael04.registration.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.michael04.registration.ai.CourseMentionResolver.Resolution;
import io.github.michael04.registration.course.CourseSummary;
import io.github.michael04.registration.course.DummyCourseCatalog;

class CourseMentionResolverTest {

	private static final String TERM = DummyCourseCatalog.TERM;

	private final CourseMentionResolver resolver = new CourseMentionResolver(new DummyCourseCatalog());

	@ParameterizedTest
	@ValueSource(strings = { "CSE 17", "cse17", "CSE-017", "Cse 017", "  cse 17  " })
	void courseCodesAreNormalizedToCatalogIds(String mention) {
		Resolution r = resolver.resolve(TERM, mention);

		assertThat(r.course()).map(CourseSummary::courseId).contains("CSE-017");
		assertThat(r.exactCode()).isTrue();
	}

	@Test
	void unknownCourseCodeIsUnresolved() {
		assertThat(resolver.resolve(TERM, "CSE 999").resolved()).isFalse();
	}

	@Test
	void titleNicknameWithNumberResolvesToOneCourse() {
		Resolution r = resolver.resolve(TERM, "Calc 1");

		assertThat(r.course()).map(CourseSummary::courseId).contains("MATH-021");
		assertThat(r.exactCode()).isFalse();
	}

	@Test
	void numberDistinguishesCourseLevels() {
		assertThat(resolver.resolve(TERM, "calculus 2").course()).map(CourseSummary::courseId).contains("MATH-022");
		assertThat(resolver.resolve(TERM, "Calculus II").course()).map(CourseSummary::courseId).contains("MATH-022");
	}

	@Test
	void ambiguousTitleIsUnresolved() {
		// Matches both Calculus I and Calculus II.
		assertThat(resolver.resolve(TERM, "calculus").resolved()).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = { "basket weaving", "", "   ", "the" })
	void nonMatchingOrEmptyMentionsAreUnresolved(String mention) {
		assertThat(resolver.resolve(TERM, mention).resolved()).isFalse();
	}

	@Test
	void unknownTermResolvesNothing() {
		assertThat(resolver.resolve("1999-FALL", "CSE 17").resolved()).isFalse();
		assertThat(resolver.resolve("1999-FALL", "Calc 1").resolved()).isFalse();
	}

}
