package io.github.michael04.registration.common.preferences;

/** Soft-preference weight scale agreed in docs/api-contract.md. */
public final class Weights {

	public static final int MIN = 0;
	public static final int MAX = 5;
	public static final int DEFAULT = 3;

	private Weights() {
	}

	public static int clamp(int weight) {
		return Math.max(MIN, Math.min(MAX, weight));
	}

}
