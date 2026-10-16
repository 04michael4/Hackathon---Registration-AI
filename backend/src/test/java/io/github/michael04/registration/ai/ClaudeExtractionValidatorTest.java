package io.github.michael04.registration.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Presence and type checks on the model's raw JSON, with no SDK or HTTP involved. A present {@code null}
 * for a nullable field is a real answer; an absent property is not.
 */
class ClaudeExtractionValidatorTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String VALID = """
			{"requiredCourses":["CSE 17","Calc 1"],"optionalCourses":[],"optionalCoursesWeight":null,
			 "minCredits":12,"maxCredits":null,
			 "blockedTimes":[{"days":["TUESDAY","THURSDAY"],"start":"15:00","end":"18:00"}],
			 "earliestStart":"10:00","earliestStartWeight":4,"latestEnd":null,"latestEndWeight":null,
			 "daysOff":["FRIDAY"],"daysOffWeight":2,"minimizeGapsWeight":null,"assumptions":[]}
			""";

	private static ObjectNode valid() {
		return (ObjectNode) JSON.readTree(VALID);
	}

	private static Optional<String> problem(ObjectNode node) {
		return ClaudeExtractionValidator.firstProblem(node.toString());
	}

	@Test
	void theRequiredPropertiesAreExactlyTheContract() {
		assertThat(ClaudeExtractionValidator.requiredProperties()).containsExactly("requiredCourses", "optionalCourses",
				"optionalCoursesWeight", "minCredits", "maxCredits", "blockedTimes", "earliestStart", "earliestStartWeight",
				"latestEnd", "latestEndWeight", "daysOff", "daysOffWeight", "minimizeGapsWeight", "assumptions");
	}

	@Test
	void aCompleteReplyHasNoProblem() {
		assertThat(ClaudeExtractionValidator.firstProblem(VALID)).isEmpty();
	}

	@Test
	void emptyObjectIsRejectedAtTheFirstRequiredProperty() {
		assertThat(ClaudeExtractionValidator.firstProblem("{}")).contains("missing property 'requiredCourses'");
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@ValueSource(strings = { "", "not json", "[]", "null", "\"text\"", "42", "{\"requiredCourses\":" })
	void aReplyThatIsNotAJsonObjectIsRejected(String reply) {
		assertThat(ClaudeExtractionValidator.firstProblem(reply)).isPresent();
	}

	@Test
	void everyMissingTopLevelPropertyIsNamed() {
		for (String name : ClaudeExtractionValidator.requiredProperties()) {
			ObjectNode node = valid();
			node.remove(name);

			assertThat(problem(node)).as(name).contains("missing property '" + name + "'");
		}
	}

	@Test
	void everyMissingBlockedTimeFieldIsNamedWithItsIndex() {
		for (String name : List.of("days", "start", "end")) {
			ObjectNode node = valid();
			((ObjectNode) node.get("blockedTimes").get(0)).remove(name);

			assertThat(problem(node)).as(name).contains("missing property 'blockedTimes[0]." + name + "'");
		}
	}

	@Test
	void aLaterIncompleteBlockedTimeIsFoundToo() {
		ObjectNode node = (ObjectNode) JSON.readTree("""
				{"requiredCourses":[],"optionalCourses":[],"optionalCoursesWeight":null,"minCredits":null,"maxCredits":null,
				 "blockedTimes":[{"days":["MONDAY"],"start":"08:00","end":"09:00"},{"days":["TUESDAY"],"start":"08:00"}],
				 "earliestStart":null,"earliestStartWeight":null,"latestEnd":null,"latestEndWeight":null,
				 "daysOff":[],"daysOffWeight":null,"minimizeGapsWeight":null,"assumptions":[]}
				""");

		assertThat(problem(node)).contains("missing property 'blockedTimes[1].end'");
	}

	@Test
	void emptyListsAndExplicitNullsAreRealAnswers() {
		assertThat(ClaudeExtractionValidator.firstProblem("""
				{"requiredCourses":[],"optionalCourses":[],"optionalCoursesWeight":null,"minCredits":null,"maxCredits":null,
				 "blockedTimes":[],"earliestStart":null,"earliestStartWeight":null,"latestEnd":null,"latestEndWeight":null,
				 "daysOff":[],"daysOffWeight":null,"minimizeGapsWeight":null,"assumptions":[]}
				""")).isEmpty();
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "requiredCourses", "optionalCourses", "blockedTimes", "daysOff", "assumptions" })
	void aListMayBeEmptyButNeverNull(String name) {
		ObjectNode node = valid();
		node.putArray(name);
		assertThat(problem(node)).as("empty array").isEmpty();

		node.putNull(name);
		assertThat(problem(node)).as("null").contains("property '" + name + "' must be an array");
	}

	@Test
	void nullableFieldsAcceptNullAndTheRightTypeOnly() {
		ObjectNode node = valid();
		node.put("minCredits", 12.5);
		assertThat(problem(node)).contains("property 'minCredits' must be an integer or null");
		node.put("minCredits", "12");
		assertThat(problem(node)).contains("property 'minCredits' must be an integer or null");
		node.put("minCredits", 3_000_000_000L);
		assertThat(problem(node)).contains("property 'minCredits' must be an integer or null");
		node = valid();
		node.put("earliestStart", 10);
		assertThat(problem(node)).contains("property 'earliestStart' must be a string or null");
		node.putNull("earliestStart");
		assertThat(problem(node)).isEmpty();
	}

	@Test
	void aBlockedTimeStringCannotBeNull() {
		ObjectNode node = valid();
		((ObjectNode) node.get("blockedTimes").get(0)).putNull("end");

		assertThat(problem(node)).contains("property 'blockedTimes[0].end' must be a string");
	}

	@Test
	void listElementsMustHaveTheRightShape() {
		ObjectNode node = valid();
		node.putArray("requiredCourses").add(17);
		assertThat(problem(node)).contains("property 'requiredCourses[0]' must be a string");

		node = valid();
		node.putArray("blockedTimes").add("15:00-18:00");
		assertThat(problem(node)).contains("property 'blockedTimes[0]' must be an object");

		node = valid();
		((ObjectNode) node.get("blockedTimes").get(0)).put("days", "TUESDAY");
		assertThat(problem(node)).contains("property 'blockedTimes[0].days' must be an array");
	}

	@Test
	void extraPropertiesAreIgnored() {
		ObjectNode node = valid();
		node.put("commentary", "extra");

		assertThat(problem(node)).isEmpty();
	}

	@Test
	void theProblemTextNeverContainsModelContent() {
		String secret = "SENSITIVE-STUDENT-TEXT-987";
		ObjectNode node = valid();
		node.put("requiredCourses", secret);
		node.put("minCredits", secret);

		assertThat(problem(node).orElseThrow()).doesNotContain(secret);
		assertThat(ClaudeExtractionValidator.firstProblem("{\"requiredCourses\":\"" + secret + "\" nope")).hasValue("reply is not valid JSON");
	}

}
