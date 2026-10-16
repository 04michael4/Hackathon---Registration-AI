package io.github.michael04.registration.ai;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Proves that the model's raw JSON contains every property of the {@link ClaudeExtraction} contract, with
 * the right JSON type, before the reply is accepted.
 *
 * <p>Why this exists: the SDK deserializes the reply into {@link ClaudeExtraction} with Jackson, which turns
 * a missing property into {@code null}, and {@link ClaudeExtraction#toRawPreferences()} turns that into an
 * empty list. So {@code {}} used to look like a complete answer ("no courses, no constraints"). Presence can
 * only be checked on the raw text, which is why this reads the JSON itself.
 *
 * <p>Rules, derived from the record so the contract has one source of truth:
 * <ul>
 * <li>every record component must be present as a JSON property, including inside {@code blockedTimes};</li>
 * <li>{@code List} components must be arrays (an empty array is a legitimate answer; {@code null} is not);</li>
 * <li>{@code Optional} components must be {@code null} or an integer/string (an explicit {@code null} counts as
 * present: "not mentioned" is a real answer);</li>
 * <li>plain {@code String} components must be strings, never {@code null}.</li>
 * </ul>
 * Extra properties are ignored. Values (valid days, ranges, times) are not judged here; that is
 * {@link PreferenceAssembler}'s job.
 *
 * <p>The returned problem names only schema properties, never any content from the model's reply, so it is
 * safe to log.
 */
final class ClaudeExtractionValidator {

	private enum Kind {

		TEXT, NULLABLE_TEXT, NULLABLE_INTEGER, LIST_OF_TEXT, LIST_OF_OBJECTS

	}

	/** One property of the contract. {@code nested} is set only for {@link Kind#LIST_OF_OBJECTS}. */
	private record Property(String name, Kind kind, List<Property> nested) {
	}

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private static final List<Property> CONTRACT = propertiesOf(ClaudeExtraction.class);

	private ClaudeExtractionValidator() {
	}

	/** The first problem found, or empty if the reply satisfies the contract. */
	static Optional<String> firstProblem(String json) {
		JsonNode root;
		try {
			root = MAPPER.readTree(json);
		}
		catch (RuntimeException ex) {
			return Optional.of("reply is not valid JSON");
		}
		if (root == null || !root.isObject()) {
			return Optional.of("reply is not a JSON object");
		}
		return check(root, CONTRACT, "");
	}

	/** The top-level property names of the contract, in record order. For tests. */
	static List<String> requiredProperties() {
		return CONTRACT.stream().map(Property::name).toList();
	}

	private static Optional<String> check(JsonNode object, List<Property> properties, String prefix) {
		for (Property property : properties) {
			String where = prefix + property.name();
			JsonNode value = object.get(property.name());
			if (value == null) {
				return Optional.of("missing property '" + where + "'");
			}
			Optional<String> problem = checkValue(property, value, where);
			if (problem.isPresent()) {
				return problem;
			}
		}
		return Optional.empty();
	}

	private static Optional<String> checkValue(Property property, JsonNode value, String where) {
		switch (property.kind()) {
			case TEXT -> {
				if (!value.isString()) {
					return Optional.of("property '" + where + "' must be a string");
				}
			}
			case NULLABLE_TEXT -> {
				if (!value.isNull() && !value.isString()) {
					return Optional.of("property '" + where + "' must be a string or null");
				}
			}
			case NULLABLE_INTEGER -> {
				if (!value.isNull() && !(value.isIntegralNumber() && value.canConvertToInt())) {
					return Optional.of("property '" + where + "' must be an integer or null");
				}
			}
			case LIST_OF_TEXT -> {
				if (!value.isArray()) {
					return Optional.of("property '" + where + "' must be an array");
				}
				for (int i = 0; i < value.size(); i++) {
					if (!value.get(i).isString()) {
						return Optional.of("property '" + where + "[" + i + "]' must be a string");
					}
				}
			}
			case LIST_OF_OBJECTS -> {
				if (!value.isArray()) {
					return Optional.of("property '" + where + "' must be an array");
				}
				for (int i = 0; i < value.size(); i++) {
					JsonNode element = value.get(i);
					if (!element.isObject()) {
						return Optional.of("property '" + where + "[" + i + "]' must be an object");
					}
					Optional<String> problem = check(element, property.nested(), where + "[" + i + "].");
					if (problem.isPresent()) {
						return problem;
					}
				}
			}
		}
		return Optional.empty();
	}

	private static List<Property> propertiesOf(Class<?> type) {
		List<Property> properties = new ArrayList<>();
		for (RecordComponent component : type.getRecordComponents()) {
			properties.add(classify(component.getName(), component.getGenericType()));
		}
		return List.copyOf(properties);
	}

	/** Fails fast (at class load, and in a test) if the record gains a type this validator doesn't understand. */
	private static Property classify(String name, Type type) {
		if (type == String.class) {
			return new Property(name, Kind.TEXT, List.of());
		}
		if (type instanceof ParameterizedType parameterized && parameterized.getActualTypeArguments().length == 1) {
			Type raw = parameterized.getRawType();
			Type argument = parameterized.getActualTypeArguments()[0];
			if (raw == Optional.class && argument == Integer.class) {
				return new Property(name, Kind.NULLABLE_INTEGER, List.of());
			}
			if (raw == Optional.class && argument == String.class) {
				return new Property(name, Kind.NULLABLE_TEXT, List.of());
			}
			if (raw == List.class && argument instanceof Class<?> element) {
				if (element == String.class || element.isEnum()) {
					return new Property(name, Kind.LIST_OF_TEXT, List.of());
				}
				if (element.isRecord()) {
					return new Property(name, Kind.LIST_OF_OBJECTS, propertiesOf(element));
				}
			}
		}
		throw new IllegalStateException("ClaudeExtractionValidator does not support the type of '" + name + "': " + type);
	}

}
