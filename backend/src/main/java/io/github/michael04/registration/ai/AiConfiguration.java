package io.github.michael04.registration.ai;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses the {@link PreferenceExtractor} from {@code app.ai.provider} (env var {@code AI_PROVIDER}).
 * Every mode must be chosen explicitly; nothing ever falls back to dummy output.
 *
 * <ul>
 * <li>{@code none} (default): extraction returns 503.</li>
 * <li>{@code dummy}: fixed example, no API key needed.</li>
 * <li>{@code claude}: real Claude extraction. Without {@code ANTHROPIC_API_KEY} the app still starts and
 * extraction returns 503.</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(ClaudeProperties.class)
public class AiConfiguration {

	private static final Logger log = LoggerFactory.getLogger(AiConfiguration.class);

	@Bean
	PreferenceExtractor preferenceExtractor(@Value("${app.ai.provider:none}") String provider, ClaudeProperties claude) {
		return switch (provider.trim().toLowerCase(Locale.ROOT)) {
			case "none", "" -> new UnavailablePreferenceExtractor();
			case "dummy" -> new DummyPreferenceExtractor();
			case "claude" -> claudeExtractor(claude);
			default -> throw new IllegalStateException(
					"Unknown app.ai.provider '" + provider + "' (expected 'none', 'dummy' or 'claude')");
		};
	}

	private static PreferenceExtractor claudeExtractor(ClaudeProperties claude) {
		if (!claude.hasApiKey()) {
			log.warn("AI_PROVIDER=claude but ANTHROPIC_API_KEY is not set; preference extraction will return 503");
			return new UnavailablePreferenceExtractor();
		}
		if (claude.model().isEmpty()) {
			throw new IllegalStateException("ANTHROPIC_MODEL must not be blank when AI_PROVIDER=claude");
		}
		log.info("Claude preference extraction enabled: model={}, timeout={}, maxRetries={}", claude.model(),
				claude.timeout(), claude.maxRetries());
		return new ClaudePreferenceExtractor(AnthropicClaudeGateway.create(claude), ClaudePreferenceExtractor.loadSystemPrompt());
	}

	@Bean
	ScheduleExplainer scheduleExplainer(@Value("${app.ai.provider:none}") String provider, ClaudeProperties claude) {
		if ("claude".equals(provider.trim().toLowerCase(Locale.ROOT)) && claude.hasApiKey()) {
			return new AnthropicScheduleExplainer(claude);
		}
		return facts -> { throw new io.github.michael04.registration.common.AiUnavailableException(); };
	}

}
