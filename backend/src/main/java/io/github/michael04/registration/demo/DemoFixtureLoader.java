package io.github.michael04.registration.demo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Reads and validates the demo fixture. A malformed fixture fails startup with a precise message; nothing is guessed. */
public final class DemoFixtureLoader {

	private static final JsonMapper MAPPER = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

	private DemoFixtureLoader() {
	}

	public static DemoFixture load(String location) {
		if (location == null || location.isBlank()) {
			throw new IllegalStateException("CATALOG_SOURCE=demo needs DEMO_FIXTURE_PATH (app.demo.fixture-path): a fixture built by "
					+ "data/catalog-promotion/tools/build_demo_fixture.py (kept outside the repository)");
		}
		try (InputStream in = open(location)) {
			return validate(MAPPER.readValue(in, DemoFixture.class), location);
		}
		catch (IOException | tools.jackson.core.JacksonException ex) {
			throw new IllegalStateException("Cannot read the demo fixture " + location + ": " + ex.getMessage(), ex);
		}
	}

	private static InputStream open(String location) throws IOException {
		if (location.startsWith("classpath:")) {
			InputStream in = DemoFixtureLoader.class.getResourceAsStream("/" + location.substring("classpath:".length()).replaceFirst("^/", ""));
			if (in == null) {
				throw new IOException("classpath resource not found: " + location);
			}
			return in;
		}
		return Files.newInputStream(Path.of(location));
	}

	static DemoFixture validate(DemoFixture fixture, String location) {
		if (fixture.term() == null || fixture.term().isBlank() || fixture.offerings().isEmpty()) {
			throw new IllegalStateException("The demo fixture " + location + " has no term or no offerings");
		}
		Set<String> ids = new HashSet<>();
		Set<String> crns = new HashSet<>();
		for (DemoFixture.Offering o : fixture.offerings()) {
			if (o.offeringId() == null || o.offeringId().isBlank() || !o.offeringId().equals(o.courseId())) {
				throw new IllegalStateException("Offering ids must be present and equal the course id: " + o.offeringId());
			}
			if (!ids.add(o.offeringId())) {
				throw new IllegalStateException("Duplicate offering " + o.offeringId());
			}
			if (o.title() == null || o.title().isBlank() || o.credits() < 0) {
				throw new IllegalStateException("Offering " + o.offeringId() + " needs a title and non-negative credits");
			}
			Set<String> lectures = new HashSet<>();
			for (DemoFixture.Section s : o.sections()) {
				if (s.crn() == null || !s.crn().matches("\\d{5}") || !crns.add(s.crn())) {
					throw new IllegalStateException("Offering " + o.offeringId() + ": missing, malformed or duplicate CRN " + s.crn());
				}
				if (!"LECTURE".equals(s.component()) && !"LAB".equals(s.component())) {
					throw new IllegalStateException("Offering " + o.offeringId() + " CRN " + s.crn() + ": unsupported component " + s.component());
				}
				if (s.credits() < 0) {
					throw new IllegalStateException("Offering " + o.offeringId() + " CRN " + s.crn() + ": negative credits");
				}
				if ("LECTURE".equals(s.component())) {
					lectures.add(s.crn());
				}
			}
			for (DemoFixture.Section s : o.sections()) {
				boolean lab = "LAB".equals(s.component());
				if (lab && (s.lectureCrn() == null || !lectures.contains(s.lectureCrn()))) {
					throw new IllegalStateException("Offering " + o.offeringId() + ": lab " + s.crn() + " is not linked to a lecture of the offering");
				}
				if (!lab && s.lectureCrn() != null) {
					throw new IllegalStateException("Offering " + o.offeringId() + ": lecture " + s.crn() + " must not name a lecture");
				}
				if (lab && s.credits() != 0) {
					throw new IllegalStateException("Offering " + o.offeringId() + ": lab " + s.crn() + " must carry zero incremental credits");
				}
			}
			if (lectures.isEmpty()) {
				throw new IllegalStateException("Offering " + o.offeringId() + " has no lecture section");
			}
		}
		return fixture;
	}

}
