package io.github.michael04.registration.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.github.michael04.registration.ai.RawPreferences.RawTimeBlock;
import io.github.michael04.registration.common.preferences.HardConstraints;
import io.github.michael04.registration.common.preferences.SoftPreferences;
import io.github.michael04.registration.common.preferences.TimeBlock;
import io.github.michael04.registration.course.DummyCourseCatalog;

class PreferenceAssemblerTest {

	private static final String TERM = DummyCourseCatalog.TERM;

	private final PreferenceAssembler assembler =
			new PreferenceAssembler(new CourseMentionResolver(new DummyCourseCatalog()));

	@Test
	void resolvesCoursesAndAppliesDefaultCredits() {
		ExtractPreferencesResponse r = assembler.assemble(TERM, raw().courses("CSE 17", "Calc 1").build());

		HardConstraints hard = r.preferences().hardConstraints();
		assertThat(hard.requiredCourseIds()).containsExactly("CSE-017", "MATH-021");
		assertThat(hard.minCredits()).isEqualTo(12);
		assertThat(hard.maxCredits()).isEqualTo(18);
		assertThat(r.unresolved()).isEmpty();
		assertThat(r.warnings()).contains("Mapped 'Calc 1' to MATH-021 (Calculus I)",
				"Credit range not mentioned; used defaults 12–18");
	}

	@Test
	void unknownCoursesGoToUnresolvedAndAreNeverInvented() {
		ExtractPreferencesResponse r = assembler.assemble(TERM, raw().courses("CSE 17", "Underwater Basket Weaving").build());

		assertThat(r.preferences().hardConstraints().requiredCourseIds()).containsExactly("CSE-017");
		assertThat(r.unresolved()).containsExactly("Underwater Basket Weaving");
	}

	@Test
	void duplicateCoursesAreRemovedAndOptionalExcludesRequired() {
		ExtractPreferencesResponse r = assembler.assemble(TERM,
				raw().courses("CSE 17", "cse017").optional("CSE-017", "ECO 1").build());

		assertThat(r.preferences().hardConstraints().requiredCourseIds()).containsExactly("CSE-017");
		assertThat(r.preferences().softPreferences().optionalCourseIds().value()).containsExactly("ECO-001");
	}

	@Test
	void warnsWhenNoRequiredCourses() {
		ExtractPreferencesResponse r = assembler.assemble(TERM, raw().build());

		assertThat(r.preferences().hardConstraints().requiredCourseIds()).isEmpty();
		assertThat(r.warnings()).anyMatch(w -> w.startsWith("No required courses"));
	}

	@Test
	void creditsAboveHardCapAreCappedAt21WithOverloadWarning() {
		ExtractPreferencesResponse r = assembler.assemble(TERM, raw().courses("CSE 17").credits(15, 24).build());

		assertThat(r.preferences().hardConstraints().maxCredits()).isEqualTo(21);
		assertThat(r.warnings()).contains("Requested 24 credits; capped at the 21-credit maximum");
		assertThat(r.warnings()).anyMatch(w -> w.contains("overload"));
	}

	@Test
	void creditsAbove18AreAnOverload() {
		ExtractPreferencesResponse r = assembler.assemble(TERM, raw().courses("CSE 17").credits(12, 19).build());

		assertThat(r.preferences().hardConstraints().maxCredits()).isEqualTo(19);
		assertThat(r.warnings()).anyMatch(w -> w.contains("overload") && w.contains("first-semester"));
	}

	@Test
	void eighteenCreditsIsNotAnOverload() {
		ExtractPreferencesResponse r = assembler.assemble(TERM, raw().courses("CSE 17").credits(12, 18).build());

		assertThat(r.warnings()).noneMatch(w -> w.contains("overload"));
	}

