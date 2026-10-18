package io.github.michael04.registration.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.michael04.registration.course.DummyCourseCatalog;

/**
 * Offline run of the evaluation set: each case's {@code fakeModelOutput} is served by
 * {@link FakeAnthropicServer} through the real SDK, real {@link AnthropicClaudeGateway},
 * {@link ClaudePreferenceExtractor} and {@link PreferenceAssembler}. Proves the expectations are
 * consistent with our Java pipeline. It does NOT measure Claude's quality; {@link ClaudeLiveEval} does.
 */
class AiEvalDatasetTest {

	private static final Set<String> REQUIRED_CATEGORIES = Set.of("exact-course-codes", "course-nicknames",
			"unknown-course", "ambiguous-course", "earliest-start", "days-off", "hard-blocked-time", "soft-preferences",
			"multiple-constraints", "credit-overload", "credit-hard-cap", "incomplete-request",
			"contradictory-preferences", "prompt-injection", "long-request");

	private static FakeAnthropicServer server;

	private static ClaudePreferenceExtractor extractor;

	private static final PreferenceAssembler assembler =
			new PreferenceAssembler(new CourseMentionResolver(new DummyCourseCatalog()));

	@BeforeAll
	static void startServer() {
		server = new FakeAnthropicServer();
		extractor = new ClaudePreferenceExtractor(
				AnthropicClaudeGateway.create(new ClaudeProperties("test-key", "claude-haiku-5-5", Duration.ofSeconds(5), 0,
						4096L, server.baseUrl())),
				ClaudePreferenceExtractor.loadSystemPrompt());
	}

	@AfterAll
	static void stopServer() {
		extractor.close();
		server.close();
	}

	static List<EvalCases.Case> cases() {
		return EvalCases.load();
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("cases")
	void fakeModelOutputPassesThePipelineAsExpected(EvalCases.Case c) {
		server.enqueue(FakeAnthropicServer.message("end_turn", EvalCases.MAPPER.writeValueAsString(c.fakeModelOutput())));

		ExtractPreferencesResponse response = assembler.assemble(DummyCourseCatalog.TERM, extractor.extract(c.text()));

		assertThat(EvalCases.check(c, response)).as("case %s", c.id()).isEmpty();
	}

	@Test
	void checkerReportsMismatchesInsteadOfPassingEverything() {
		EvalCases.Case jobCase = byId("job-blocked-time");
		EvalCases.Case capCase = byId("over-hard-cap");
		// Serve the job case's model output, but judge it against the hard-cap case's expectations.
		server.enqueue(FakeAnthropicServer.message("end_turn", EvalCases.MAPPER.writeValueAsString(jobCase.fakeModelOutput())));
		ExtractPreferencesResponse response = assembler.assemble(DummyCourseCatalog.TERM, extractor.extract(jobCase.text()));

		List<String> problems = EvalCases.check(capCase, response);

		assertThat(problems).anyMatch(p -> p.startsWith("requiredCourseIds"))
			.anyMatch(p -> p.startsWith("maxCredits"))
			.anyMatch(p -> p.contains("capped at the 21-credit maximum"));
	}

	private static EvalCases.Case byId(String id) {
		return EvalCases.load().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
	}

	@Test
	void datasetHas18To20UniqueCasesCoveringEveryCategory() {
		List<EvalCases.Case> cases = EvalCases.load();

		assertThat(cases).hasSizeBetween(18, 20);
		assertThat(cases.stream().map(EvalCases.Case::id).collect(Collectors.toSet())).hasSameSizeAs(cases);
		assertThat(cases.stream().map(EvalCases.Case::category).collect(Collectors.toSet())).containsAll(REQUIRED_CATEGORIES);
		assertThat(cases).allSatisfy(c -> {
			assertThat(c.text()).isNotBlank().hasSizeLessThanOrEqualTo(2000);
			assertThat(c.expect().has("requiredCourseIds")).as("%s needs requiredCourseIds", c.id()).isTrue();
		});
	}

}
