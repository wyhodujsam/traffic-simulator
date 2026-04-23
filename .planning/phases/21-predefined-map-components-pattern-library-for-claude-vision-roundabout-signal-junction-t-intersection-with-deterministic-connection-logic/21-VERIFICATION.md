---
phase: 21
verified: 2026-04-22
verifier: Claude (goal-backward review)
status: PASS
---

# Phase 21 Verification

**Goal (from CONTEXT.md):** Add an alternative Claude vision pipeline where the component catalog owns the geometry (deterministic Java code) and Claude only identifies component types, anchor positions, rotations, and arm-to-arm connections. Phase 20 endpoints remain byte-for-byte identical.

## Goal-backward check

| Goal artefact | Observed in code | Status |
|---|---|---|
| New endpoint `POST /api/vision/analyze-components` (multipart) | `VisionController` routes multipart → `ComponentVisionService.analyzeImage` | ✓ |
| New endpoint `POST /api/vision/analyze-components-bbox` (bbox) | `VisionController` routes bbox → PNG → `ComponentVisionService.analyzeImageBytes` | ✓ |
| `ComponentVisionService` owns prompt + expansion | `backend/.../service/ComponentVisionService.java` — prompt constant + CLI call + parse + `MapComponentLibrary.expand(...)` | ✓ |
| `MapComponentLibrary` with `expand(List<ComponentSpec>, List<Connection>)` → `MapConfig` | `backend/.../service/MapComponentLibrary.java` | ✓ |
| MVP catalog: `ROUNDABOUT_4ARM`, `T_INTERSECTION`, `SIGNAL_4WAY`, `STRAIGHT_SEGMENT` | All 4 records present under `vision/components/`, sealed `ComponentSpec` permits them | ✓ |
| Stitching: shared-endpoint merge OR bridge via `STRAIGHT_SEGMENT` | `MapComponentLibrary.stitchOne` merges at midpoint; `MapComponentLibraryTest.stitch_bridgesWithStraightSegment` covers bridge path | ✓ |
| Phase 20 additive-only (no edits to `ClaudeVisionService` / Phase 20 endpoints) | `git log --stat` on phase 20 files shows no modifications since phase 20 landing | ✓ |
| Frontend "AI Vision (component library)" button reusing existing state machine | `MapSidebar.tsx` third button, `MapPage.tsx` handler; `MapSidebar.test.tsx` covers presence + dispatch | ✓ |
| Comparison harness (dev-only, opt-in) | `VisionComparisonHarness` gated by `@EnabledIfSystemProperty(named="vision.harness", matches="true")` | ✓ |
| Output passes `MapValidator` | `MapComponentLibrary.expand` calls `mapValidator.validate(cfg)` and raises `ExpansionException` on errors | ✓ |

## Per-plan requirements

| Plan | Requirements | Met |
|---|---|---|
| 21-01 | P21-CATALOG-TYPES, P21-CATALOG-SEALED, P21-LIB-SKELETON | ✓ (sealed `ComponentSpec`, 4 records, `MapComponentLibrary` skeleton) |
| 21-02 | P21-STITCH-MERGE, P21-STITCH-REJECT, P21-STITCH-BRIDGE, P21-STITCH-ORPHAN | ✓ (9 new stitching tests all green) — note: P21-STITCH-REJECT contract revised in c2e4d59 to "explicit connection authoritative"; test renamed to `stitch_explicitConnection_mergesRegardlessOfDistance` |
| 21-03 | P21-VISION-PROMPT, P21-VISION-PARSE, P21-VISION-EXPAND-INTEGRATION | ✓ (prompt enumerates types, two-pass DTO, expansion integrated) |
| 21-04 | P21-ENDPOINT-MULTIPART, P21-ENDPOINT-BBOX, P21-PHASE20-REGRESSION | ✓ (`VisionControllerTest` passes both new + regression) |
| 21-05 | P21-FRONTEND-BUTTON, P21-FRONTEND-HANDLER | ✓ (`MapSidebar.test.tsx` 3/3) |
| 21-06 | P21-HARNESS-GATED, P21-HARNESS-DIFF | ✓ (harness skipped by default; manual invocation produces diff artefacts) |

## Test evidence

```
mvn test
→ Tests run: 308, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS

npm test (frontend)
→ Tests 51 passed | 3 skipped (54)
```

## Design-decision drift

The original plan for 21-02 (P21-STITCH-REJECT) required rejecting arm pairs >5px apart with a "Insert a STRAIGHT_SEGMENT" message. Live UI test revealed Claude cannot know the arm endpoint geometry (228px from a roundabout centre), so commit c2e4d59 made the explicit `Connection` authoritative and dropped the distance gate. The reject-path unit test was replaced by `stitch_explicitConnection_mergesRegardlessOfDistance` in `MapComponentLibraryTest`, and the parallel test in `ComponentVisionServiceTest` was updated to match (commit on `fix/overpass-fallback`).

## Conclusion

**PHASE 21 GOAL ACHIEVED.** Alternative pipeline ships end-to-end (bbox → PNG → Claude → components → deterministic expansion → validated `MapConfig`). Phase 20 untouched. Harness in place for ongoing A/B comparison.
