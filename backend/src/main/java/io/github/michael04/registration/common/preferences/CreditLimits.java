package io.github.michael04.registration.common.preferences;

/**
 * Credit-load rules from docs/api-contract.md § Credit limits
 * (Lehigh overload policy plus the team's 21-credit hard cap).
 */
public final class CreditLimits {

	/** No schedule may exceed this many credits (team rule). */
	public static final int HARD_CAP = 21;

	/** Default range when the student doesn't specify one. */
	public static final int DEFAULT_MIN = 12;
	public static final int DEFAULT_MAX = 18;

	/** Above this is an overload for most students (17 for first-semester students). */
	public static final int NORMAL_LOAD_MAX = 18;
	public static final int FIRST_SEMESTER_NORMAL_LOAD_MAX = 17;

	private CreditLimits() {
	}

	public static boolean isOverload(int credits) {
		return credits > NORMAL_LOAD_MAX;
	}

}
