package io.github.michael04.registration.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Provider selection. None of these contexts makes a network call (building the SDK client is offline). */
class AiConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(AiConfiguration.class)
		.withPropertyValues("app.ai.anthropic.model=claude-haiku-5-5");

	@Test
	void defaultIsUnavailable() {
		runner.run(ctx -> assertThat(ctx.getBean(PreferenceExtractor.class)).isInstanceOf(UnavailablePreferenceExtractor.class));
	}

	@Test
	void noneIsUnavailable() {
		runner.withPropertyValues("app.ai.provider=none")
			.run(ctx -> assertThat(ctx.getBean(PreferenceExtractor.class)).isInstanceOf(UnavailablePreferenceExtractor.class));
	}

	@Test
	void dummyMustBeChosenExplicitly() {
		runner.withPropertyValues("app.ai.provider=dummy")
			.run(ctx -> assertThat(ctx.getBean(PreferenceExtractor.class)).isInstanceOf(DummyPreferenceExtractor.class));
	}

	@Test
	void claudeWithApiKeyUsesTheClaudeExtractor() {
		runner.withPropertyValues("app.ai.provider=claude", "app.ai.anthropic.api-key=test-key-not-real")
			.run(ctx -> assertThat(ctx.getBean(PreferenceExtractor.class)).isInstanceOf(ClaudePreferenceExtractor.class));
	}

	@Test
	void claudeWithoutApiKeyStartsAndReturns503NeverDummy() {
		runner.withPropertyValues("app.ai.provider=claude", "app.ai.anthropic.api-key=")
			.run(ctx -> {
				assertThat(ctx).hasNotFailed();
				PreferenceExtractor extractor = ctx.getBean(PreferenceExtractor.class);
				assertThat(extractor).isInstanceOf(UnavailablePreferenceExtractor.class).isNotInstanceOf(DummyPreferenceExtractor.class);
				assertThatThrownBy(() -> extractor.extract("I need CSE 17")).isInstanceOf(AiUnavailableException.class);
			});
	}

	@Test
	void claudeWithBlankModelFailsAtStartup() {
		runner.withPropertyValues("app.ai.provider=claude", "app.ai.anthropic.api-key=test-key-not-real",
				"app.ai.anthropic.model= ")
			.run(ctx -> assertThat(ctx).hasFailed());
	}

	@Test
	void unknownProviderFailsAtStartup() {
		runner.withPropertyValues("app.ai.provider=gpt").run(ctx -> assertThat(ctx).hasFailed());
	}

	@Test
	void providerNameIsCaseInsensitive() {
		runner.withPropertyValues("app.ai.provider= Dummy ")
			.run(ctx -> assertThat(ctx.getBean(PreferenceExtractor.class)).isInstanceOf(DummyPreferenceExtractor.class));
	}

	@Test
	void propertiesNeverPrintTheApiKey() {
		ClaudeProperties props = new ClaudeProperties("fake-key-for-masking-test", "claude-haiku-5-5", null, null, null, null);

		assertThat(props.toString()).doesNotContain("fake-key-for-masking-test").contains("***");
	}

	@Test
	void timeoutsAndRetriesHaveBoundedDefaults() {
		ClaudeProperties props = new ClaudeProperties("k", "m", null, null, null, null);

		assertThat(props.timeout()).hasSeconds(20);
		assertThat(props.maxRetries()).isEqualTo(1);
		assertThat(props.maxTokens()).isEqualTo(4096L);
		assertThatThrownBy(() -> new ClaudeProperties("k", "m", null, 9, null, null))
			.isInstanceOf(IllegalArgumentException.class);
	}

}