	/** Regression: "at least 19 credits" used to become 18–18 because the maximum defaulted to 18. */
	@ParameterizedTest(name = "minimum only {0} -> {1}-{2}")
	@CsvSource({
		// min, expected min, expected max, overload warning, cap warning
		"15, 15, 18, false, false",
		"19, 19, 19, true,  false",
		"20, 20, 20, true,  false",
		"21, 21, 21, true,  false",
		"24, 21, 21, true,  true",
	})
	void minimumOnlyRaisesTheDefaultMaximumInsteadOfLoweringTheMinimum(int min, int expectedMin, int expectedMax,
			boolean overloadWarning, boolean capWarning) {
		ExtractPreferencesResponse r = assembler.assemble(TERM, raw().courses("CSE 17").credits(min, null).build());

		assertThat(r.preferences().hardConstraints().minCredits()).isEqualTo(expectedMin);
		assertThat(r.preferences().hardConstraints().maxCredits()).isEqualTo(expectedMax);
		assertThat(r.warnings().stream().anyMatch(w -> w.contains("overload"))).isEqualTo(overloadWarning);
		assertThat(r.warnings().stream().anyMatch(w -> w.contains("capped at the 21-credit maximum"))).isEqualTo(capWarning);
		assertThat(r.warnings()).noneMatch(w -> w.contains("was above the maximum"))
			.noneMatch(w -> w.startsWith("Credit range not mentioned"));
	}

	@Test
	void maximumOnlyIsUnchanged() {
		ExtractPreferencesResponse r = assembler.assemble(TERM, raw().courses("CSE 17").credits(null, 20).build());

		assertThat(r.preferences().hardConstraints().minCredits()).isEqualTo(12);
		assertThat(r.preferences().hardConstraints().maxCredits()).isEqualTo(20);
		assertThat(r.warnings()).anyMatch(w -> w.contains("overload"));
	}

	@Test
	void bothGivenIsUnchanged() {
		ExtractPreferencesResponse r = assembler.assemble(TERM, raw().courses("CSE 17").credits(19, 20).build());

		assertThat(r.preferences().hardConstraints().minCredits()).isEqualTo(19);
		assertThat(r.preferences().hardConstraints().maxCredits()).isEqualTo(20);
		assertThat(r.warnings()).anyMatch(w -> w.contains("overload")).noneMatch(w -> w.contains("capped"));
	}

	@Test
	void minimumAboveMaximumIsLoweredToMaximum() {
		ExtractPreferencesResponse r = assembler.assemble(TERM, raw().courses("CSE 17").credits(16, 14).build());

		assertThat(r.preferences().hardConstraints().minCredits()).isEqualTo(14);
		assertThat(r.preferences().hardConstraints().maxCredits()).isEqualTo(14);
	}

	@Test
	void onlyMaximumGivenKeepsMinimumAtOrBelowIt() {
		ExtractPreferencesResponse r = assembler.assemble(TERM, raw().courses("CSE 17").credits(null, 9).build());

		assertThat(r.preferences().hardConstraints().minCredits()).isEqualTo(9);
		assertThat(r.preferences().hardConstraints().maxCredits()).isEqualTo(9);
	}

	@Test
	void validBlockedTimesAreParsedAndDaysSorted() {
		ExtractPreferencesResponse r = assembler.assemble(TERM, raw().courses("CSE 17")
				.blocked(new RawTimeBlock(List.of("thursday", "TUESDAY", "Tuesday"), "15:00", "18:00")).build());

		assertThat(r.preferences().hardConstraints().blockedTimes()).containsExactly(
				new TimeBlock(List.of(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY), LocalTime.of(15, 0), LocalTime.of(18, 0)));
	}

	@Test
	void invalidBlockedTimesAreDroppedWithWarnings() {
		ExtractPreferencesResponse r = assembler.assemble(TERM, raw().courses("CSE 17").blocked(
				new RawTimeBlock(List.of("MONDAY"), "18:00", "15:00"),
				new RawTimeBlock(List.of("FUNDAY"), "09:00", "10:00"),
				new RawTimeBlock(List.of("MONDAY"), "3pm", "6pm")).build());

		assertThat(r.preferences().hardConstraints().blockedTimes()).isEmpty();
		assertThat(r.warnings()).anyMatch(w -> w.contains("start must be before end"));
		assertThat(r.warnings()).anyMatch(w -> w.contains("unrecognized day 'FUNDAY'"));
		assertThat(r.warnings()).anyMatch(w -> w.contains("invalid blocked-time start time '3pm'"));
	}

