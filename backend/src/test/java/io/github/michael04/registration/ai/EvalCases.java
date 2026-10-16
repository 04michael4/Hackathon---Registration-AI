package io.github.michael04.registration.ai;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonFormat;

import io.github.michael04.registration.common.json.JsonConfiguration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loads {@code ai-eval/cases.json} and checks an {@link ExtractPreferencesResponse} against a case's
 * expectations. Shared by the offline {@link AiEvalDatasetTest} and the opt-in {@link ClaudeLiveEval}.
 *
 * <p>Comparisons are semantic: course IDs and days as sets, times exact, weights within a range.
 */
final class EvalCases {

	static final String RESOURCE = "ai-eval/cases.json";

	static final JsonMapper MAPPER = contractMapper();

	record Case(String id, String category, String text, JsonNode fakeModelOutput, JsonNode expect) {

		@Override
		public String toString() {
			return id;
		}

	}

	private EvalCases() {
	}

	static List<Case> load() {
		try (InputStream in = EvalCases.class.getClassLoader().getResourceAsStream(RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException("Missing " + RESOURCE);
			}
			List<Case> cases = new ArrayList<>();
			for (JsonNode c : MAPPER.readTree(in).path("cases")) {
				cases.add(new Case(c.path("id").asString(), c.path("category").asString(), c.path("text").asString(),
						c.path("fakeModelOutput"), c.path("expect")));
			}
			return cases;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** @return human-readable problems; empty means the case passed */
	static List<String> check(Case c, ExtractPreferencesResponse response) {
		JsonNode actual = MAPPER.valueToTree(response);
		JsonNode hard = actual.path("preferences").path("hardConstraints");
		JsonNode soft = actual.path("preferences").path("softPreferences");
		JsonNode e = c.expect();
		List<String> problems = new ArrayList<>();

		sameSet("requiredCourseIds", e.path("requiredCourseIds"), hard.path("requiredCourseIds"), problems);
		if (e.has("optionalCourseIds")) {
			sameSet("optionalCourseIds", e.path("optionalCourseIds"), soft.path("optionalCourseIds").path("value"), problems);
		}
		if (e.has("unresolved")) {
			int n = actual.path("unresolved").size();
			int min = e.path("unresolved").path("min").asInt(0);
			int max = e.path("unresolved").path("max").asInt(Integer.MAX_VALUE);
			if (n < min || n > max) {
				problems.add("unresolved count " + n + " not in [" + min + "," + max + "]: " + actual.path("unresolved"));
			}
		}
		exactInt("minCredits", e, hard, problems);
		exactInt("maxCredits", e, hard, problems);
		if (e.has("maxCreditsAtMost") && hard.path("maxCredits").asInt() > e.path("maxCreditsAtMost").asInt()) {
			problems.add("maxCredits " + hard.path("maxCredits") + " above " + e.path("maxCreditsAtMost"));
		}
		if (e.has("blockedTimes")) {
			Set<String> expected = blocks(e.path("blockedTimes"));
			Set<String> got = blocks(hard.path("blockedTimes"));
			if (!expected.equals(got)) {
				problems.add("blockedTimes expected " + expected + " but got " + got);
			}
		}
		for (var entry : e.path("soft").properties()) {
			checkSoft(entry.getKey(), entry.getValue(), soft.path(entry.getKey()), problems);
		}
		for (JsonNode name : e.path("absentSoft")) {
			if (soft.has(name.asString())) {
				problems.add("softPreferences." + name.asString() + " should be absent but was " + soft.path(name.asString()));
			}
		}
		List<String> warnings = new ArrayList<>();
		actual.path("warnings").forEach(w -> warnings.add(w.asString().toLowerCase(Locale.ROOT)));
		for (JsonNode want : e.path("warningsContain")) {
			String needle = want.asString().toLowerCase(Locale.ROOT);
			if (warnings.stream().noneMatch(w -> w.contains(needle))) {
				problems.add("no warning containing '" + want.asString() + "' in " + actual.path("warnings"));
			}
		}
		for (JsonNode unwanted : e.path("warningsAbsent")) {
			String needle = unwanted.asString().toLowerCase(Locale.ROOT);
			if (warnings.stream().anyMatch(w -> w.contains(needle))) {
				problems.add("unexpected warning containing '" + unwanted.asString() + "' in " + actual.path("warnings"));
			}
		}
		return problems;
	}

	private static void checkSoft(String name, JsonNode expected, JsonNode actual, List<String> problems) {
		if (actual.isMissingNode() || actual.isNull()) {
			problems.add("softPreferences." + name + " missing");
			return;
		}
		if (expected.has("value")) {
			JsonNode want = expected.path("value");
			JsonNode got = actual.path("value");
			boolean ok = want.isArray() ? strings(want).equals(strings(got)) : want.asString().equals(got.asString());
			if (!ok) {
				problems.add("softPreferences." + name + ".value expected " + want + " but got " + got);
			}
		}
		if (expected.has("weight")) {
			int weight = actual.path("weight").asInt();
			int min = expected.path("weight").get(0).asInt();
			int max = expected.path("weight").get(1).asInt();
			if (weight < min || weight > max) {
				problems.add("softPreferences." + name + ".weight " + weight + " not in [" + min + "," + max + "]");
			}
		}
	}

	private static void sameSet(String label, JsonNode expected, JsonNode actual, List<String> problems) {
		if (!strings(expected).equals(strings(actual))) {
			problems.add(label + " expected " + strings(expected) + " but got " + strings(actual));
		}
	}

	private static void exactInt(String field, JsonNode expect, JsonNode hard, List<String> problems) {
		if (expect.has(field) && expect.path(field).asInt() != hard.path(field).asInt()) {
			problems.add(field + " expected " + expect.path(field) + " but got " + hard.path(field));
		}
	}

	private static Set<String> strings(JsonNode array) {
		Set<String> out = new HashSet<>();
		array.forEach(v -> out.add(v.asString()));
		return out;
	}

	private static Set<String> blocks(JsonNode array) {
		Set<String> out = new HashSet<>();
		array.forEach(b -> out.add(new java.util.TreeSet<>(strings(b.path("days"))) + " " + b.path("start").asString() + "-"
				+ b.path("end").asString()));
		return out;
	}

	/** Same "HH:mm" time format the app uses (see {@link JsonConfiguration}). */
	private static JsonMapper contractMapper() {
		return JsonMapper.builder()
			.withConfigOverride(LocalTime.class,
					o -> o.setFormat(JsonFormat.Value.forPattern(JsonConfiguration.TIME_PATTERN)))
			.build();
	}

}
