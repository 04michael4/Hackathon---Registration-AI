package io.github.michael04.registration.ai;

/** Receives only reloaded catalog facts and engine-checked results, never arbitrary client descriptions. */
public interface ScheduleExplainer {
	ExplainScheduleResponse explain(String factsJson);
}
