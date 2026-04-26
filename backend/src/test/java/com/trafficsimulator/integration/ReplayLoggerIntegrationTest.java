package com.trafficsimulator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * REPLAY-01 + REPLAY-03. Verifies file is created when RUN_FOR_TICKS is invoked
 * (REPLAY-01) and is NOT created when {@code simulator.replay.enabled=false} (default) and only
 * Start was issued (REPLAY-03).
 *
 * <p>Note: this test cannot extend Phase25IntegrationBase because the base forces
 * {@code simulator.replay.enabled=true} for the byte-identity tests; REPLAY-03 specifically
 * requires the default-disabled property. Each test method clears the temp directory in @BeforeEach
 * for isolation.
 *
 * <p>File naming: {@code *Test.java} (NOT {@code *IT.java}). The unit-level peer is named
 * {@code ReplayLoggerTest} (Plan 05); the {@code Integration} suffix here avoids a class clash.
 */
@SpringBootTest
class ReplayLoggerIntegrationTest {

    static Path tmpDir;

    @DynamicPropertySource
    static void configureReplay(DynamicPropertyRegistry r) throws IOException {
        tmpDir = Files.createTempDirectory("phase25-replay-it-");
        r.add("simulator.replay.directory", tmpDir::toString);
        // simulator.replay.enabled is left at its default (false) so REPLAY-03 can exercise the
        // "no file unless RUN_FOR_TICKS forces it" contract.
    }

    @Autowired SimulationEngine engine;
    @Autowired CommandDispatcher dispatcher;
    @Autowired MapLoader mapLoader;
    @Autowired ReplayLogger replayLogger;

    @BeforeEach
    void clearTmp() throws IOException, InterruptedException {
        // Wait for any prior fast-mode run from a previous test to finish before deleting files
        long deadline = System.currentTimeMillis() + 5_000L;
        while (engine.isFastMode() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        replayLogger.close();
        try (Stream<Path> files = Files.list(tmpDir)) {
            files.forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Best-effort cleanup
                }
            });
        }
        // Ensure engine is stopped between tests so each one starts clean
        dispatcher.dispatch(new SimulationCommand.Stop());
    }

    @AfterEach
    void closeLogger() throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (engine.isFastMode() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        replayLogger.close();
        dispatcher.dispatch(new SimulationCommand.Stop());
    }

    @Test
    void writesFileWhenRunForTicksInvoked_REPLAY01() throws Exception {
        dispatcher.dispatch(new SimulationCommand.LoadMap("ring-road"));
        dispatcher.dispatch(new SimulationCommand.Start(99L));
        dispatcher.dispatch(new SimulationCommand.RunForTicksFast(10L));

        // Wait for the @Async fast worker to finish
        long deadline = System.currentTimeMillis() + 15_000L;
        while (engine.isFastMode() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        try (Stream<Path> files = Files.list(tmpDir)) {
            long count =
                    files.filter(
                                    p ->
                                            p.getFileName()
                                                    .toString()
                                                    .matches("99-\\d{8}T\\d{6}\\.ndjson"))
                            .count();
            assertThat(count)
                    .as("REPLAY-01: file with name {seed}-{ISO8601}.ndjson exists in target dir")
                    .isGreaterThanOrEqualTo(1L);
        }
    }

    @Test
    void defaultDisabledNoFileWritten_REPLAY03() throws Exception {
        // Property simulator.replay.enabled is default-false; only Start is dispatched (no
        // RUN_FOR_TICKS), so the dispatcher's force-start path does not fire and no file should
        // appear.
        dispatcher.dispatch(new SimulationCommand.LoadMap("ring-road"));
        dispatcher.dispatch(new SimulationCommand.Start(123L));
        // Brief settle window — the @Scheduled tick runs but with disabled replay it must NOT
        // touch the disk.
        Thread.sleep(500L);

        try (Stream<Path> files = Files.list(tmpDir)) {
            long count =
                    files.filter(p -> p.getFileName().toString().endsWith(".ndjson")).count();
            assertThat(count)
                    .as("REPLAY-03: default-disabled means NO ndjson file is created")
                    .isZero();
        }

        // Tidy: stop so the next test starts clean
        dispatcher.dispatch(new SimulationCommand.Stop());
        long deadline = System.currentTimeMillis() + 2_000L;
        while (engine.getStatus() != SimulationStatus.STOPPED
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }
}
