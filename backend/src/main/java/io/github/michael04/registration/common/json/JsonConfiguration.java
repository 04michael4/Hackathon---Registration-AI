package io.github.michael04.registration.common.json;

import java.time.LocalTime;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.annotation.JsonFormat;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

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
		return builder -> builder
			.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
			.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
			.withCoercionConfig(LogicalType.Textual, config -> config
				.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
				.setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
				.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
			.withConfigOverride(LocalTime.class,
				override -> override.setFormat(JsonFormat.Value.forPattern(TIME_PATTERN).withLenient(false)));
	}

}