	@Test
	void softPreferencesAreParsedWithClampedWeights() {
		ExtractPreferencesResponse r = assembler.assemble(TERM, raw().courses("CSE 17")
				.earliestStart("9:30", 9).daysOff(List.of("FRIDAY"), null).gaps(2).build());

		SoftPreferences soft = r.preferences().softPreferences();
		assertThat(soft.earliestStart().value()).isEqualTo(LocalTime.of(9, 30));
		assertThat(soft.earliestStart().weight()).isEqualTo(5);
		assertThat(soft.daysOff().value()).containsExactly(DayOfWeek.FRIDAY);
		assertThat(soft.daysOff().weight()).isEqualTo(3);
		assertThat(soft.minimizeGaps().weight()).isEqualTo(2);
		assertThat(soft.latestEnd()).isNull();
		assertThat(soft.optionalCourseIds()).isNull();
	}

	@Test
	void zeroWeightPreferencesAreDropped() {
		ExtractPreferencesResponse r = assembler.assemble(TERM, raw().courses("CSE 17")
				.earliestStart("10:00", 0).gaps(0).build());

		assertThat(r.preferences().softPreferences().earliestStart()).isNull();
		assertThat(r.preferences().softPreferences().minimizeGaps()).isNull();
	}

	@Test
	void modelAssumptionsBecomeWarnings() {
		ExtractPreferencesResponse r = assembler.assemble(TERM, raw().courses("CSE 17")
				.assumptions("Interpreted 'mornings off' as earliest start 12:00", " ", null).build());

		assertThat(r.warnings()).contains("Interpreted 'mornings off' as earliest start 12:00");
	}

	@Test
	void dummyExtractorOutputMatchesContractExample() {
		ExtractPreferencesResponse r = assembler.assemble(TERM, new DummyPreferenceExtractor().extract("ignored"));

		HardConstraints hard = r.preferences().hardConstraints();
		assertThat(hard.requiredCourseIds()).containsExactly("CSE-017", "MATH-021");
		assertThat(hard.blockedTimes()).hasSize(1);
		assertThat(r.preferences().softPreferences().earliestStart().weight()).isEqualTo(4);
		assertThat(r.preferences().softPreferences().daysOff().value()).containsExactly(DayOfWeek.FRIDAY);
		assertThat(r.warnings()).contains(DummyPreferenceExtractor.NOTICE);
	}

	private static Builder raw() {
		return new Builder();
	}

	/** Test helper so each test only sets the fields it cares about. */
	private static final class Builder {

		private List<String> required = List.of();
		private List<String> optional = List.of();
		private Integer min;
		private Integer max;
		private List<RawTimeBlock> blocked = List.of();
		private String earliest;
		private Integer earliestWeight;
		private List<String> daysOff;
		private Integer daysOffWeight;
		private Integer gaps;
		private List<String> assumptions;

		Builder courses(String... mentions) { required = List.of(mentions); return this; }
		Builder optional(String... mentions) { optional = List.of(mentions); return this; }
		Builder credits(Integer min, Integer max) { this.min = min; this.max = max; return this; }
		Builder blocked(RawTimeBlock... blocks) { blocked = List.of(blocks); return this; }
		Builder earliestStart(String time, Integer weight) { earliest = time; earliestWeight = weight; return this; }
		Builder daysOff(List<String> days, Integer weight) { daysOff = days; daysOffWeight = weight; return this; }
		Builder gaps(Integer weight) { gaps = weight; return this; }
		Builder assumptions(String... notes) { assumptions = java.util.Arrays.asList(notes); return this; }

		RawPreferences build() {
			return new RawPreferences(required, optional, null, min, max, blocked,
					earliest, earliestWeight, null, null, daysOff, daysOffWeight, gaps, assumptions);
		}

	}

}
