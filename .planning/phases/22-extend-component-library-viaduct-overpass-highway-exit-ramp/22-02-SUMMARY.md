---
phase: 22
plan: 02
subsystem: vision/component-library
tags: [claude-prompt, dto-mapping, viaduct, highway-exit-ramp]
requirements: [CLIB-V22-03]
key-files:
  modified:
    - backend/src/main/java/com/trafficsimulator/service/ComponentVisionService.java
    - backend/src/test/java/com/trafficsimulator/service/ComponentVisionServiceTest.java
commit: d2bf9aa
---

# Phase 22 Plan 02: Expose VIADUCT + HIGHWAY_EXIT_RAMP to Claude Summary

Wired the two new record types (added in plan 22-01) into the Claude pipeline:

- `ANALYSIS_PROMPT` now enumerates `VIADUCT` and `HIGHWAY_EXIT_RAMP` alongside the original 4 MVP types, with arm names, field schemas, and worked examples for each.
- `ComponentSpecDto.fromDto` (a.k.a. `toSpec`) switch adds `case "VIADUCT" -> new Viaduct(...)` and `case "HIGHWAY_EXIT_RAMP" -> new HighwayExitRamp(...)` branches.
- Unknown-type error message updated to list all 6 supported types.

## Verification
`ComponentVisionServiceTest` covers parsing of both new types (valid, missing fields, unknown arm). `mvn test` — 308/308 green.
