# Phase 21: Predefined map components — pattern library for Claude vision

**Gathered:** 2026-04-13
**Status:** Ready for planning
**Source:** Conversation context (additive feature decision after Phase 20 vision iteration)

## Background

Phase 20 introduced two free-form Claude vision endpoints:
- `POST /api/vision/analyze` — analyse uploaded JPEG/PNG, return MapConfig
- `POST /api/vision/analyze-bbox` — compose static OSM PNG from bbox, then run vision

In iterative testing on a real-world roundabout image, the free-form pipeline produced topologically wrong output multiple times:
1. **Iter 0:** flat 4-way star instead of ring (no geometry guidance in prompt)
2. **Iter 1:** ring nodes correct, but engine treated U-turn as legal exit (no `_in`/`_out` naming)
3. **Iter 2:** circulating, but `laneCount=2` everywhere instead of 1 (rural single-lane road)

Each iteration required a prompt patch and a retest. The root cause is that **Claude is asked to invent geometry**, which is high-variance and easy to get wrong in subtle ways that only manifest at sim runtime.

## Goal

Add an **alternative** vision pipeline (Phase 20 endpoints continue to exist, unchanged) where:

- A **component catalog** owns the geometry (deterministic Java code).
- Claude only identifies **component types**, **anchor positions**, **rotations**, and **arm-to-arm connections**.
- Backend deterministically expands the recognised components into a full `MapConfig`.
- Both pipelines stay live so we can A/B compare on the same input image.

## Locked Decisions

1. **Additive only.** Do NOT modify `ClaudeVisionService.analyzeImage` / `analyzeImageBytes` or `VisionController.analyze` / `analyzeBbox`. Phase 20 endpoints stay byte-for-byte identical.
2. **New endpoint:** `POST /api/vision/analyze-components` (multipart image) — same response contract (`MapConfig`).
3. **New service:** `ComponentVisionService` (or similar) owns the new prompt + the deterministic expansion.
4. **New service:** `MapComponentLibrary` (or similar) holds the component catalog and exposes `expand(List<ComponentSpec>) → MapConfig`.
5. **Initial component catalog (MVP):**
   - `ROUNDABOUT_4ARM` — mirrors `roundabout.json` (4 ring nodes, 4 approach `_in`, 4 departure `_out`, 4 ring roads CCW, 4 ROUNDABOUT intersections).
   - `T_INTERSECTION` — 3 arms meeting at a PRIORITY junction.
   - `SIGNAL_4WAY` — mirrors `four-way-signal.json`.
   - `STRAIGHT_SEGMENT` — connector between two component arms (length parameter from Claude).
   - More components can be added later — design must make adding one cheap.
6. **Connection model:** Each component exposes named arms (`north`, `east`, …). Claude returns connections as `(componentA.armX) ↔ (componentB.armY)`. Backend stitches them by adding a `STRAIGHT_SEGMENT` between or merging shared endpoint nodes.
7. **Frontend:** add a third button next to "AI Vision (from bbox)" labelled e.g. `"AI Vision (component library)"`. Calls the new endpoint with the same image-or-bbox flow as the existing button (reuse the bbox-to-PNG composer from Phase 20). User decides per request which pipeline to try.
8. **Comparison harness:** add a small dev-only utility (script or test) that takes one fixture image, hits both endpoints, and writes a side-by-side diff (road count, intersection count, types histogram, ring-roads detected, etc.) so we can quantify which pipeline produces a more correct map.
9. **Testing fixture:** reuse `/tmp/roundabout-test.png` (the user's screenshot of the Serock-area roundabout) as the canonical regression image.
10. **Output validation:** the deterministic expansion must produce a `MapConfig` that passes `MapValidator` — same gate as Phase 20 outputs.

## Scope (what's IN)

- New REST endpoint, service, component catalog, expansion logic.
- New Claude prompt that asks for component-level identification, NOT full graph.
- Frontend button + handler reusing existing MapPage state machine.
- Comparison harness (dev tool, not user-facing).
- Backend tests for: component expansion → valid MapConfig; controller wiring (mocked vision); fixture image roundtrip.

## Scope (what's OUT)

- Replacing Phase 20 endpoints (explicitly NOT happening — the whole point is A/B).
- Auto-selecting between pipelines (user picks via button for now).
- New component types beyond the MVP catalog (can be added in follow-up phases).
- Persistent component-library config (catalog can be hardcoded in Java for MVP).
- Multi-component composition optimisation (e.g. shared-node detection beyond simple endpoint match).

## Claude's Discretion

- Exact Java package layout (under `service/` and/or `vision/components/`).
- Whether components are records, sealed interfaces, or a registry pattern.
- How `STRAIGHT_SEGMENT` length is parameterised (Claude estimates px? we infer from anchor distance? both?).
- Frontend button styling / wording — match existing `MapSidebar` button conventions.
- Prompt phrasing — must explicitly enumerate the catalog and explain that ONLY those types are valid.

## Success Criteria

- `POST /api/vision/analyze-components` returns `MapConfig` for the user's roundabout fixture image, with topology equivalent to the predefined `roundabout.json` (4 ring nodes, 4 approach + 4 departure roads, all `_in`/`_out` naming, 4 ROUNDABOUT intersections, single lane each).
- `POST /api/vision/analyze` and `/analyze-bbox` continue to work unchanged (same tests pass).
- Frontend offers a third button; clicking it routes through the new endpoint and renders a working roundabout in `/`.
- Comparison harness produces a clear textual diff between both pipelines on the same fixture.

## Constraints / References

- Predefined catalog basis: `backend/src/main/resources/maps/roundabout.json`, `four-way-signal.json` (use as templates for `ROUNDABOUT_4ARM` / `SIGNAL_4WAY` expansion).
- Engine routing convention: `IntersectionGeometry.reverseRoadId` does string `_in`→`_out` heuristic. Component expansion MUST emit ids that satisfy this convention (same lesson from Phase 20 iter 2).
- Phase 20 prompt lives in `ClaudeVisionService.ANALYSIS_PROMPT` — read it for context but don't modify.
- Image composition for bbox flow: `OsmStaticMapService.composeBboxPng` — reuse for the new endpoint's bbox variant if added.

## Open Questions (defer to planner)

- Does the new endpoint take only `multipart image`, or do we also expose `analyze-components-bbox` from day one? Recommend: yes, mirror Phase 20's pair so the frontend doesn't branch on flow.
- How granular is the comparison harness? Recommend: dev script in `backend/src/test/.../tools/` runnable via `mvn exec:java`, dumping JSON+stats to `target/vision-comparison/`.
