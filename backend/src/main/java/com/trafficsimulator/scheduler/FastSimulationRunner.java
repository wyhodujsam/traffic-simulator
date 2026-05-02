package com.trafficsimulator.scheduler;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.trafficsimulator.engine.SimulationEngine;
import com.trafficsimulator.engine.SimulationStatus;
import com.trafficsimulator.engine.run.IFastSimulationRunner;

import lombok.extern.slf4j.Slf4j;

/**
 * Phase 25 D-13: tight worker-thread loop that drives the simulation pipeline as fast as the JVM
 * permits for {@code RUN_FOR_TICKS_FAST}.
 *
 * <p>While running, {@link SimulationEngine#isFastMode()} is {@code true}, so {@link TickEmitter}
 * early-returns from its {@code @Scheduled} entrypoint (RESEARCH.md §Pitfall #5 / threat
 * T-25-RACE). The worker has exclusive access to the tick pipeline; on completion, fast mode is
 * cleared and the regular 20 Hz ticker resumes.
 *
 * <p>The same {@link TickEmitter#runOneTick()} method is reused — this guarantees byte-identity
 * between {@code RUN_FOR_TICKS} (wall-clock) and {@code RUN_FOR_TICKS_FAST} (worker) runs of the
 * same seed (DET-07 — verified end-to-end in Plan 07 integration tests).
 *
 * <p>Bean is only constructable when {@link TickEmitter} is also active (same {@code
 * simulation.tick-emitter.enabled} property gate). When TickEmitter is disabled (e.g. in BDD
 * tests), this bean is absent and {@code CommandDispatcher} no-ops on RUN_FOR_TICKS_FAST.
 */
@Component
@Slf4j
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "simulation.tick-emitter.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class FastSimulationRunner implements IFastSimulationRunner {

    private final SimulationEngine engine;
    private final TickEmitter tickEmitter;

    @Autowired
    public FastSimulationRunner(SimulationEngine engine, @Nullable TickEmitter tickEmitter) {
        this.engine = engine;
        this.tickEmitter = tickEmitter;
    }

    /**
     * Runs the simulation pipeline for {@code ticks} ticks in a worker thread. Sets {@code
     * fastMode=true} for the duration so {@link TickEmitter#emitTick()} early-returns; clears the
     * flag and any auto-stop on exit (success or exception).
     *
     * @param ticks number of ticks to advance (validated by CommandHandler to 1..1_000_000)
     */
    @Override
    @Async
    public void runFor(long ticks) {
        if (tickEmitter == null) {
            log.warn("[FastSimulationRunner] No TickEmitter — fast run skipped");
            return;
        }
        engine.setFastMode(true);
        // DET-07: scheduleAutoStop is called synchronously by CommandDispatcher#handleRunForTicksFast
        // BEFORE this @Async runs, so the autoStopTick is computed against the same tickCounter
        // that wall-clock RUN_FOR_TICKS sees. Re-scheduling here would shift the target by however
        // many @Scheduled ticks fired between dispatch and the worker actually starting.
        log.info("[FastSimulationRunner] Starting fast run for {} ticks", ticks);
        try {
            // Tight loop — invoke the same per-tick pipeline TickEmitter uses. Loop terminates
            // when the engine is stopped (e.g. by external Stop, or by the auto-stop check inside
            // runOneTick) or when the auto-stop tick is reached.
            while (engine.getStatus() == SimulationStatus.RUNNING && !engine.isAutoStopReached()) {
                tickEmitter.runOneTick();
            }
        } finally {
            engine.setFastMode(false);
            engine.clearAutoStop();
            log.info(
                    "[FastSimulationRunner] Fast run complete at tick {}",
                    engine.getTickCounter().get());
        }
    }
}
