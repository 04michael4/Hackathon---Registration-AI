package io.github.michael04.registration.course.persistence;

/** Stored catalog metadata. Requirement evaluation and new public response fields are outside the MVP contract. */
public record CatalogRequirement(String type, String expressionJson, String assessmentStatus) {}
