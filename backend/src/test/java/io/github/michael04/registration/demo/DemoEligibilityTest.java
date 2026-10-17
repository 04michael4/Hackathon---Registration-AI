package io.github.michael04.registration.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class DemoEligibilityTest {

	private static DemoFixture.Offering o(String id) {
		return DemoFixtureLoaderTest.synthetic().offerings().stream().filter(x -> x.offeringId().equals(id)).findFirst().orElseThrow();
	}

	private static DemoProfile standing(String s) {
		return new DemoProfile(s, null, null, List.of());
	}

	private static DemoWarning warning(DemoEligibility.CourseReport r, String code) {
		return r.warnings().stream().filter(w -> w.code().equals(code)).findFirst().orElseThrow(() -> new AssertionError("no " + code + " in " + r.warnings()));
	}

	@Test
	void everyOfferingAlwaysCarriesTheEligibilityNotConfirmedWarning() {
		for (DemoFixture.Offering offering : DemoFixtureLoaderTest.synthetic().offerings()) {
			var report = DemoEligibility.report(offering, DemoProfile.NONE);
			assertThat(report.warnings()).extracting(DemoWarning::code).contains("REGISTRATION_ELIGIBILITY_NOT_CONFIRMED");
			assertThat(report.status()).isIn(DemoEligibility.UNVERIFIED, DemoEligibility.OVERRIDE_REQUIRED).isNotEqualTo("ELIGIBLE");
		}
	}

	@Test
	void aMissingProfileIsUnresolvedNeverEligible() {
		var report = DemoEligibility.report(o("SYN-101"), DemoProfile.NONE);
		assertThat(warning(report, "CLASS_STANDING").outcome()).isEqualTo("UNRESOLVED");
		assertThat(warning(report, "CLASS_STANDING").text()).contains("was not provided");
		assertThat(report.status()).isEqualTo(DemoEligibility.UNVERIFIED);
	}

	@Test
	void aFirstYearStudentFailsAnFrExclusionAndNeedsAnOverride() {
		var report = DemoEligibility.report(o("SYN-101"), standing("FR"));
		assertThat(warning(report, "CLASS_STANDING").outcome()).isEqualTo(DemoEligibility.OVERRIDE_REQUIRED);
		assertThat(warning(report, "CLASS_STANDING").text()).contains("override may be required").contains("not guaranteed");
		assertThat(report.status()).isEqualTo(DemoEligibility.OVERRIDE_REQUIRED);
	}

	@Test
	void aNonFirstYearStandingSatisfiesTheFrExclusionButStaysUnverified() {
		var report = DemoEligibility.report(o("SYN-101"), standing("JR"));
		assertThat(warning(report, "CLASS_STANDING").outcome()).isEqualTo("SATISFIED");
		assertThat(report.status()).isEqualTo(DemoEligibility.UNVERIFIED);
		assertThat(warning(report, "PREREQUISITE_NOT_VERIFIED").text()).startsWith("Prerequisite not verified:");
	}

	@Test
	void aMustBeOneOfRuleNeedsTheListedStandings() {
		assertThat(warning(DemoEligibility.report(o("SYN-201-010"), standing("SO")), "CLASS_STANDING").outcome()).isEqualTo(DemoEligibility.OVERRIDE_REQUIRED);
		assertThat(warning(DemoEligibility.report(o("SYN-201-010"), standing("SR")), "CLASS_STANDING").outcome()).isEqualTo("SATISFIED");
		assertThat(warning(DemoEligibility.report(o("SYN-201-010"), standing("GR")), "CLASS_STANDING").outcome()).isEqualTo("SATISFIED");
	}

	@Test
	void levelCollegeMajorAndDepartmentApprovalAreEvaluatedOrLeftUnresolved() {
		var none = DemoEligibility.report(o("SYN-501"), DemoProfile.NONE);
		assertThat(List.of("LEVEL", "COLLEGE", "MAJOR", "DEPARTMENT_APPROVAL")).allSatisfy(code -> assertThat(warning(none, code).outcome()).isEqualTo("UNRESOLVED"));
		var meets = DemoEligibility.report(o("SYN-501"), new DemoProfile("GR", "GR", "EN", List.of("cse")));
		assertThat(warning(meets, "LEVEL").outcome()).isEqualTo("SATISFIED");
		assertThat(warning(meets, "COLLEGE").outcome()).isEqualTo("SATISFIED");
		assertThat(warning(meets, "MAJOR").outcome()).isEqualTo("SATISFIED");
		assertThat(warning(meets, "DEPARTMENT_APPROVAL").outcome()).as("the demo cannot check permission").isEqualTo("UNRESOLVED");
		var fails = DemoEligibility.report(o("SYN-501"), new DemoProfile("SR", "UG", "AS", List.of("ART")));
		assertThat(List.of("LEVEL", "COLLEGE", "MAJOR")).allSatisfy(code -> assertThat(warning(fails, code).outcome()).isEqualTo(DemoEligibility.OVERRIDE_REQUIRED));
	}

	@Test
	void anArrangedTeamMeetingIsFlaggedAsTimeToBeArranged() {
		var w = warning(DemoEligibility.report(o("SYN-401"), DemoProfile.NONE), "ARRANGED_MEETING");
		assertThat(w.text()).contains("Additional team meeting time must be arranged").contains("Time to be arranged");
	}

	@Test
	void creditExclusionsAndAliasesAreShownAsMayApply() {
		var report = DemoEligibility.report(o("SYN-602"), DemoProfile.NONE);
		assertThat(warning(report, "CREDIT_EXCLUSION").text()).startsWith("Credit exclusion may apply:");
		assertThat(warning(report, "CATALOG_ALIAS").text()).contains("ALT 602");
	}

	@Test
	void anIndependentStudySectionTypeIsShownAndTheTeamPolicyDecisionIsLabelled() {
		var report = DemoEligibility.report(o("SYN-301"), DemoProfile.NONE);
		assertThat(warning(report, "ADMINISTRATIVE_TYPE").text()).contains("Independent Study");
		assertThat(warning(report, "NOTE").source()).isEqualTo("TEAM_POLICY");
	}

	@Test
	void requiredLabsAreExplainedAndStandaloneClaimsAreNotInvented() {
		assertThat(warning(DemoEligibility.report(o("SYN-102"), DemoProfile.NONE), "REQUIRED_LAB").text())
			.contains("Exactly one lab").contains("adds 0 credits");
		assertThat(warning(DemoEligibility.report(o("SYN-101"), DemoProfile.NONE), "LINKED_COMPONENTS_UNVERIFIED").text()).contains("not proof");
	}

	@Test
	void theProfileNormalizesCaseAndDropsBlanks() {
		var p = new DemoProfile(" jr ", "", null, java.util.Arrays.asList("cse", " ", null, "CSE"));
		assertThat(p.classStanding()).isEqualTo("JR");
		assertThat(p.level()).isNull();
		assertThat(p.majors()).containsExactly("CSE");
	}

}
