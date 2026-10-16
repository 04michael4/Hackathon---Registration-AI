package io.github.michael04.registration.schedule;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Cross-field rules and constraints on generic preference values. Numeric ranges use Bean Validation. */
public class ScheduleRequestValidator implements ConstraintValidator<ValidScheduleRequest, GenerateScheduleRequest> {
	@Override
	public boolean isValid(GenerateScheduleRequest request, ConstraintValidatorContext context) {
		if (request == null || request.preferences() == null) {
			return true;
		}
		boolean valid = true;
		var hard = request.preferences().hardConstraints();
		if (hard != null && hard.minCredits() > hard.maxCredits()) {
			violation(context, "minCredits must not exceed maxCredits", "hardConstraints", "minCredits");
			valid = false;
		}
		var soft = request.preferences().softPreferences();
		if (soft == null) {
			return valid;
		}
		if (soft.optionalCourseIds() != null && soft.optionalCourseIds().value() != null) {
			var ids = soft.optionalCourseIds().value();
			// Check the original list before de-duplication or required/optional overlap removal.
			if (ids.size() > 10) {
				violation(context, "must contain at most 10 optional courses", "softPreferences", "optionalCourseIds", "value");
				valid = false;
			}
			if (ids.stream().anyMatch(id -> id == null || id.isBlank())) {
				violation(context, "course IDs must not be blank", "softPreferences", "optionalCourseIds", "value");
				valid = false;
			}
		}
		if (soft.daysOff() != null && soft.daysOff().value() != null
				&& soft.daysOff().value().stream().anyMatch(java.util.Objects::isNull)) {
			violation(context, "days must not contain null", "softPreferences", "daysOff", "value");
			valid = false;
		}
		return valid;
	}

	private void violation(ConstraintValidatorContext context, String message, String... path) {
		context.disableDefaultConstraintViolation();
		var node = context.buildConstraintViolationWithTemplate(message).addPropertyNode("preferences");
		for (String part : path) {
			node = node.addPropertyNode(part);
		}
		node.addConstraintViolation();
	}
}
