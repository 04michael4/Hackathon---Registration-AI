package io.github.michael04.registration.engine;

import java.util.List;
import java.util.Objects;

/**
 * Something wrong or missing in a hand-built (manual mode) selection.
 *
 * @param type what kind of problem it is
 * @param crns the sections involved, in a fixed order; empty for course- or credit-level issues
 * @param message a sentence the UI can show as is
 */
public record SelectionIssue(Type type, List<String> crns, String message) {

	public enum Type {
		/** A CRN that isn't one of the problem's sections. */
		UNKNOWN_SECTION(Severity.CONFLICT),
		/** Two placed sections meet at the same time. */
		TIME_CONFLICT(Severity.CONFLICT),
		/** A placed section overlaps one of the student's blocked times. */
		BLOCKED_TIME(Severity.CONFLICT),
		/** Two lectures (or standalone sections) of the same course are placed. */
		DUPLICATE_COURSE(Severity.CONFLICT),
		/** A lecture has two placed linked sections of the same component, e.g. two labs. */
		DUPLICATE_COMPONENT(Severity.CONFLICT),
		/** Placed credits are above {@code maxCredits}. */
		OVER_MAX_CREDITS(Severity.CONFLICT),
		/** A lab or recitation is placed without its lecture. */
		LINKED_WITHOUT_LECTURE(Severity.INCOMPLETE),
		/** A placed lecture still needs a section of one of its linked components. */
		MISSING_LINKED_SECTION(Severity.INCOMPLETE),
		/** A required course has no section placed yet. */
		REQUIRED_COURSE_MISSING(Severity.INCOMPLETE),
		/** Placed credits are below {@code minCredits}. */
		UNDER_MIN_CREDITS(Severity.INCOMPLETE);

		private final Severity severity;

		Type(Severity severity) {
			this.severity = severity;
		}

		public Severity severity() {
			return severity;
		}
	}

	/**
	 * {@code CONFLICT}: something placed breaks a hard rule (show a red warning). {@code INCOMPLETE}:
	 * something still has to be added (show a prompt).
	 */
	public enum Severity {
		CONFLICT, INCOMPLETE
	}

	public SelectionIssue {
		Objects.requireNonNull(type, "type");
		crns = List.copyOf(Objects.requireNonNull(crns, "crns"));
		Objects.requireNonNull(message, "message");
	}

	public Severity severity() {
		return type.severity();
	}

}
