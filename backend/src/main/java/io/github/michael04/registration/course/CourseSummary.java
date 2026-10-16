package io.github.michael04.registration.course;

/** Minimal course info, e.g. ("CSE-017", "Programming and Data Structures", 3). */
public record CourseSummary(String courseId, String title, int credits) {
}
