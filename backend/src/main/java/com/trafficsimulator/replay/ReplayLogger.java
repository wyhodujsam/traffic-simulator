package com.trafficsimulator.replay;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trafficsimulator.dto.VehicleSnapshotDto;

import lombok.extern.slf4j.Slf4j;

/**
 * NDJSON replay logger per CONTEXT.md §D-14. Writes one JSON object per line. Append-only.
 * Tick-loop-safe: any {@link IOException} is caught, logged ONCE at WARN, and disables the logger
 * for the rest of the run (RESEARCH.md §Pitfall #4 / threat T-25-IO).
 *
 * <p>Path-traversal safe (T-25-01): the output path is constructed from internal fields only (seed
 * Long + ISO8601 timestamp from {@link Instant#now()}). No caller-provided string ever participates
 * in the filename — see {@link ReplayLoggerProperties#getDirectory()} for the configurable parent
 * directory; the leaf is always {@code <seed>-<ISO8601>.ndjson}.
 *
 * <p>Spring lifecycle: registered as a {@link Component} with {@link
 * EnableConfigurationProperties}. The {@code simulator.replay.enabled} property controls whether
 * the logger writes by default; RUN_FOR_TICKS / RUN_FOR_TICKS_FAST dispatchers force-start the
 * logger regardless of the property.
 */
@Component
@EnableConfigurationProperties(ReplayLoggerProperties.class)
@Slf4j
public class ReplayLogger implements AutoCloseable {

    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneOffset.UTC);

    private final ObjectMapper objectMapper;
    private final ReplayLoggerProperties props;

    private BufferedWriter writer;
    private Path path;
    private final AtomicBoolean alreadyWarned = new AtomicBoolean(false);
    private boolean disabled;

    public ReplayLogger(ObjectMapper objectMapper, ReplayLoggerProperties props) {
        this.objectMapper = objectMapper;
        this.props = props;
    }

    /**
     * Begins a new replay file. Idempotent: closes any prior writer first. Writes the header line
     * per D-14 schema: {@code {"type":"header","seed":<long>,"source":<string>,
     * "mapId":<string>,"tickDt":0.05}}.
     */
    public synchronized void start(long seed, String source, String mapId, double tickDt) {
        try {
            close(); // safety: close any previous handle
            Path dir = Path.of(props.getDirectory());
            Files.createDirectories(dir);
            String iso = TS_FORMAT.format(Instant.now());
            this.path = dir.resolve(seed + "-" + iso + ".ndjson");
            this.writer =
                    Files.newBufferedWriter(
                            path, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            this.alreadyWarned.set(false);
            this.disabled = false;
            // Header line per D-14
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("type", "header");
            header.put("seed", seed);
            header.put("source", source);
            header.put("mapId", mapId);
            header.put("tickDt", tickDt);
            writeLine(header);
            log.info("[ReplayLogger] Started writing to {}", path);
        } catch (IOException e) {
            handleIoError("start", e);
        }
    }

    /**
     * Appends one tick line per D-14 schema: {@code {"type":"tick","tick":<n>,"vehicles":[...]}}.
     * No-op if not started or if a previous IOException disabled the logger (T-25-IO).
     */
    public synchronized void onTick(long tick, List<VehicleSnapshotDto> vehicles) {
        if (writer == null || disabled) {
            return;
        }
        try {
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("type", "tick");
            rec.put("tick", tick);
            rec.put("vehicles", vehicles);
            writeLine(rec);
        } catch (IOException e) {
            handleIoError("onTick", e);
        }
    }

    private void writeLine(Object record) throws IOException {
        writer.write(objectMapper.writeValueAsString(record));
        writer.newLine();
    }

    private void handleIoError(String stage, IOException e) {
        if (alreadyWarned.compareAndSet(false, true)) {
            log.warn(
                    "[ReplayLogger] Disabled at {} — disk write failed: {}", stage, e.getMessage());
        }
        disabled = true;
        try {
            close();
        } catch (IOException ignored) {
            // Already in error state; suppression is intentional (T-25-IO).
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (writer != null) {
            try {
                writer.flush();
                writer.close();
            } finally {
                writer = null;
            }
        }
    }

    /**
     * @return current file path or {@code null} if not started.
     */
    public synchronized Path getPath() {
        return path;
    }

    /**
     * @return {@code true} iff the logger is actively writing (started + not disabled).
     */
    public synchronized boolean isActive() {
        return writer != null && !disabled;
    }
}
