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
import com.trafficsimulator.config.MapValidator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Invokes the Claude CLI to analyse an uploaded road image and return a {@link MapConfig}. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClaudeVisionService {

    // -------------------------------------------------------------------------
    // Custom exceptions
    // -------------------------------------------------------------------------

    /** Thrown when the Claude CLI process exits with a non-zero code. */
    public static class ClaudeCliException extends RuntimeException {
        public ClaudeCliException(String message) {
            super(message);
        }
    }

    /** Thrown when the Claude CLI process does not complete within the configured timeout. */
    public static class ClaudeCliTimeoutException extends RuntimeException {
        public ClaudeCliTimeoutException(String message) {
            super(message);
        }
    }

    /** Thrown when the Claude CLI output cannot be parsed into a valid {@link MapConfig}. */
    public static class ClaudeCliParseException extends RuntimeException {
        public ClaudeCliParseException(String message) {
            super(message);
        }
    }

    // -------------------------------------------------------------------------
    // Prompt constant
    // -------------------------------------------------------------------------

    static final String ANALYSIS_PROMPT =
            "You are a traffic simulation map generator. Analyse the road image and output a "
                    + "MapConfig JSON that describes the road network visible in the image.\n\n"
                    + "REQUIRED JSON STRUCTURE (output ONLY this JSON, no markdown fences, no "
                    + "explanation text):\n"
                    + "{\n"
                    + "  \"id\": \"vision-generated\",\n"
                    + "  \"name\": \"AI Generated Map\",\n"
                    + "  \"description\": \"Map generated from road image analysis\",\n"
                    + "  \"defaultSpawnRate\": 1.0,\n"
                    + "  \"nodes\": [\n"
                    + "    {\"id\": \"n1\", \"type\": \"ENTRY\", \"x\": 100, \"y\": 300},\n"
                    + "    {\"id\": \"n2\", \"type\": \"INTERSECTION\", \"x\": 400, \"y\": 300},\n"
                    + "    {\"id\": \"n3\", \"type\": \"EXIT\", \"x\": 700, \"y\": 300}\n"
                    + "  ],\n"
                    + "  \"roads\": [\n"
                    + "    {\"id\": \"r1\", \"fromNodeId\": \"n1\", \"toNodeId\": \"n2\", "
                    + "\"length\": 300.0, \"speedLimit\": 50.0, \"laneCount\": 2}\n"
                    + "  ],\n"
                    + "  \"intersections\": [\n"
                    + "    {\"nodeId\": \"n2\", \"type\": \"PRIORITY\"}\n"
                    + "  ],\n"
                    + "  \"spawnPoints\": [\n"
                    + "    {\"roadId\": \"r1\", \"laneIndex\": 0, \"position\": 0.0}\n"
                    + "  ],\n"
                    + "  \"despawnPoints\": [\n"
                    + "    {\"roadId\": \"r1\", \"laneIndex\": 0, \"position\": 300.0}\n"
                    + "  ]\n"
                    + "}\n\n"
                    + "RULES:\n"
                    + "- Use node IDs: n1, n2, n3, ... (sequential integers)\n"
                    + "- Use road IDs: r1, r2, r3, ... (sequential integers)\n"
                    + "- Node types: ENTRY (network entry point), EXIT (network exit point), "
                    + "INTERSECTION (junction)\n"
                    + "- Road length: estimate in pixels, range 100–500 per segment\n"
                    + "- speedLimit: always 50.0 (urban default)\n"
                    + "- laneCount: detect from image (default 2, range 1–4)\n"
                    + "- Add spawnPoints on roads leaving ENTRY nodes (position 0.0)\n"
                    + "- Add despawnPoints on roads entering EXIT nodes (position = road length)\n"
                    + "- Intersection types: PRIORITY (default), SIGNAL (if traffic lights visible), "
                    + "ROUNDABOUT (if roundabout visible)\n"
                    + "- Output ONLY valid JSON. No markdown fences. No explanation text.";

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final ClaudeCliConfig config;
    private final MapValidator mapValidator;
    private final ObjectMapper objectMapper;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Analyses a road image using Claude CLI and returns a validated {@link MapConfig}.
     *
     * @param file multipart image upload (JPEG or PNG)
     * @return validated MapConfig representing the road network in the image
     * @throws IOException if temp file I/O fails
     * @throws ClaudeCliTimeoutException if Claude CLI exceeds timeout
     * @throws ClaudeCliException if Claude CLI exits non-zero
     * @throws ClaudeCliParseException if output cannot be parsed into a valid MapConfig
     */
    public MapConfig analyzeImage(MultipartFile file) throws IOException {
        Path tempFile = createTempFile(file);
        try {
            return analyzeImagePath(tempFile);
        } finally {
            deleteSilently(tempFile);
        }
    }

    /** Analyse a PNG image given as raw bytes (e.g. composed server-side from OSM tiles). */
    public MapConfig analyzeImageBytes(byte[] data) throws IOException {
        Path tempFile = Files.createTempFile(Path.of(config.getTempDir()), "vision-bbox-", ".png");
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
        MapConfig mapConfig = parseJson(json);
        validateConfig(mapConfig);
        return mapConfig;
    }

    // -------------------------------------------------------------------------
    // Package-private helpers (testable via subclass / spy)
    // -------------------------------------------------------------------------

    /**
     * Executes the given command and returns its combined stdout+stderr output.
     * Extracted as a separate method to allow mocking in unit tests.
     */
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

        log.debug("Claude CLI output ({} chars): {}", output.length(), output.substring(0, Math.min(200, output.length())));
        return output;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private Path createTempFile(MultipartFile file) throws IOException {
        String ext = getExtension(file.getOriginalFilename());
        Path tempFile = Files.createTempFile(Path.of(config.getTempDir()), "vision-", ext);
        file.transferTo(tempFile);
        return tempFile;
    }

    /**
     * Extracts the first complete JSON object from the given text. Handles preamble and postamble
     * produced by Claude CLI when it adds explanatory text around the JSON.
     */
    String extractJson(String output) {
        int start = output.indexOf('{');
        int end = output.lastIndexOf('}');
        if (start == -1 || end == -1 || end < start) {
            throw new ClaudeCliParseException("Could not find JSON in Claude CLI output");
        }
        return output.substring(start, end + 1);
    }

    private MapConfig parseJson(String json) {
        try {
            return objectMapper.readValue(json, MapConfig.class);
        } catch (JsonProcessingException e) {
            throw new ClaudeCliParseException("Invalid JSON: " + e.getMessage());
        }
    }

    private void validateConfig(MapConfig mapConfig) {
        List<String> errors = mapValidator.validate(mapConfig);
        if (!errors.isEmpty()) {
            throw new ClaudeCliParseException(
                    "Generated config has validation errors: " + String.join(", ", errors));
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
