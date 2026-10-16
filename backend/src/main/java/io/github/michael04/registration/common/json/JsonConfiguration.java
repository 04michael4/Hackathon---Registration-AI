package io.github.michael04.registration.common.json;

import java.time.LocalTime;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * App-wide JSON formats from docs/api-contract.md § Conventions.
 * Times are "HH:mm" (e.g. "15:00"); Jackson's default would write "15:00:00".
 *
 * <p>Shared by all modules (owned by Backend API / Marpu). Created on the AI branch.
 */
@Configuration
public class JsonConfiguration {

	public static final String TIME_PATTERN = "HH:mm";

	@Bean
	JsonMapperBuilderCustomizer contractTimeFormat() {
		return builder -> builder.withConfigOverride(LocalTime.class,
				override -> override.setFormat(JsonFormat.Value.forPattern(TIME_PATTERN)));
	}

}
