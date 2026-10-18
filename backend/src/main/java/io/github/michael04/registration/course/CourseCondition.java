package io.github.michael04.registration.course;

/**
 * Something the application has NOT verified about a student's ability to take a course (a prerequisite, a class-standing or major
 * restriction, a credit exclusion, a meeting without a time, ...). It never says the student is eligible, and it is not a statement
 * by the Registrar.
 *
 * @param code e.g. {@code PREREQUISITE}, {@code CLASS_STANDING}, {@code CREDIT_EXCLUSION}
 * @param message wording for the student
 * @param evidenceLevel who says so: {@code BANNER}, {@code CATALOG}, {@code USER_CLARIFICATION} or {@code TEAM_POLICY}
 * @param outcome always {@code UNVERIFIED} today: the application does not check the student against the condition
 */
public record CourseCondition(String code, String message, String evidenceLevel, String outcome) {
}
