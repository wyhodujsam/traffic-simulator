---
phase: 22-extend-component-library-viaduct-overpass-highway-exit-ramp
plan: 03
subsystem: vision-harness
tags: [vision, harness, fixtures, viaduct, highway-exit-ramp, parametrised-test]
requirements: [CLIB-V22-04]
dependency_graph:
  requires:
    - 22-01 (Viaduct + HighwayExitRamp records)
    - 22-02 (ComponentVisionService prompt + DTO mapping)
    - 25ec1c4 (original VisionComparisonHarness — Phase 21)
  provides:
    - Parametrised regression button for viaduct / highway-exit-ramp vision fixtures
  affects:
    - backend/src/test/java/com/trafficsimulator/integration/VisionComparisonHarness.java
tech_stack:
  added: []
  patterns:
    - "@ParameterizedTest + @MethodSource for fixture iteration"
    - "Per-fixture output directory: target/vision-comparison/{label}/"
    - "Assumptions.assumeTrue on fixture presence — skip, never fail"
key_files:
  created: []
  modified:
    - backend/src/test/java/com/trafficsimulator/integration/VisionComparisonHarness.java
decisions:
  - "Parametrise the existing single-fixture method rather than duplicating it."
  - "Fixture PNG generation for viaduct / highway-exit-ramp DEFERRED — see rationale below."
metrics:
  duration: "~5min (refactor + verification)"
  completed: 2026-04-13
---

# Phase 22 Plan 03: Vision Harness Fixture Coverage — Summary

Extended the opt-in `VisionComparisonHarness` to iterate three fixture paths
(`roundabout`, `viaduct`, `highway-exit-ramp`) via `@ParameterizedTest` so the Phase 22
component types share the same diagnostic button as the original Phase 21 roundabout
fixture. Default `mvn test` unchanged (harness stays gated by `-Dvision.harness=true`).

## What changed

- `VisionComparisonHarness` replaced the single `compareOnRoundaboutFixture` method with a
  parametrised `compareOnFixture(String label, Path fixture)` driven by a `@MethodSource
  fixtures()` stream. Three fixture entries:
  - `roundabout`        → `/tmp/roundabout-test.png`
  - `viaduct`           → `/tmp/viaduct-test.png`
  - `highway-exit-ramp` → `/tmp/highway-exit-ramp-test.png`
- Output dir is now per-fixture: `backend/target/vision-comparison/{label}/` — prevents
  report-file collisions when multiple fixtures are present.
- `buildDiffReport` takes `label` + `fixture` args and includes both in the report header.
- Class-level `@SpringBootTest @EnabledIfSystemProperty(named="vision.harness", matches="true")`
  preserved, so default build skips the whole class (no Spring context boot, no surefire
  report file at all).
- Old `compareOnRoundaboutFixture` method removed — the parametrised `[1] roundabout`
  invocation fully replaces it (cleaner single code path).

## Fixture PNG status — DEFERRED for viaduct + highway-exit-ramp

Per `22-CONTEXT.md §Success Criteria §3`, harness-level fixture coverage may be deferred
with written rationale. This plan ships the **wiring** (parametrised test + expected file
paths) but **not the PNG fixtures themselves** for the two new component types.

**Rationale for deferral:**

1. No suitable OSM crop is readily available at time of writing — finding a clean
   top-down PNG of a viaduct-with-crossing or a highway exit ramp with enough visual
   clarity for Claude to recognise requires real image sourcing work that belongs in its
   own plan.
2. Synthetic image generation (drawing fake viaduct / ramp diagrams) would test what
   Claude does with our drawing, not what it does with real-world imagery — low
   diagnostic value.
3. The harness is additive and opt-in: the cost of shipping just the wiring is zero (it
   skips cleanly when fixtures are absent), and the cost of adding a fixture later is
   "drop a PNG at the documented path" — no code change needed.

**When a developer drops a fixture PNG at the expected path and runs the harness:**

```bash
cd backend && ./mvnw -Dvision.harness=true -Dtest=VisionComparisonHarness test
```

...the parametrised entry for that fixture automatically exercises both Phase 20 and
Phase 21 pipelines and writes a diff report to
`backend/target/vision-comparison/{viaduct|highway-exit-ramp}/diff.md`.

## Verification results

### Default suite (no -D flag)

```
Tests run: 308, Failures: 2, Errors: 0, Skipped: 0
```

- Two failures are **pre-existing** (ArchitectureTest.no_generic_exceptions in
  `OsmPipelineService.fetchFromMirrors` and
  `ComponentVisionServiceTest.analyzeImageBytes_disconnectedArms_throwsParseException`)
  — both out of scope for Phase 22.
- `ls target/surefire-reports | grep VisionComparison` → empty. Harness class was not
  executed at all, confirming `@EnabledIfSystemProperty` gate holds.

### Harness-enabled run (`-Dvision.harness=true -Dtest=VisionComparisonHarness`)

```
testsuite ... tests="3" errors="0" skipped="2" failures="0"
  testcase [1] roundabout          — PASS (43.9s, /tmp/roundabout-test.png present on dev box)
  testcase [2] viaduct             — SKIPPED via Assumptions (fixture absent)
  testcase [3] highway-exit-ramp   — SKIPPED via Assumptions (fixture absent)
```

Skip semantics preserved: missing fixture → `TestAbortedException` (skip), never failure.
Per-fixture dir `target/vision-comparison/roundabout/` was created; viaduct and
highway-exit-ramp subdirs will be created automatically the first time their fixture is
dropped in place.

## Phase 22 cumulative test-count delta

| Run                                                 | Before Phase 22 | After Phase 22 | Delta |
|-----------------------------------------------------|-----------------|----------------|-------|
| Default `mvn test` (harness OFF)                    | 289             | 308            | +19   |
| Harness-only `-Dvision.harness=true ... -Dtest=...` | 1 test          | 3 tests        | +2    |

The +19 in the default suite comes entirely from Plans 22-01 / 22-02 (ViaductExpansionTest,
HighwayExitRampExpansionTest, ReverseRoadIdComponentTest extensions, ComponentVisionService
mapping tests, prompt-mentions-new-types test). Plan 22-03 adds **0** to the default
count — the harness stays class-disabled without the flag.

## Deviations from Plan

None. Both tasks executed as written (parametrisation via `@MethodSource`, per-fixture
output dirs, fixture generation deferred with rationale).

## Self-Check: PASSED

- `backend/src/test/java/com/trafficsimulator/integration/VisionComparisonHarness.java` — FOUND, parametrised over 3 fixtures.
- Default `mvn test` surefire reports — no `VisionComparison` entry (class disabled). FOUND as expected (absent).
- Harness-enabled run shows 3 parametrised testcases, 2 skipped via `TestAbortedException`. FOUND.
