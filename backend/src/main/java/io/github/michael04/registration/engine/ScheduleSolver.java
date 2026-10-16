package io.github.michael04.registration.engine;

/**
 * The scheduling engine's entry point.
 *
 * <p>Contract for implementations:
 * <ul>
 * <li>Every returned schedule has no time conflicts, includes every required course exactly
 * once, stays within the credit range, and doesn't overlap any blocked time.</li>
 * <li>A lecture with linked sections is always taken with exactly one of them per component
 * type (see {@link SectionOption}).</li>
 * <li>When no schedule fits, the diagnostics say why and, where possible, which required
 * course to make optional to get results.</li>
 * <li>Deterministic: the same problem, in any input order, gives an equal result. No
 * randomness and no wall-clock limits.</li>
 * <li>Pure: no I/O, no shared mutable state. Infeasibility is a normal result with
 * diagnostics, not an exception.</li>
 * </ul>
 */
public interface ScheduleSolver {

	ScheduleResult solve(ScheduleProblem problem);

}
