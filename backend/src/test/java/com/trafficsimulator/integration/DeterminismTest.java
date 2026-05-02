package com.trafficsimulator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * DET-01 (HEADLINE) + DET-02. Same seed produces byte-identical NDJSON; different seeds diverge.
 *
 * <p>Scope: byte-identity is asserted ONLY against the NDJSON replay log content (per RESEARCH.md
 * Open Questions Q4/Q6 RESOLVED — STOMP frames carry wall-clock timestamps and KPI sub-samples
 * and are explicitly out of scope). The first line (header) contains an ISO8601 timestamp that
 * differs across runs, so we compare only the tick lines (everything after the first newline).
 *
 * <p>File naming: {@code *Test.java} (NOT {@code *IT.java}) because the project does not configure
 * Failsafe; Surefire would silently exclude {@code *IT.java}.
 */
class DeterminismTest extends Phase25IntegrationBase {

    @Test
    void sameSeedSameLog() throws Exception {
        Path p1 = runOnce(42L, 200L);
        Path p2 = runOnce(42L, 200L);

        String body1 = readBodyAfterHeader(p1);
        String body2 = readBodyAfterHeader(p2);

        assertThat(body1)
                .as("DET-01 HEADLINE: same seed must yield byte-identical NDJSON tick stream")
                .isEqualTo(body2);
    }

    @Test
    void differentSeedDifferentLog() throws Exception {
        Path p1 = runOnce(42L, 200L);
        Path p2 = runOnce(43L, 200L);

        String body1 = readBodyAfterHeader(p1);
        String body2 = readBodyAfterHeader(p2);

        assertThat(body1)
                .as("DET-02: different seeds must produce different NDJSON tick streams")
                .isNotEqualTo(body2);
    }

    /**
     * Run the ring-road scenario with the supplied seed for {@code ticks} ticks via
     * RUN_FOR_TICKS_FAST, then move the resulting NDJSON file out of REPLAY_DIR (so the next
     * loadScenario's directory wipe doesn't delete it) and into the test-class temp dir.
     */
    private Path runOnce(long seed, long ticks) throws Exception {
        loadScenario("ring-road");
        startAndRunFast(seed, ticks);

        Path replayPath = replayLogger.getPath();
        assertThat(replayPath)
                .as("ReplayLogger must have written a file for seed=" + seed)
                .isNotNull();
        // Defensive close — in case the auto-stop path didn't quite finish flushing
        replayLogger.close();

        // Move OUT OF REPLAY_DIR — the next loadScenario() wipes that directory.
        Path stash = stashDir().resolve("det-" + seed + "-" + UUID.randomUUID() + ".ndjson");
        Files.move(replayPath, stash);
        return stash;
    }

    private static Path STASH;

    private static synchronized Path stashDir() throws IOException {
        if (STASH == null) {
            STASH = Files.createTempDirectory("phase25-determinism-stash-");
        }
        return STASH;
    }

    /** Read the file content excluding the first (header) line so timestamps don't break parity. */
    private static String readBodyAfterHeader(Path p) throws IOException {
        String all = Files.readString(p);
        int firstNewline = all.indexOf('\n');
        return firstNewline < 0 ? "" : all.substring(firstNewline + 1);
    }
}
