---
phase: 25
plan: 01
artefact: wave-0-spike-result
date: 2026-04-26
spike_test: backend/src/test/java/com/trafficsimulator/engine/RingRoadPriorityYieldSpikeTest.java
resolves: RESEARCH.md Open Question Q1 (Pitfall #2 — PRIORITY-yield stalls the ring)
---

# Phase 25 — Wave-0 Spike Result

> **One-line directive for Plan 03 (downstream consumer):**
> `RING-ROAD-INTERSECTION-TYPE: PRIORITY`

Plan 03 must use `IntersectionType.PRIORITY` for the 8 intersections in
`backend/src/main/resources/maps/ring-road.json`. No fallback (`NONE`) and no schema extension
(`straightThrough`) is required.

## Context

Phase 25 RESEARCH.md §"Common Pitfalls" #2 warned that the existing `IntersectionManager`
right-of-way logic might stall a same-angle ring topology because every other vehicle would be
classified as "approaching from the right". That pitfall identified three options:

| Option | Description | Cost |
|--------|-------------|------|
| (a) | Add a new `IntersectionType.NONE_THROUGH` enum value with explicit pass-through semantics | New enum + IntersectionManager branch |
| (b) | Re-use the existing `IntersectionType.NONE` (already short-circuits to `hasVehicleFromRight`) | Zero — both `PRIORITY` and `NONE` reach the same right-of-way branch in `IntersectionManager.canEnterIntersection` (line 96) |
| (c) | Extend `IntersectionConfig` with a `straightThrough: true` flag | Schema change + dispatcher change |

The recommendation in RESEARCH.md was to "try `NONE` first" — but it never validated that
`PRIORITY` itself was broken. This spike covers both.

## Method

`RingRoadPriorityYieldSpikeTest` builds a closed 8-segment ring (each segment 250 m, 2 lanes,
22.2 m/s speed limit) with 8 nodes wired so each intersection has exactly one inbound road and
one outbound road. 8 vehicles are primed at uniform spacing (one per segment, all on lane 0,
position 0, speed 0.8 × speed-limit). 100 ticks of:

1. Minimal physics step (`pos += speed * dt`, clamped to `road.length`)
2. Real `IntersectionManager.processTransfers(network, tick)` — exercises the actual
   `canEnterIntersection` branch including `hasVehicleFromRight`

…are run. Two assertions per case: (a) all 8 vehicles still alive, (b) sum of vehicle positions
> 0 (proving they moved).

Two test cases run the same scenario with `IntersectionType.PRIORITY` and `IntersectionType.NONE`
respectively.

## Result

**Date of run:** 2026-04-26
**Maven command:** `cd backend && mvn -Dtest=RingRoadPriorityYieldSpikeTest test`

| Test case | Outcome |
|-----------|---------|
| `ringWithPriorityDoesNotStall` (IntersectionType.PRIORITY) | **PASS** — 8 vehicles alive after 100 ticks, total advanced distance > 0 |
| `ringWithNoneDoesNotStall_fallbackOption` (IntersectionType.NONE) | **PASS** — 8 vehicles alive after 100 ticks, total advanced distance > 0 |

Both intersection types allow the ring topology to circulate without stalls. The `hasVehicleFromRight`
branch in `IntersectionManager.canEnterIntersection` (line 96-99) does not produce a false-positive
in a ring where every intersection has exactly one inbound road, because the inner stream in
`hasVehicleFromRight` (line 159-163) iterates over `ixtn.getInboundRoadIds()` and each "other"
candidate is filtered out by `otherRoadId.equals(inboundRoadId)` (line 168). With a single inbound
road per node there is no other road to qualify as "from the right" — the stream returns false and
the vehicle is allowed through.

## Why the original concern was unfounded

The pitfall RESEARCH.md anticipated assumed each ring intersection would have multiple inbound
roads (the canonical 4-arm intersection geometry). In the rescoped ring topology (D-11) every node
has exactly one inbound road and one outbound road, so the multi-arm right-of-way logic is never
triggered.

If a future ring scenario ever wires multiple inbound roads per node (e.g. a ring with on/off
ramps), this assumption needs re-validation — the spike test should be re-run with the new
topology before assuming PRIORITY is safe.

## Directive for Plan 03

```
RING-ROAD-INTERSECTION-TYPE: PRIORITY
```

Reasoning:
- `PRIORITY` is the semantic match for an unsignalled junction (vs. `NONE` which is intended for
  geometrically-degenerate "pseudo-junction" pass-throughs).
- Both tested types behave identically on the spike; choosing `PRIORITY` keeps the scenario
  semantically honest and avoids documenting a workaround.
- No schema change required — `IntersectionType.PRIORITY` is already in the enum.
- `RING-02` (80 primed vehicles still present after 100 ticks) is expected to pass with
  `IntersectionType.PRIORITY` and the ring-road.json layout described in CONTEXT.md §D-11.

## Next-step boundaries

- This spike proves the **transfer layer** does not stall the ring. It does NOT cover IDM-driven
  car-following dynamics — those are exercised by Plan 03's `RingRoadTest` (RING-02 / RING-03 /
  RING-04) which uses the full Spring-wired `PhysicsEngine`.
- The spike uses 8 vehicles (one per segment). Plan 03's `ring-road.json` will use 80 vehicles
  per D-11. If the 80-vehicle case stalls (unlikely given the 8-vehicle case has surplus space),
  Plan 03 should re-run this spike at higher density before changing intersection types.
