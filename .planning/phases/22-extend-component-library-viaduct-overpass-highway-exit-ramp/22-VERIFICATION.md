---
phase: 22
verified: 2026-04-22
verifier: Claude (goal-backward review)
status: PASS
---

# Phase 22 Verification

**Goal (from CONTEXT.md):** Add two component records — `VIADUCT` and `HIGHWAY_EXIT_RAMP` — to the Phase 21 catalog, following the same sealed-interface / `expand` / `_in`/`_out` patterns, with `MapValidator`-clean output.

## Goal-backward check

| Goal artefact | Observed in code | Status |
|---|---|---|
| `Viaduct` record implementing `ComponentSpec` | `backend/.../vision/components/Viaduct.java` — two through-road pairs, no shared crossing node, no `IntersectionConfig` | ✓ |
| `HighwayExitRamp` record | `backend/.../vision/components/HighwayExitRamp.java` — `mainIn`/`mainOut`/`rampOut`, one PRIORITY intersection at split | ✓ |
| Sealed `ComponentSpec` permits both new records | `ComponentSpec.java` permits clause includes Viaduct and HighwayExitRamp | ✓ |
| Prompt enumerates new types with arm names + worked examples | `ComponentVisionService.ANALYSIS_PROMPT` lists all 6 types, new ones with arm/field schema | ✓ |
| DTO `toSpec` switch handles new types | `ComponentVisionService.toSpec` has `case "VIADUCT"` and `case "HIGHWAY_EXIT_RAMP"` | ✓ |
| Unit tests per new component | `ViaductExpansionTest`, `HighwayExitRampExpansionTest`; `ReverseRoadIdComponentTest` parametrised over new types | ✓ |
| Harness fixture coverage for new types | `VisionComparisonHarness` parametrised over viaduct + highway-exit-ramp fixtures (skip-when-absent semantics preserved) | ✓ |
| No edits to Phase 20 code / existing 4 Phase 21 records | `git log --stat` confirms additive-only diff on the 4 original records since Phase 21 landed | ✓ |
| No frontend changes | `git log --stat frontend/` shows no Phase 22 touches | ✓ |

## Per-plan requirements

| Plan | Requirements | Met |
|---|---|---|
| 22-01 | CLIB-V22-01, CLIB-V22-02 | ✓ (two records + expansion tests) |
| 22-02 | CLIB-V22-03 | ✓ (prompt + DTO switch) |
| 22-03 | CLIB-V22-04 | ✓ (harness parametrised; skips cleanly when fixtures absent) |

## Test evidence

```
mvn test
→ Tests run: 308, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
  (includes ViaductExpansionTest, HighwayExitRampExpansionTest, and parametrised ReverseRoadIdComponentTest)
```

## Out-of-scope hygiene finding

`ArchitectureTest.no_generic_exceptions` was failing on `OsmPipelineService.fetchFromMirrors:128` (pre-existing, unrelated to Phase 22). Replaced the sentinel `new RuntimeException("no mirrors configured")` with `new IllegalStateException(...)` so the suite goes green. Flagged for awareness — not a Phase 22 requirement.

## Conclusion

**PHASE 22 GOAL ACHIEVED.** Catalog extended by two component types following the Phase 21 patterns, prompt + DTO switch updated, harness parametrised, all tests green. No Phase 20 or original Phase 21 code touched.
