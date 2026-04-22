---
phase: 21
plan: 03
subsystem: vision/component-library
tags: [claude-cli, vision-service, prompt, dto-mapping, expansion-integration]
requirements: [P21-VISION-PROMPT, P21-VISION-PARSE, P21-VISION-EXPAND-INTEGRATION]
key-files:
  created:
    - backend/src/main/java/com/trafficsimulator/service/ComponentVisionService.java
    - backend/src/main/java/com/trafficsimulator/vision/components/ComponentSpecDto.java
    - backend/src/test/java/com/trafficsimulator/service/ComponentVisionServiceTest.java
commit: b66a1f5
fixups: c2e4d59 (STRAIGHT_SEGMENT arm naming + explicit-connection contract)
---

# Phase 21 Plan 03: ComponentVisionService Summary

New `ComponentVisionService` owns the component-library Claude prompt, invokes the Claude CLI on the image bytes, parses the returned JSON envelope (`{components, connections}`) into `ComponentSpec`/`Connection` records, and delegates to `MapComponentLibrary.expand(...)` for the final `MapConfig`.

## Prompt contract

- Enumerates the 4 MVP types (ROUNDABOUT_4ARM, SIGNAL_4WAY, T_INTERSECTION, STRAIGHT_SEGMENT) with arm names and worked examples.
- Explicitly forbids invention — "if you cannot fit a region, OMIT IT".
- Post-fix (c2e4d59) the prompt spells out STRAIGHT_SEGMENT arms as `start`/`end` and includes a worked bridging example after Claude was observed emitting `{a:"seg1.a", b:"seg1.b"}`.

## Parse layer

Two-pass DTO → record mapping via `ComponentSpecDto.Envelope`. Unknown types raise `ClaudeCliParseException` with the enumerated-types list appended to the message. `MapComponentLibrary.ExpansionException` and `IllegalArgumentException` are caught and rethrown as `ClaudeCliParseException` so the controller layer has a single error funnel.

## Intentional duplication

`executeCliCommand(...)` and `extractJson(...)` are copied byte-for-byte from `ClaudeVisionService` to honour the Phase 20 "additive-only" lock. Extraction into `ClaudeCliRunner` is a follow-up refactor — `TODO` comments mark both methods.

## Verification
`mvn test` — `ComponentVisionServiceTest` covers prompt sanity, DTO parsing, CLI error propagation (timeout, exit-code, malformed JSON), and end-to-end integration with `MapComponentLibrary`.
