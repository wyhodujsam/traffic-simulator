---
phase: 21
plan: 04
subsystem: controller/vision
tags: [rest-endpoint, multipart, bbox, controller]
requirements: [P21-ENDPOINT-MULTIPART, P21-ENDPOINT-BBOX, P21-PHASE20-REGRESSION]
key-files:
  modified:
    - backend/src/main/java/com/trafficsimulator/controller/VisionController.java
    - backend/src/test/java/com/trafficsimulator/controller/VisionControllerTest.java
commit: 0000a03
---

# Phase 21 Plan 04: Vision Controller Endpoints Summary

Added two endpoints mirroring the Phase 20 contract:

- `POST /api/vision/analyze-components` — multipart image → `ComponentVisionService.analyzeImage(MultipartFile)` → `MapConfig`.
- `POST /api/vision/analyze-components-bbox` — `BboxRequest` → screenshot via existing Phase 20 helper → `ComponentVisionService.analyzeImageBytes(byte[])` → `MapConfig`.

Error handling is identical to the Phase 20 endpoints: `ClaudeCliTimeoutException` → 504, `ClaudeCliParseException` → 422, `ClaudeCliException` → 502, IO failure → 500. The Phase 20 endpoints remain untouched — `VisionControllerTest` exercises both pipelines side-by-side and the regression cases.

## Verification
`VisionControllerTest` passes both the new `/analyze-components*` cases and the pre-existing `/analyze*` regression suite (services mocked).
