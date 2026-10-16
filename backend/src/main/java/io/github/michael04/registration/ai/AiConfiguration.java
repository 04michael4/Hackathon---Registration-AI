package io.github.michael04.registration.ai;

import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses the {@link PreferenceExtractor} from {@code app.ai.provider} (env var {@code AI_PROVIDER}):
 * {@code none} (default, endpoint returns 503) or {@code dummy} (fixed example, no API key needed).
 * A {@code claude} provider is added in the next step.
 */
@Configuration
public class AiConfiguration {

	@Bean
	PreferenceExtractor preferenceExtractor(@Value("${app.ai.provider:none}") String provider) {
		return switch (provider.trim().toLowerCase(Locale.ROOT)) {
			case "none", "" -> new UnavailablePreferenceExtractor();
			case "dummy" -> new DummyPreferenceExtractor();
			default -> throw new IllegalStateException(
					"Unknown app.ai.provider '" + provider + "' (expected 'none' or 'dummy')");
		};
	}

}
