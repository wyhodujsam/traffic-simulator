package com.trafficsimulator.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import com.trafficsimulator.config.MapConfig;
import com.trafficsimulator.config.Osm2StreetsConfig;
import com.trafficsimulator.dto.BboxRequest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Phase 24 osm2streets-based OSM → {@link MapConfig} converter.
 *
 * <p>Invokes the pre-built Rust binary {@code osm2streets-cli} (Plan 24-01) as a subprocess,
 * piping OSM XML to stdin and reading StreetNetwork JSON back from stdout. The full
 * fetch-and-map wiring (Overpass fetch, JSON → MapConfig translation) lands in Plan 24-04; this
 * plan delivers only the process-mechanics spine: {@link #executeCli(byte[])} + timeout + exit-
 * code mapping + pipe-deadlock avoidance.
 *
 * <p><b>Spring wiring:</b> marked {@code @Lazy} per 23-SPIKE {@code ## A7} (see also Phase 23's
 * {@link GraphHopperOsmService}). A classpath or binary issue must not abort the Spring context
 * and take down the Phase 18 / Phase 23 endpoints that already work. {@link #isAvailable()}
 * returns {@code false} when the binary cannot be executed, letting the controller in Plan 24-05
 * respond with 503 rather than letting construction failure propagate.
 *
 * <p><b>Subprocess contract (24-RESEARCH.md §5, Pitfalls 3 + 6):</b>
 * <ul>
 *   <li>stdin = raw OSM XML bytes; stream closed via try-with-resources so the binary's
 *       {@code read_to_end} sees EOF and returns
 *   <li>stdout = StreetNetwork JSON bytes (UTF-8), drained on a dedicated {@link CompletableFuture}
 *   <li>stderr = log lines, drained on a SEPARATE {@link CompletableFuture} — never use
 *       {@code redirectErrorStream(true)} here because large stderr can deadlock the stdout reader
 *       when both come through the same pipe
 *   <li>exit 0 = success; any other exit code is wrapped in {@link Osm2StreetsCliException} with
 *       captured stderr
 *   <li>hard timeout from {@link Osm2StreetsConfig#getTimeoutSeconds()}; on expiry the process is
 *       {@code destroyForcibly()}'d and {@link Osm2StreetsCliTimeoutException} is thrown
 * </ul>
 */
@Service
@Lazy
@RequiredArgsConstructor
@Slf4j
public class Osm2StreetsService implements OsmConverter {

    // ---------------------------------------------------------------------
    // Exception taxonomy
    // ---------------------------------------------------------------------

    /** Thrown when the osm2streets-cli process exits with a non-zero code. */
    public static class Osm2StreetsCliException extends RuntimeException {
        public Osm2StreetsCliException(String message) {
            super(message);
        }
    }

    /** Thrown when the osm2streets-cli process does not complete within the configured timeout. */
    public static class Osm2StreetsCliTimeoutException extends RuntimeException {
        public Osm2StreetsCliTimeoutException(String message) {
            super(message);
        }
    }

    /** Soft bound for draining stdout/stderr after the process has already exited. */
    private static final long STREAM_READ_BUFFER_SECONDS = 5L;

    // ---------------------------------------------------------------------
    // Dependencies
    // ---------------------------------------------------------------------

    private final Osm2StreetsConfig config;

    // ---------------------------------------------------------------------
    // OsmConverter contract
    // ---------------------------------------------------------------------

    @Override
    public String converterName() {
        return "osm2streets";
    }

    @Override
    public boolean isAvailable() {
        try {
            return Files.isExecutable(Path.of(config.getBinaryPath()));
        } catch (Exception e) {
            log.debug("isAvailable() probe failed for {}: {}", config.getBinaryPath(), e.getMessage());
            return false;
        }
    }

    @Override
    public MapConfig fetchAndConvert(BboxRequest bbox) {
        // NOTE: Plan 24-04 replaces this body with Overpass fetch → executeCli → StreetNetworkMapper.
        throw new UnsupportedOperationException(
                "Osm2StreetsService.fetchAndConvert — StreetNetworkMapper + Overpass fetch land in Plan 24-04");
    }

    // ---------------------------------------------------------------------
    // Subprocess mechanics
    // ---------------------------------------------------------------------

    /**
     * Runs the osm2streets-cli binary with the given OSM XML as stdin and returns the JSON body it
     * writes to stdout.
     *
     * <p>Package-private for direct unit testing via shell-script stand-ins; Plan 24-04 wraps this
     * inside {@link #fetchAndConvert(BboxRequest)} after wiring in {@code OsmPipelineService.fetch*}.
     *
     * @param osmXml raw OSM XML bytes (may be empty)
     * @return decoded UTF-8 stdout of the subprocess
     * @throws IOException if the process cannot be started
     * @throws Osm2StreetsCliException if the process exits with a non-zero code
     * @throws Osm2StreetsCliTimeoutException if the process does not finish in time
     */
    String executeCli(byte[] osmXml) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(config.getBinaryPath());
        pb.redirectErrorStream(false);
        Process process = pb.start();

        // Drain stdout + stderr on SEPARATE threads (RESEARCH Pitfall 6). If we read stdout inline
        // and stderr overflows its pipe buffer — or vice versa — the subprocess blocks and we
        // deadlock. CompletableFuture.supplyAsync uses the common ForkJoinPool which is adequate
        // for this fan-out of 2.
        CompletableFuture<byte[]> stdoutFuture = CompletableFuture.supplyAsync(() -> {
            try (InputStream is = process.getInputStream()) {
                return is.readAllBytes();
            } catch (IOException e) {
                throw new IllegalStateException("stdout read failed", e);
            }
        });
        CompletableFuture<byte[]> stderrFuture = CompletableFuture.supplyAsync(() -> {
            try (InputStream is = process.getErrorStream()) {
                return is.readAllBytes();
            } catch (IOException e) {
                throw new IllegalStateException("stderr read failed", e);
            }
        });

        // Pipe XML into stdin then close via try-with-resources (RESEARCH Pitfall 3).
        // Closing stdin is what drives `read_to_end` on the Rust side to EOF — without it the
        // binary hangs forever even if `osmXml` is empty.
        try (OutputStream stdin = process.getOutputStream()) {
            if (osmXml.length > 0) {
                stdin.write(osmXml);
            }
            stdin.flush();
        }

        boolean finished;
        try {
            finished = process.waitFor(config.getTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new Osm2StreetsCliTimeoutException(
                    "osm2streets-cli interrupted after " + config.getTimeoutSeconds() + "s");
        }
        if (!finished) {
            process.destroyForcibly();
            throw new Osm2StreetsCliTimeoutException(
                    "osm2streets-cli timed out after " + config.getTimeoutSeconds() + "s");
        }

        int exit = process.exitValue();
        if (exit != 0) {
            String stderr = readFuture(stderrFuture, "<stderr unreadable>");
            throw new Osm2StreetsCliException(
                    "osm2streets-cli exited " + exit + ": " + stderr.trim());
        }
        byte[] stdoutBytes;
        try {
            stdoutBytes = stdoutFuture.get(STREAM_READ_BUFFER_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new Osm2StreetsCliException("Failed to read stdout: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Osm2StreetsCliException("Interrupted reading stdout: " + e.getMessage());
        }
        return new String(stdoutBytes, StandardCharsets.UTF_8);
    }

    /** Reads a drain future after the process has exited; returns a fallback marker on failure. */
    private String readFuture(CompletableFuture<byte[]> future, String fallback) {
        try {
            return new String(
                    future.get(STREAM_READ_BUFFER_SECONDS, TimeUnit.SECONDS), StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback + " (interrupted: " + e.getMessage() + ")";
        } catch (ExecutionException | TimeoutException e) {
            return fallback + " (" + e.getMessage() + ")";
        }
    }
}
