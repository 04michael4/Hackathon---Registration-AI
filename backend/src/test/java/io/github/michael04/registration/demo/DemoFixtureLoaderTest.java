package io.github.michael04.registration.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DemoFixtureLoaderTest {

	static final String SYNTHETIC = "classpath:demo/synthetic-fixture.json";

	static DemoFixture synthetic() {
		return DemoFixtureLoader.load(SYNTHETIC);
	}

	private static DemoFixture with(DemoFixture f, DemoFixture.Offering replacement) {
		List<DemoFixture.Offering> all = new ArrayList<>(f.offerings());
		all.removeIf(o -> o.offeringId().equals(replacement.offeringId()));
		all.add(replacement);
		return new DemoFixture(f.term(), f.source(), f.caution(), all);
	}

	private static DemoFixture.Offering offering(DemoFixture f, String id) {
		return f.offerings().stream().filter(o -> o.offeringId().equals(id)).findFirst().orElseThrow();
	}

	private static DemoFixture.Offering withSections(DemoFixture.Offering o, List<DemoFixture.Section> sections) {
		return new DemoFixture.Offering(o.offeringId(), o.courseId(), o.subject(), o.number(), o.topic(), o.title(), o.credits(),
				o.administrativeType(), o.dataSource(), o.tier(), sections, o.rules(), o.prerequisiteNote(), o.components(),
				o.arrangedMeeting(), o.creditExclusions(), o.aliases(), o.crossListing(), o.notes());
	}

	@Test
	void loadsTheSyntheticFixtureAndIdentifiesEveryOffering() {
		DemoFixture f = synthetic();
		assertThat(f.term()).isEqualTo("2027-SPRING");
		assertThat(f.offerings()).extracting(DemoFixture.Offering::offeringId).containsExactlyInAnyOrder("SYN-101", "SYN-102", "SYN-103",
				"SYN-201-010", "SYN-201-020", "SYN-301", "SYN-401", "SYN-501", "SYN-601", "SYN-602", "SYN-701");
	}

	@Test
	void labsAreLinkedToTheirLectureAndCarryNoCreditsOfTheirOwn() {
		DemoFixture.Offering o = offering(synthetic(), "SYN-102");
		assertThat(o.sections()).extracting(DemoFixture.Section::component).containsExactly("LECTURE", "LAB", "LAB");
		assertThat(o.sections().stream().filter(s -> s.component().equals("LAB"))).allSatisfy(lab -> {
			assertThat(lab.credits()).isZero();
			assertThat(lab.lectureCrn()).isEqualTo("91101");
		});
	}

	@Test
	void aMissingPathFailsWithAClearMessage() {
		assertThatThrownBy(() -> DemoFixtureLoader.load("")).isInstanceOf(IllegalStateException.class).hasMessageContaining("DEMO_FIXTURE_PATH");
		assertThatThrownBy(() -> DemoFixtureLoader.load(null)).isInstanceOf(IllegalStateException.class).hasMessageContaining("DEMO_FIXTURE_PATH");
		assertThatThrownBy(() -> DemoFixtureLoader.load("/no/such/file.json")).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Cannot read the demo fixture");
	}

	@Test
	void anUnknownPropertyIsRefusedInsteadOfIgnored(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("f.json");
		Files.writeString(file, "{\"term\":\"T\",\"offerings\":[],\"surprise\":1}");
		assertThatThrownBy(() -> DemoFixtureLoader.load(file.toString())).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void aLabWithoutALectureOfItsOwnOfferingIsRefused() {
		DemoFixture f = synthetic();
		DemoFixture.Offering o = offering(f, "SYN-102");
		List<DemoFixture.Section> sections = new ArrayList<>(o.sections());
		sections.set(1, new DemoFixture.Section("91102", "060", "LAB", 0, "99999", sections.get(1).meetings()));
		assertThatThrownBy(() -> DemoFixtureLoader.validate(with(f, withSections(o, sections)), "t"))
			.hasMessageContaining("not linked to a lecture");
	}

	@Test
	void aLabThatCarriesCreditsIsRefused() {
		DemoFixture f = synthetic();
		DemoFixture.Offering o = offering(f, "SYN-102");
		List<DemoFixture.Section> sections = new ArrayList<>(o.sections());
		sections.set(1, new DemoFixture.Section("91102", "060", "LAB", 1, "91101", sections.get(1).meetings()));
		assertThatThrownBy(() -> DemoFixtureLoader.validate(with(f, withSections(o, sections)), "t")).hasMessageContaining("zero incremental credits");
	}

	@Test
	void duplicateCrnsAndOfferingsAndMalformedCrnsAreRefused() {
		DemoFixture f = synthetic();
		DemoFixture.Offering a = offering(f, "SYN-101");
		DemoFixture.Offering b = offering(f, "SYN-103");
		DemoFixture dupCrn = with(f, withSections(b, List.of(new DemoFixture.Section("91001", "010", "LECTURE", 3, null, List.of()))));
		assertThatThrownBy(() -> DemoFixtureLoader.validate(dupCrn, "t")).hasMessageContaining("duplicate CRN");
		DemoFixture bad = with(f, withSections(a, List.of(new DemoFixture.Section("12", "010", "LECTURE", 3, null, List.of()))));
		assertThatThrownBy(() -> DemoFixtureLoader.validate(bad, "t")).hasMessageContaining("malformed");
		List<DemoFixture.Offering> twice = new ArrayList<>(f.offerings());
		twice.add(a);
		assertThatThrownBy(() -> DemoFixtureLoader.validate(new DemoFixture(f.term(), f.source(), f.caution(), twice), "t")).hasMessageContaining("Duplicate offering");
	}

	@Test
	void anOfferingWithoutALectureIsRefused() {
		DemoFixture f = synthetic();
		DemoFixture.Offering o = offering(f, "SYN-101");
		assertThatThrownBy(() -> DemoFixtureLoader.validate(with(f, withSections(o, List.of())), "t")).hasMessageContaining("no lecture section");
	}

}
