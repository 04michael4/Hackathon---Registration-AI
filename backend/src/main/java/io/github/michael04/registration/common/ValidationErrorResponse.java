package io.github.michael04.registration.common;

/** A public validation message; never includes a rejected value. */
public record ValidationErrorResponse(String field, String message) {
}
