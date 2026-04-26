package com.trafficsimulator.integration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.trafficsimulator.config.MapLoader;
import com.trafficsimulator.engine.CommandDispatcher;
import com.trafficsimulator.engine.SimulationEngine;
import com.trafficsimulator.engine.SimulationStatus;
import com.trafficsimulator.engine.command.SimulationCommand;
import com.trafficsimulator.replay.ReplayLogger;

/**
 * Phase 25 Plan 07 — shared base class for the 6 integration test suites.
 *
 * <p>Concentrates {@link SpringBootTest} context loading + replay-directory wiring in one place so
 * all 6 *Test.java suites share the same Spring context (Surefire reuses contexts when
 * configuration matches — see VALIDATION.md note about saving 5-7 minutes by amortising boot).
 *
 * <p>File naming: every concrete subclass uses {@code *Test.java} (NOT {@code *IT.java}) so
 * Surefire's default include pattern picks them up. The project does not configure
 * {@code maven-failsafe-plugin}; {@code *IT.java} would be silently excluded.
 *
 * <p>Replay directory: every subclass writes NDJSON into {@link #REPLAY_DIR}, a JVM-shared temp
 * directory created on class load. Test methods that need their own subdirectory can read
 * {@link #REPLAY_DIR} and create per-test paths.
 */
@SpringBootTest(properties = {"simulation.tick-emitter.enabled=true"})
public abstract class Phase25IntegrationBase {

    /** Shared replay directory across all Phase-25 IT runs in this JVM. */
    protected static Path REPLAY_DIR;

    static {
        try {
            REPLAY_DIR = Files.createTempDirectory("phase25-it-");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create replay temp dir", e);
        }
    }

    @DynamicPropertySource
    static void configureReplay(DynamicPropertyRegistry r) {
        r.add("simulator.replay.enabled", () -> "true");
        r.add("simulator.replay.directory", REPLAY_DIR::toString);
    }

    @Autowired protected SimulationEngine engine;
    @Autowired protected CommandDispatcher dispatcher;
    @Autowired protected ReplayLogger replayLogger;
    @Autowired protected MapLoader mapLoader;

    /**
     * Reset the simulation engine to a known clean state and load the named scenario via the
     * dispatcher's LoadMap path so {@code primeInitialVehicles} runs (this is what the production
     * STOMP path does, and it is what the determinism contract is asserted against).
     *
     * <p>Uses {@code dispatcher.dispatch} directly (not the queue) because the test owns the
     * lifecycle for these setup commands; a barrier waits for any in-flight fast worker to drain
     * before issuing a new Stop.
     */
    protected void loadScenario(String mapId) throws InterruptedException {
        // Force a clean slate even if a prior test left fast-mode running
        if (engine.isFastMode()) {
            long deadline = System.currentTimeMillis() + 5_000L;
            while (engine.isFastMode() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
        }
        dispatcher.dispatch(new SimulationCommand.Stop());
        dispatcher.dispatch(new SimulationCommand.LoadMap(mapId));
    }

    /**
     * Start the simulation with the supplied seed and advance {@code ticks} ticks via
     * RUN_FOR_TICKS_FAST, then block until the worker finishes.
     *
     * <p>Both commands are dispatched <em>synchronously</em> via the dispatcher (not enqueued)
     * within a single writeLock acquisition so the @Scheduled cadence cannot race between Start
     * and RunForTicksFast and shift the auto-stop tick (DET-07 byte-identity precondition).
     */
    protected void startAndRunFast(Long seed, long ticks) throws InterruptedException {
        engine.writeLock().lock();
        try {
            dispatcher.dispatch(new SimulationCommand.Start(seed));
            dispatcher.dispatch(new SimulationCommand.RunForTicksFast(ticks));
        } finally {
            engine.writeLock().unlock();
        }
        waitForFastDone(60_000L);
    }

    /** Poll until {@link SimulationEngine#isFastMode()} drops, with a hard timeout. */
    protected void waitForFastDone(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (engine.isFastMode() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    /** Wait until status returns to STOPPED (auto-stop signal). */
    protected void waitForStopped(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (engine.getStatus() != SimulationStatus.STOPPED
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }
}
