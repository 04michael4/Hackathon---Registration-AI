package io.github.michael04.registration.demo;

/**
 * One thing a student should know about an offering. {@code outcome} is how the demo could judge it with the profile it was given:
 * SATISFIED, OVERRIDE_REQUIRED (a known restriction the profile does not meet; an approved override may exist but is never
 * guaranteed), UNRESOLVED (the profile or the evidence is missing) or UNVERIFIED (known only from research or user-supplied
 * evidence). {@code source} is who says so: BANNER, CATALOG, USER_SUPPLIED, TEAM_POLICY or RESEARCH. Nothing here is
 * Registrar-certified.
 */
public record DemoWarning(String code, String severity, String outcome, String source, String courseId, String text) {
}
