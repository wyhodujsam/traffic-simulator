package com.trafficsimulator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.trafficsimulator.engine.SimulationStatus;
import com.trafficsimulator.engine.command.SimulationCommand;

/**
 * DET-07: {@code RUN_FOR_TICKS_FAST} produces the same NDJSON content as wall-clock
 * {@code RUN_FOR_TICKS} for the same seed. Both modes drive the same {@code TickEmitter#runOneTick}
 * pipeline (FastSimulationRunner just bypasses the @Scheduled cadence) so byte-identity is the
 * contract.
 *
 * <p>Tick budget: 50 ticks chosen to keep wall-clock leg under ~3s while still exercising
 * lane-change + perturbation-eligible windows. Larger budgets blow the CI timeout.
 *
 * <p>File naming: {@code *Test.java}.
 */
class FastModeParityTest extends Phase25IntegrationBase {

    @Test
    void sameSeedFastVsSlowMatch() throws Exception {
        Path fast = runWithMode(42L, 50L, true);
        Path slow = runWithMode(42L, 50L, false);

        String fastBody = readBodyAfterHeader(fast);
        String slowBody = readBodyAfterHeader(slow);

        assertThat(fastBody)
                .as("DET-07: FAST and wall-clock modes must produce identical NDJSON tick stream")
                .isEqualTo(slowBody);
    }

    private Path runWithMode(long seed, long ticks, boolean fast) throws Exception {
        loadScenario("ring-road");
        // Atomic Start + run-for-ticks pair under writeLock so the @Scheduled cadence cannot fire
        // a free tick between the two commands (DET-07 — the wall-clock and FAST runs must see
        // identical (tickCounter, autoStopTick) starting state).
        engine.writeLock().lock();
        try {
            dispatcher.dispatch(new SimulationCommand.Start(seed));
            if (fast) {
                dispatcher.dispatch(new SimulationCommand.RunForTicksFast(ticks));
            } else {
                dispatcher.dispatch(new SimulationCommand.RunForTicks(ticks));
            }
        } finally {
            engine.writeLock().unlock();
        }
        if (fast) {
            waitForFastDone(30_000L);
        } else {
            // Wall-clock mode: 50 ticks @ 50ms = 2.5s minimum
            waitForStopped(15_000L);
        }
        // Sanity: must have actually stopped (auto-stop tripped)
        assertThat(engine.getStatus()).isEqualTo(SimulationStatus.STOPPED);

        Path replayPath = replayLogger.getPath();
        replayLogger.close();
        // Move OUT OF REPLAY_DIR — the next loadScenario() wipes that directory.
        Path stash = stashDir().resolve((fast ? "fast-" : "slow-") + UUID.randomUUID() + ".ndjson");
        Files.move(replayPath, stash);
        return stash;
    }

    private static Path STASH;

    private static synchronized Path stashDir() throws IOException {
        if (STASH == null) {
            STASH = Files.createTempDirectory("phase25-fastparity-stash-");
        }
        return STASH;
    }

    private static String readBodyAfterHeader(Path p) throws IOException {
        String all = Files.readString(p);
        int firstNewline = all.indexOf('\n');
        return firstNewline < 0 ? "" : all.substring(firstNewline + 1);
    }
}
