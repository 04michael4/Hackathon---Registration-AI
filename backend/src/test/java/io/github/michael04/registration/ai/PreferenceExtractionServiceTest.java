package io.github.michael04.registration.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.github.michael04.registration.course.DummyCourseCatalog;

class PreferenceExtractionServiceTest {

	private final DummyCourseCatalog catalog = new DummyCourseCatalog();

	private final PreferenceAssembler assembler = new PreferenceAssembler(new CourseMentionResolver(catalog));

	@Test
	void unknownTermFailsBeforeCallingTheAi() {
		AtomicInteger calls = new AtomicInteger();
		PreferenceExtractor counting = text -> {
			calls.incrementAndGet();
			return new DummyPreferenceExtractor().extract(text);
		};
		PreferenceExtractionService service = new PreferenceExtractionService(counting, catalog, assembler);

		assertThatThrownBy(() -> service.extract("1999-FALL", "I need CSE 17"))
			.isInstanceOf(UnknownTermException.class);
		assertThat(calls).hasValue(0);
	}

	@Test
	void unavailableAiPropagatesSoTheEndpointReturns503() {
		PreferenceExtractionService service =
				new PreferenceExtractionService(new UnavailablePreferenceExtractor(), catalog, assembler);

		assertThatThrownBy(() -> service.extract(DummyCourseCatalog.TERM, "I need CSE 17"))
			.isInstanceOf(AiUnavailableException.class);
	}

	@Test
	void nullAiOutputIsRejected() {
		PreferenceExtractionService service = new PreferenceExtractionService(text -> null, catalog, assembler);

		assertThatThrownBy(() -> service.extract(DummyCourseCatalog.TERM, "I need CSE 17"))
			.isInstanceOf(AiOutputInvalidException.class);
	}

	@Test
	void validRequestReturnsAssembledPreferences() {
		PreferenceExtractionService service =
				new PreferenceExtractionService(new DummyPreferenceExtractor(), catalog, assembler);

		ExtractPreferencesResponse r = service.extract(DummyCourseCatalog.TERM, "anything");

		assertThat(r.preferences().hardConstraints().requiredCourseIds()).containsExactly("CSE-017", "MATH-021");
	}

}
