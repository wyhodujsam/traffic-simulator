package com.trafficsimulator.engine.run;

/**
 * Phase 25 D-13: engine-layer interface for the worker-thread RUN_FOR_TICKS_FAST driver. Lets
 * {@code CommandDispatcher} dispatch fast runs without importing the scheduler package
 * (architecture rule forbids {@code engine → scheduler}). Concrete implementation lives in {@code
 * com.trafficsimulator.scheduler.FastSimulationRunner}.
 */
public interface IFastSimulationRunner {

    /**
     * Runs the simulation pipeline for {@code ticks} ticks asynchronously. Caller MUST set the
     * engine status to RUNNING before invoking.
     *
     * @param ticks number of ticks to advance (validated upstream to {@code 1..1_000_000})
     */
    void runFor(long ticks);
}
