# Phase 22: Extend component library — viaduct + highway exit ramp

**Gathered:** 2026-04-14
**Status:** Ready for planning
**Source:** Conversation + Phase 21 patterns

## Background

Phase 21 landed a component-library vision pipeline with 4 types (`ROUNDABOUT_4ARM`, `SIGNAL_4WAY`, `T_INTERSECTION`, `STRAIGHT_SEGMENT`). Live test (Serock bbox) produced 29 roads / 14 intersections, simulation runs. Pattern is proven. Phase 22 adds two more component types to cover more realistic OSM imagery.

## Goal

Add two component records to the Phase 21 catalog — `VIADUCT` and `HIGHWAY_EXIT_RAMP` — following the exact same patterns (sealed interface record, `expand(ExpansionContext)`, arm registration, `_in`/`_out` naming, `MapValidator`-clean output).

## Locked Decisions

1. **Additive only.** No edits to Phase 20 code or the 4 existing Phase 21 component records.
2. **VIADUCT semantics:**
   - Two through-roads crossing at different heights.
   - Lower road connects `south` ↔ `north` (bidirectional: two roads, `_in` / `_out` naming).
   - Upper road connects `west` ↔ `east` (bidirectional pair).
   - **No shared node at the crossing point** — physics engine already handles "two roads passing near each other with no intersection" correctly.
   - Arm endpoints: `center + dir * APPROACH_LEN` (same convention as T_INTERSECTION).
   - No `IntersectionConfig` emitted — the crossing has no junction control.
3. **HIGHWAY_EXIT_RAMP semantics:**
   - Three arms: `mainIn` (upstream highway), `mainOut` (downstream highway continuation), `rampOut` (exit ramp).
   - One PRIORITY intersection node at the split point where `mainIn` branches into `mainOut` + `rampOut`.
   - Main-line through traffic has priority (via existing PRIORITY intersection semantics).
   - Emit road ids `r_main_in`, `r_main_out`, `r_ramp_out` so `reverseRoadId` stays sane.
   - Default `laneCount`: `mainIn` / `mainOut` = 2, `rampOut` = 1 (highway convention — Claude can override via tags if the prompt asks for per-arm laneCount).
4. **Prompt update:** enumerate the 2 new types in `ComponentVisionService.ANALYSIS_PROMPT` with arm names, field schemas, and 1-2 worked examples. Keep existing 4 types unchanged.
5. **Two-pass DTO mapping:** extend `ComponentSpecDto.fromDto` switch for the 2 new types.
6. **Harness fixtures (optional but in scope):** add at least one new fixture image to `VisionComparisonHarness` or as a separate parametrised entry so we can regression-test the new components. Fixture can be a synthetic drawing (simpler than finding/cropping real OSM).
7. **No frontend changes** — existing "AI Vision (component library)" button covers the new types automatically.

## Scope (IN)

- `backend/.../vision/components/Viaduct.java` — new record implementing `ComponentSpec`.
- `backend/.../vision/components/HighwayExitRamp.java` — new record implementing `ComponentSpec`.
- Unit tests per component (2 new `*ExpansionTest.java` files in `backend/src/test/java/.../vision/components/`) covering: node count, road id naming, `_in`/`_out` pair consistency, MapValidator-clean output, arm registration round-trip.
- `MapComponentLibrary` — no code edits needed (the `ComponentSpec` sealed-interface dispatch already delegates via `component.expand(ctx)`). Update only if the switch needs new branches.
- `ComponentSpecDto` — extend switch for 2 new types.
- `ComponentVisionService.ANALYSIS_PROMPT` — enumerate new types.
- `ComponentVisionServiceTest` — add 2 two-pass DTO→record mapping tests (one per new type).
- `VisionComparisonHarness` fixture coverage — extend the existing single-fixture test OR add a second gated test to cover the new components.

## Scope (OUT)

- Engine physics changes (VIADUCT explicitly relies on existing "no shared node = no interaction" behaviour).
- Frontend changes.
- Additional component types beyond these two.
- Changing Phase 21 prompts or stitching behaviour.

## Claude's Discretion

- Exact `APPROACH_LEN` value for new components (match T_INTERSECTION or pick per-component — document in javadoc).
- Whether `VIADUCT` stores two separate roads or emits them via two internal `StraightSegment`-like helpers (probably direct emission; simpler).
- Whether harness adds a new fixture file or reuses the existing one for assertion-only extensions.
- Whether to add an integration test that stitches a `VIADUCT` + two `ROUNDABOUT_4ARM` into one map (recommended — catches id-collision regressions).

## Success Criteria

1. `mvn test` shows +N green tests covering both new components (target: ≥8 unit tests total).
2. `reverseRoadId` contract test covers maps that include VIADUCT and HIGHWAY_EXIT_RAMP (no `_in` or `_out` substring leaks).
3. `VisionComparisonHarness` (opt-in) can process an image containing a viaduct or exit ramp and emit a valid MapConfig — OR we explicitly defer harness-level fixture coverage to a follow-up with rationale.
4. Existing 289 tests remain green; pre-existing `ArchitectureTest` violation stays carried over (out-of-scope).
5. `ComponentVisionService.ANALYSIS_PROMPT` mentions both new types; a unit test asserts this.

## References

- `backend/src/main/java/com/trafficsimulator/vision/components/TIntersection.java` — closest template for `Viaduct` (3→2 arms of unrelated through-traffic).
- `backend/src/main/java/com/trafficsimulator/vision/components/SignalFourWay.java` — template for arm endpoint math.
- `backend/src/main/java/com/trafficsimulator/vision/components/StraightSegment.java` — example of a component with only 2 arms.
- `backend/src/main/java/com/trafficsimulator/engine/IntersectionGeometry.java` — `reverseRoadId` `_in`→`_out` rule; all emitted ids must satisfy.
- `backend/src/test/java/com/trafficsimulator/vision/components/TIntersectionExpansionTest.java` — test template.

## Open Questions (defer to planner)

- Should VIADUCT's two roads have `lateralOffset` set to visually stack them on canvas at different heights? Default `0` means they overlap visually. Recommended: `+14` on upper road for visual separation.
- HIGHWAY_EXIT_RAMP: how does Claude express ramp angle? Option A: implicit (ramp always 45° CCW from mainOut). Option B: `rampAngleDeg` field on DTO. Recommend A for MVP.
