package com.trafficsimulator.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trafficsimulator.config.ClaudeCliConfig;
import com.trafficsimulator.config.MapConfig;
import com.trafficsimulator.service.ClaudeVisionService.ClaudeCliException;
import com.trafficsimulator.service.ClaudeVisionService.ClaudeCliParseException;
import com.trafficsimulator.service.ClaudeVisionService.ClaudeCliTimeoutException;
import com.trafficsimulator.vision.components.ArmRef;
import com.trafficsimulator.vision.components.ComponentSpec;
import com.trafficsimulator.vision.components.ComponentSpecDto;
import com.trafficsimulator.vision.components.Connection;
import com.trafficsimulator.vision.components.HighwayExitRamp;
import com.trafficsimulator.vision.components.RoundaboutFourArm;
import com.trafficsimulator.vision.components.SignalFourWay;
import com.trafficsimulator.vision.components.StraightSegment;
import com.trafficsimulator.vision.components.TIntersection;
import com.trafficsimulator.vision.components.Viaduct;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Phase 21 alternative vision pipeline. Claude identifies components (roundabouts, signals, etc.)
 * and how they connect; {@link MapComponentLibrary} deterministically expands that recognition into
 * a validated {@link MapConfig}.
 *
 * <p>Signatures mirror {@link ClaudeVisionService} on purpose — the controller layer can toggle
 * between pipelines by picking which service to call.
 *
 * <p><b>Intentional duplication:</b> {@link #executeCliCommand(String...)} and
 * {@link #extractJson(String)} are copied byte-for-byte from {@link ClaudeVisionService} to
 * preserve the Phase 20 "additive only" lock (CONTEXT.md §1 and RESEARCH §"Reuse Strategy"). Do
 * NOT extract a shared runner here — that refactor is deferred to a later phase.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ComponentVisionService {

    // -------------------------------------------------------------------------
    // Prompt constant — enumerates the 4 MVP types, forbids invention.
    // -------------------------------------------------------------------------

    static final String ANALYSIS_PROMPT =
            "You are a traffic-map recogniser. Look at the road image and identify which\n"
                    + "PREDEFINED COMPONENTS appear, where they are, and how they connect.\n\n"
                    + "VALID COMPONENT TYPES (you MUST NOT invent new types — if you cannot fit a\n"
                    + "region into one of these, OMIT IT):\n\n"
                    + "- ROUNDABOUT_4ARM — circular ring with up to 4 arms (north/east/south/west)\n"
                    + "  fields: id (lowercase alnum, no 'in'/'out'), centerPx{x,y}, rotationDeg,\n"
                    + "          armsPresent (subset of [north,east,south,west])\n"
                    + "- SIGNAL_4WAY — signalised intersection with up to 4 approaches\n"
                    + "  fields: id, centerPx{x,y}, rotationDeg, armsPresent\n"
                    + "- T_INTERSECTION — 3 arms, PRIORITY control\n"
                    + "  fields: id, centerPx{x,y}, rotationDeg (0 = stem points south),\n"
                    + "          armsPresent (exactly 3 of [north,east,south,west])\n"
                    + "- STRAIGHT_SEGMENT — connector road between two component arms\n"
                    + "  fields: id, startPx{x,y}, endPx{x,y}, lengthPx\n"
                    + "  ARM NAMES: exactly two — `start` and `end` (NOT `a`/`b`). Reference them\n"
                    + "  in connections as `<segId>.start` and `<segId>.end`.\n"
                    + "- VIADUCT — two through-roads crossing at different heights (overpass)\n"
                    + "  fields: id, centerPx{x,y}, rotationDeg\n"
                    + "  ARMS ARE FIXED: [north, east, south, west] — OMIT the armsPresent field.\n"
                    + "  Lower road connects south ↔ north, upper road connects west ↔ east; there\n"
                    + "  is NO shared intersection at the crossing point. Example connection:\n"
                    + "  `{a:\"rb1.north\", b:\"via1.south\"}`.\n"
                    + "- HIGHWAY_EXIT_RAMP — PRIORITY split where a ramp exits the main line\n"
                    + "  fields: id, centerPx{x,y}, rotationDeg\n"
                    + "  ARMS ARE FIXED: [main_in, main_out, ramp_out] — OMIT the armsPresent field.\n"
                    + "  main_in is upstream highway, main_out downstream continuation, ramp_out the\n"
                    + "  exit ramp. Main-line traffic has PRIORITY. Example connection:\n"
                    + "  `{a:\"hr1.main_out\", b:\"seg1.start\"}`.\n\n"
                    + "OUTPUT FORMAT (raw JSON only, no markdown fences, no prose):\n"
                    + "{\n"
                    + "  \"components\": [ { \"type\": \"...\", \"id\": \"...\", ... }, ... ],\n"
                    + "  \"connections\": [ { \"a\": \"<componentId>.<armName>\",\n"
                    + "                     \"b\": \"<componentId>.<armName>\" }, ... ]\n"
                    + "}\n\n"
                    + "RULES:\n"
                    + "- Component ids MUST match ^[a-z][a-z0-9]*$ and MUST NOT contain 'in' or 'out'.\n"
                    + "  Safe prefixes: rb, sig, t, seg, via, hr, then a digit (rb1, sig2, t3,\n"
                    + "  seg4, via1, hr2).\n"
                    + "- If two component arms meet at the same pixel location, emit one connection.\n"
                    + "- If two arms are separated by visible road, insert a STRAIGHT_SEGMENT and emit\n"
                    + "  two connections (one per end). Example: connecting rb1.north to rb2.south\n"
                    + "  via a segment seg1 → connections `[{a:\"rb1.north\", b:\"seg1.start\"},\n"
                    + "  {a:\"seg1.end\", b:\"rb2.south\"}]`. A highway exit ramp can chain similarly:\n"
                    + "  `hr1.main_out → seg1.start → rb2.west`.\n"
                    + "- For VIADUCT and HIGHWAY_EXIT_RAMP, OMIT the armsPresent field entirely —\n"
                    + "  arms are fixed by the component type.\n"
                    + "- Unconnected arms = network boundaries (traffic enters/exits there). Fine.\n"
                    + "- At most ONE non-connector component per pixel location.";

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final ClaudeCliConfig config;
    private final MapComponentLibrary mapComponentLibrary;
    private final ObjectMapper objectMapper;

    // -------------------------------------------------------------------------
    // Public API (mirrors ClaudeVisionService signatures)
    // -------------------------------------------------------------------------

    /** Analyse an uploaded multipart image and return a validated {@link MapConfig}. */
    public MapConfig analyzeImage(MultipartFile file) throws IOException {
        String ext = getExtension(file.getOriginalFilename());
        Path tempFile =
                Files.createTempFile(Path.of(config.getTempDir()), "vision-components-", ext);
        try {
            file.transferTo(tempFile);
            return analyzeImagePath(tempFile);
        } finally {
            deleteSilently(tempFile);
        }
    }

    /** Analyse a PNG image given as raw bytes. */
    public MapConfig analyzeImageBytes(byte[] data) throws IOException {
        Path tempFile =
                Files.createTempFile(Path.of(config.getTempDir()), "vision-components-", ".png");
        try {
            Files.write(tempFile, data);
            return analyzeImagePath(tempFile);
        } finally {
            deleteSilently(tempFile);
        }
    }

    private MapConfig analyzeImagePath(Path tempFile) throws IOException {
        String promptWithFile = ANALYSIS_PROMPT
                + "\n\nAnalyze the road image at: " + tempFile.toAbsolutePath()
                + "\nOutput ONLY valid JSON, no markdown fences.";
        String output = executeCliCommand(
                config.getPath(),
                "-p",
                promptWithFile,
                "--output-format",
                "text");

        String json = extractJson(output);
        ComponentSpecDto.Envelope envelope = parseEnvelope(json);
        List<ComponentSpec> specs =
                envelope.components == null
                        ? List.of()
                        : envelope.components.stream().map(this::toSpec).toList();
        List<Connection> connections =
                envelope.connections == null
                        ? List.of()
                        : envelope.connections.stream()
                                .map(c -> new Connection(ArmRef.parse(c.a), ArmRef.parse(c.b)))
                                .toList();
        try {
            return mapComponentLibrary.expand(specs, connections);
        } catch (MapComponentLibrary.ExpansionException e) {
            throw new ClaudeCliParseException(e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ClaudeCliParseException(e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Two-pass DTO → record mapping
    // -------------------------------------------------------------------------

    ComponentSpec toSpec(ComponentSpecDto d) {
        if (d == null || d.type == null) {
            throw new ClaudeCliParseException("Component entry missing 'type' field");
        }
        return switch (d.type) {
            case "ROUNDABOUT_4ARM" ->
                    new RoundaboutFourArm(d.id, d.centerPx, d.rotationDeg, d.armsPresent);
            case "SIGNAL_4WAY" ->
                    new SignalFourWay(d.id, d.centerPx, d.rotationDeg, d.armsPresent);
            case "T_INTERSECTION" ->
                    new TIntersection(d.id, d.centerPx, d.rotationDeg, d.armsPresent);
            case "STRAIGHT_SEGMENT" ->
                    new StraightSegment(
                            d.id, d.startPx, d.endPx, d.lengthPx == null ? 0.0 : d.lengthPx);
            case "VIADUCT" -> new Viaduct(d.id, d.centerPx, d.rotationDeg);
            case "HIGHWAY_EXIT_RAMP" -> new HighwayExitRamp(d.id, d.centerPx, d.rotationDeg);
            default ->
                    throw new ClaudeCliParseException(
                            "Unknown component type: "
                                    + d.type
                                    + ". Valid types: ROUNDABOUT_4ARM, SIGNAL_4WAY, T_INTERSECTION, STRAIGHT_SEGMENT, VIADUCT, HIGHWAY_EXIT_RAMP");
        };
    }

    // -------------------------------------------------------------------------
    // Package-private helpers (testable via subclass / spy)
    // -------------------------------------------------------------------------

    // TODO: extract ClaudeCliRunner once Phase 21 stabilises
    // (RESEARCH §"Reuse Strategy" — duplicated from ClaudeVisionService to honour
    //  the additive-only lock; extraction is a follow-up refactor).
    String executeCliCommand(String... command) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        Process process = pb.start();
        String output;
        try (InputStream is = process.getInputStream()) {
            output = new String(is.readAllBytes());
        }

        boolean finished;
        try {
            finished = process.waitFor(config.getTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new ClaudeCliTimeoutException(
                    "Analysis interrupted after " + config.getTimeoutSeconds() + " seconds");
        }

        if (!finished) {
            process.destroyForcibly();
            throw new ClaudeCliTimeoutException(
                    "Analysis timed out after " + config.getTimeoutSeconds() + " seconds");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new ClaudeCliException(
                    "Claude CLI failed with exit code " + exitCode + ": " + output.trim());
        }

        log.debug(
                "Claude CLI output ({} chars): {}",
                output.length(),
                output.substring(0, Math.min(200, output.length())));
        return output;
    }

    // TODO: extract ClaudeCliRunner once Phase 21 stabilises (duplicated from ClaudeVisionService).
    String extractJson(String output) {
        int start = output.indexOf('{');
        int end = output.lastIndexOf('}');
        if (start == -1 || end == -1 || end < start) {
            throw new ClaudeCliParseException("Could not find JSON in Claude CLI output");
        }
        return output.substring(start, end + 1);
    }

    private ComponentSpecDto.Envelope parseEnvelope(String json) {
        try {
            return objectMapper.readValue(json, ComponentSpecDto.Envelope.class);
        } catch (JsonProcessingException e) {
            throw new ClaudeCliParseException("Invalid JSON: " + e.getMessage());
        }
    }

    /** Returns ".png" or ".jpg" based on original filename; defaults to ".png". */
    String getExtension(String filename) {
        if (filename == null) {
            return ".png";
        }
        String lower = filename.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return ".jpg";
        }
        return ".png";
    }

    private void deleteSilently(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not delete temp file: {}", path, e);
        }
    }
}
