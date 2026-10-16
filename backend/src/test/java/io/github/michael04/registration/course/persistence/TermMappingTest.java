package io.github.michael04.registration.course.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The API term to Banner code map is explicit: a lookup, never a formula, and never reversible by accident. */
class TermMappingTest {

	private final TermMapping mapping = TermMapping.of(Map.of("2027-SPRING", "202710"));

	@Test
	void theApiTermMapsToTheBannerCode() {
		assertThat(mapping.banner("2027-SPRING")).contains("202710");
		assertThat(mapping.banner("  2027-SPRING ")).contains("202710");
	}

	@Test
	void theBannerCodeIsNotAnApiTerm() {
		assertThat(mapping.banner("202710")).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "2027-FALL", "2028-SPRING", "2027-spring", "spring 2027", "", " " })
	void anythingNotConfiguredIsUnknownNotDerived(String term) {
		assertThat(mapping.banner(term)).isEmpty();
	}

	@Test
	void aNullTermIsUnknown() {
		assertThat(mapping.banner(null)).isEmpty();
	}

	@Test
	void severalTermsCanBeConfigured() {
		TermMapping two = TermMapping.of(Map.of("2027-SPRING", "202710", "2026-FALL", "202610"));

		assertThat(two.banner("2026-FALL")).contains("202610");
		assertThat(two.apiTerms()).containsExactly("2026-FALL", "2027-SPRING");
	}

	@Test
	void anEmptyMapIsRejectedWithAnActionableMessage() {
		assertThatThrownBy(() -> TermMapping.of(Map.of())).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("app.catalog.terms").hasMessageContaining("[2027-SPRING]=202710");
		assertThatThrownBy(() -> TermMapping.of(null)).isInstanceOf(IllegalStateException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = { "20271", "2027100", "abcdef", "20271a", "", " ", "2027-10" })
	void aBannerCodeThatIsNotSixDigitsIsRejected(String banner) {
		assertThatThrownBy(() -> TermMapping.of(Map.of("2027-SPRING", banner))).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("6-digit");
	}

	@Test
	void anApiTermThatLooksLikeABannerCodeIsRejected() {
		assertThatThrownBy(() -> TermMapping.of(Map.of("202710", "202710"))).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("looks like a Banner code");
	}

	@Test
	void aBlankApiTermIsRejected() {
		assertThatThrownBy(() -> TermMapping.of(Map.of(" ", "202710"))).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void twoApiTermsCannotShareABannerCode() {
		Map<String, String> clash = new HashMap<>();
		clash.put("2027-SPRING", "202710");
		clash.put("2027-WINTER", "202710");

		assertThatThrownBy(() -> TermMapping.of(clash)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("same Banner code");
	}

}
