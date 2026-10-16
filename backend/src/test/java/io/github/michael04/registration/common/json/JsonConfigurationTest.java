package io.github.michael04.registration.common.json;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;

import io.github.michael04.registration.common.preferences.GapsPreference;
import io.github.michael04.registration.common.preferences.HardConstraints;
import io.github.michael04.registration.common.preferences.SchedulePreferences;
import io.github.michael04.registration.common.preferences.SoftPreferences;
import io.github.michael04.registration.common.preferences.TimeBlock;
import io.github.michael04.registration.common.preferences.Weighted;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code SchedulePreferences} must read and write exactly the JSON in docs/api-contract.md
 * (endpoint 5), including "HH:mm" times, in both directions.
 */
class JsonConfigurationTest {

	private static final String CONTRACT_JSON = """
			{
			  "hardConstraints": {
			    "requiredCourseIds": ["CSE-017", "MATH-021"],
			    "minCredits": 12,
			    "maxCredits": 18,
			    "blockedTimes": [ { "days": ["TUESDAY", "THURSDAY"], "start": "15:00", "end": "18:00" } ]
			  },
			  "softPreferences": {
			    "optionalCourseIds": { "value": ["ECO-001"], "weight": 2 },
			    "earliestStart":     { "value": "10:00",     "weight": 3 },
			    "latestEnd":         { "value": "17:00",     "weight": 1 },
			    "daysOff":           { "value": ["FRIDAY"],  "weight": 4 },
			    "minimizeGaps":      { "weight": 2 }
			  }
			}
			""";

	private final JsonMapper mapper = contractMapper();

	@Test
	void readsTheContractExample() {
		SchedulePreferences p = mapper.readValue(CONTRACT_JSON, SchedulePreferences.class);

		HardConstraints hard = p.hardConstraints();
		assertThat(hard.requiredCourseIds()).containsExactly("CSE-017", "MATH-021");
		assertThat(hard.minCredits()).isEqualTo(12);
		assertThat(hard.maxCredits()).isEqualTo(18);
		assertThat(hard.blockedTimes()).containsExactly(
				new TimeBlock(List.of(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY), LocalTime.of(15, 0), LocalTime.of(18, 0)));

		SoftPreferences soft = p.softPreferences();
		assertThat(soft.optionalCourseIds()).isEqualTo(new Weighted<>(List.of("ECO-001"), 2));
		assertThat(soft.earliestStart()).isEqualTo(new Weighted<>(LocalTime.of(10, 0), 3));
		assertThat(soft.latestEnd()).isEqualTo(new Weighted<>(LocalTime.of(17, 0), 1));
		assertThat(soft.daysOff()).isEqualTo(new Weighted<>(List.of(DayOfWeek.FRIDAY), 4));
		assertThat(soft.minimizeGaps()).isEqualTo(new GapsPreference(2));
	}

	@Test
	void writesTimesAsHoursAndMinutes() {
		SchedulePreferences p = mapper.readValue(CONTRACT_JSON, SchedulePreferences.class);

		String json = mapper.writeValueAsString(p);

		assertThat(json).contains("\"start\":\"15:00\"", "\"end\":\"18:00\"", "\"value\":\"10:00\"")
			.doesNotContain("15:00:00");
	}

	@Test
	void roundTripIsLossless() {
		SchedulePreferences original = mapper.readValue(CONTRACT_JSON, SchedulePreferences.class);

		SchedulePreferences copy = mapper.readValue(mapper.writeValueAsString(original), SchedulePreferences.class);

		assertThat(copy).isEqualTo(original);
	}

	@Test
	void unsetSoftPreferencesAreOmitted() {
		SchedulePreferences p = new SchedulePreferences(
				new HardConstraints(List.of("CSE-017"), 12, 18, List.of()), SoftPreferences.none());

		assertThat(mapper.writeValueAsString(p)).contains("\"softPreferences\":{}");
	}

	private static JsonMapper contractMapper() {
		JsonMapper.Builder builder = JsonMapper.builder();
		JsonMapperBuilderCustomizer customizer = new JsonConfiguration().contractTimeFormat();
		customizer.customize(builder);
		return builder.build();
	}

}
