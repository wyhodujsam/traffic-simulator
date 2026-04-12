---
phase: 20-ai-vision-claude-cli
plan: "01"
subsystem: backend-vision
tags: [claude-cli, vision, multipart, spring-boot, processbuilder]
dependency_graph:
  requires: [MapConfig, MapValidator, OsmController-pattern]
  provides: [ClaudeVisionService, VisionController, ClaudeCliConfig]
  affects: [backend-api]
tech_stack:
  added: [ProcessBuilder, MultipartFile, ConfigurationProperties]
  patterns: [WebMvcTest, spy-for-process-mocking, @ConfigurationProperties]
key_files:
  created:
    - backend/src/main/java/com/trafficsimulator/config/ClaudeCliConfig.java
    - backend/src/main/java/com/trafficsimulator/service/ClaudeVisionService.java
    - backend/src/main/java/com/trafficsimulator/controller/VisionController.java
    - backend/src/test/java/com/trafficsimulator/controller/VisionControllerTest.java
    - backend/src/test/java/com/trafficsimulator/service/ClaudeVisionServiceTest.java
  modified:
    - backend/src/main/resources/application.properties
decisions:
  - "Custom exceptions as public static inner classes of ClaudeVisionService — avoids extra files, keeps them close to the service that throws them"
  - "executeCliCommand() extracted as package-private method to allow spy-based mocking without ProcessBuilder in unit tests"
  - "extractJson() finds first { to last } — handles Claude CLI preamble/postamble in output"
  - "@MockBean (not @MockitoBean) because Spring Boot 3.3.5 — @MockitoBean requires 3.4+"
metrics:
  duration_minutes: 12
  completed_date: "2026-04-12"
  tasks_completed: 2
  tasks_total: 2
  files_created: 5
  files_modified: 1
  tests_added: 19
  tests_total: 238
---

# Phase 20 Plan 01: Claude CLI Vision Service Summary

**One-liner:** ProcessBuilder-based Claude CLI image analysis with MapConfig JSON extraction, validation, and 400/422/503/504 error handling via POST /api/vision/analyze.

## What Was Built

The backend AI vision path: upload an image, invoke Claude CLI, parse the JSON output into a validated MapConfig.

### ClaudeCliConfig

`@ConfigurationProperties(prefix = "claude.cli")` component with three fields:
- `path` (default: "claude") — CLI binary path
- `timeoutSeconds` (default: 30) — process timeout
- `tempDir` (default: java.io.tmpdir) — temp file storage

Registered in `application.properties` with sensible defaults.

### ClaudeVisionService

Core service with `analyzeImage(MultipartFile)`:
1. Saves upload to a temp file
2. Calls `executeCliCommand(claude -p <prompt> --image <path>)`
3. Reads stdout, checks exit code and timeout
4. Extracts JSON from output (first `{` to last `}`)
5. Parses into MapConfig via ObjectMapper
6. Validates with MapValidator — throws ClaudeCliParseException on errors
7. Returns valid MapConfig

The `ANALYSIS_PROMPT` constant embeds the full MapConfig schema and instructs Claude to output only JSON (no markdown fences).

Custom exceptions (public static inner classes):
- `ClaudeCliException` — non-zero exit code
- `ClaudeCliTimeoutException` — process timeout
- `ClaudeCliParseException` — JSON not found or MapConfig invalid

### VisionController

`@RestController @RequestMapping("/api/vision")` following OsmController pattern:
- `POST /api/vision/analyze` — accepts multipart `image` param
- Validates: not empty, content type = image/jpeg or image/png, size <= 10MB
- Returns 200 MapConfig on success
- Exception handlers: 504 (timeout), 422 (parse), 503 (CLI unavailable), 500 (IO)

## Tests

**VisionControllerTest** (6 tests, @WebMvcTest):
- Valid PNG returns 200 with MapConfig JSON
- Empty file returns 400
- Wrong content type returns 400
- ClaudeCliTimeoutException maps to 504
- ClaudeCliParseException maps to 422
- ClaudeCliException maps to 503

**ClaudeVisionServiceTest** (13 tests, unit):
- ANALYSIS_PROMPT contains all MapConfig field names (nodes, roads, intersections, spawnPoints, despawnPoints)
- ANALYSIS_PROMPT instructs JSON-only output
- extractJson: preamble+JSON+postamble handled correctly
- extractJson: pure JSON passes through
- extractJson: missing JSON throws ClaudeCliParseException
- getExtension: .png, .jpg, .jpeg (case-insensitive), null, unknown all handled
- analyzeImage happy path via spy (mocked executeCliCommand)
- analyzeImage with validation errors throws ClaudeCliParseException

## Deviations from Plan

None — plan executed exactly as written.

## Known Stubs

None. The prompt instructs Claude to return a structurally complete MapConfig. The actual content depends on Claude CLI analysis of the uploaded image at runtime — this is intentional behavior, not a stub.

## Self-Check

Files created:
- backend/src/main/java/com/trafficsimulator/config/ClaudeCliConfig.java
- backend/src/main/java/com/trafficsimulator/service/ClaudeVisionService.java
- backend/src/main/java/com/trafficsimulator/controller/VisionController.java
- backend/src/test/java/com/trafficsimulator/controller/VisionControllerTest.java
- backend/src/test/java/com/trafficsimulator/service/ClaudeVisionServiceTest.java

Commits:
- 39d15e4: feat(20-01): ClaudeCliConfig + ClaudeVisionService
- 21c700a: feat(20-01): VisionController + tests
