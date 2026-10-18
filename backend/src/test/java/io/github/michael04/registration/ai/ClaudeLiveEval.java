package io.github.michael04.registration.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import io.github.michael04.registration.course.DummyCourseCatalog;

/**
 * LIVE evaluation against the real Claude API. Costs money; never part of the normal build.
 *
 * <p>Two guards keep it out of {@code ./mvnw test}: the class name doesn't match Surefire's
 * {@code *Test}/{@code *Tests} patterns, and it only runs when {@code RUN_LIVE_AI=true} and
 * {@code ANTHROPIC_API_KEY} are both set. Run it explicitly:
 *
 * <pre>
 * RUN_LIVE_AI=true ./mvnw test -Dtest=ClaudeLiveEval -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * About 20 API calls per run (plus any retries). Model: {@code ANTHROPIC_MODEL} (default claude-haiku-5-5).
 * Passes when at least 80% of cases meet every expectation; prints a report either way.
 */
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_AI", matches = "true")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class ClaudeLiveEval {

	private static final double MIN_PASS_RATE = 0.8;

	@Test
	void claudeMeetsTheEvaluationExpectations() {
		String model = System.getenv().getOrDefault("ANTHROPIC_MODEL", "claude-haiku-5-5");
		ClaudeProperties props = new ClaudeProperties(System.getenv("ANTHROPIC_API_KEY"), model, Duration.ofSeconds(30), 1,
				4096L, null);
		PreferenceAssembler assembler = new PreferenceAssembler(new CourseMentionResolver(new DummyCourseCatalog()));
		List<EvalCases.Case> cases = EvalCases.load();
		List<String> report = new ArrayList<>();
		int passed = 0;

		try (ClaudePreferenceExtractor extractor = new ClaudePreferenceExtractor(AnthropicClaudeGateway.create(props),
				ClaudePreferenceExtractor.loadSystemPrompt())) {
			for (EvalCases.Case c : cases) {
				List<String> problems;
				try {
					problems = EvalCases.check(c, assembler.assemble(DummyCourseCatalog.TERM, extractor.extract(c.text())));
				}
				catch (RuntimeException ex) {
					problems = List.of("request failed: " + ex.getClass().getSimpleName());
				}
				if (problems.isEmpty()) {
					passed++;
					report.add("PASS  " + c.id());
				}
				else {
					report.add("FAIL  " + c.id() + "\n        - " + String.join("\n        - ", problems));
				}
			}
		}

		System.out.println("\n=== Claude live evaluation (" + model + "): " + passed + "/" + cases.size() + " passed ===");
		report.forEach(System.out::println);
		assertThat((double) passed / cases.size()).as("pass rate").isGreaterThanOrEqualTo(MIN_PASS_RATE);
	}

}
