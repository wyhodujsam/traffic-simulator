---
phase: 21
plan: 06
subsystem: vision/harness
tags: [integration, diff, opt-in, spring-boot-test]
requirements: [P21-HARNESS-GATED, P21-HARNESS-DIFF]
key-files:
  created:
    - backend/src/test/java/com/trafficsimulator/integration/VisionComparisonHarness.java
commit: 25ec1c4
---

# Phase 21 Plan 06: Vision Comparison Harness Summary

Opt-in `@SpringBootTest` tool that, when invoked with `-Dvision.harness=true` and a fixture image present, runs **both** `ClaudeVisionService` (Phase 20 free-form) and `ComponentVisionService` (Phase 21 component-library) on the same input and writes:

- `target/vision-comparison/free-form/map.json`
- `target/vision-comparison/components/map.json`
- `target/vision-comparison/{free-form,components}/diff.md` (per-pipeline validator summary + node/road counts)

Gated by `@EnabledIfSystemProperty(named = "vision.harness", matches = "true")` so the default `mvn test` never calls the Claude CLI. JUnit `assume` skips when fixtures are missing. Plan 22-03 parametrised this over viaduct + highway-exit-ramp fixtures.

## Verification
Default `mvn test` does not run the harness (confirmed: `Tests run: 308, Skipped: 0` — harness not among them). Manual invocation with `-Dvision.harness=true` produces the artifact tree under `target/vision-comparison/`.
