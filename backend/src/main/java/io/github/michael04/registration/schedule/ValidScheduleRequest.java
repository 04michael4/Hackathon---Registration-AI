package io.github.michael04.registration.schedule;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

@Target(TYPE)
@Retention(RUNTIME)
@Constraint(validatedBy = ScheduleRequestValidator.class)
public @interface ValidScheduleRequest {
	String message() default "Invalid scheduling preferences";
	Class<?>[] groups() default {};
	Class<? extends Payload>[] payload() default {};
}
