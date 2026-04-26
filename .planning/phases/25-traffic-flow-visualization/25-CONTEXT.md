---
phase: 25
title: Determinism + KPI foundation (rescoped from "Traffic flow visualization")
type: foundation / v3.0 prerequisite
depends_on: [4, 5, 9]
status: drafted
gathered: 2026-04-26
---

# Phase 25 — Determinism + KPI foundation

## Phase Boundary

Make simulation runs **deterministic** (fixed-seed RNG, scenario duration contract, replay capability) and emit a **structured KPI suite** (throughput, mean delay, queue length, level-of-service per segment + per intersection) so downstream v3.0 phases (CRUD API, headless batch, reward function, LLM agent harness) can score network variants honestly.

Visualisation (space-time diagram, fundamental diagram, ring-road scenario) is a **side output** of the metrics pipeline, not the primary deliverable. The phase succeeds when:

1. The same seed produces a byte-identical tick stream across runs.
2. KPIs are emitted per-tick on the existing STOMP channel and reflect what vehicles actually experienced.
3. The ring-road scenario produces an observable phantom jam from a deterministic perturbation.

**Out of scope** (deferred to follow-up phases per rescope):
- CRUD API for scenario edits → Phase 26
- Headless batch runner → Phase 27
- Reward function → Phase 28
- LLM agent harness → Phase 29

## Locked from rescope text (not gray areas — already decided)

- Fixed-seed RNG via Spring-injected `RandomGenerator` (replaces `Math.random` / `ThreadLocalRandom`).
- `RUN_FOR_TICKS` STOMP command + auto-stop with terminal snapshot.
- Replay capability writes deterministic tick log to disk.
- Per-tick rolling KPI aggregates: throughput, mean delay, 95th-percentile queue length, LOS A–F.
- Per-segment + per-intersection KPI breakdown.
- Fundamental diagram + space-time diagram as side outputs of the KPI pipeline.
- Ring-road scenario: closed loop, uniform initial speed, no spawner, perturbations seed jams.

## Implementation Decisions

### RNG & determinism contract

- **D-01:** Seed source is **scenario JSON `seed` field with optional STOMP `START` command override**. Scenario file is self-contained for reproducible runs; ad-hoc experimentation overrides without editing the JSON.
  - JSON schema: optional integer `seed` at the top level of `MapConfig`.
  - STOMP `START` command DTO gets an optional `seed` (Long). When present, overrides JSON.
  - Override precedence: `command.seed` > `mapConfig.seed` > auto-generated.

- **D-02:** **One master `RandomGenerator` + per-component sub-RNGs** derived via `SplittableRandom.split()` (or `RandomGeneratorFactory` equivalent). Spawn order is fixed and documented so adding a new consumer doesn't reshuffle existing streams.
  - Master RNG instantiated once per simulation start with the resolved seed.
  - Sub-RNGs: `vehicleSpawnerRng`, `intersectionRoutingRng`, `idmNoiseRng` (named in code; spawn order in `SimulationEngine.start()`).
  - Adding a new consumer in a future phase appends a new `split()` call at the END of the spawn list — never inserts in the middle.

- **D-03:** **Inject `RandomGenerator` via Spring constructor injection** into `VehicleSpawner` and `IntersectionManager`. Direct replacement of the 5 existing `ThreadLocalRandom.current()` call sites — no `RandomSource` interface, no static helper.
  - Affected files: `VehicleSpawner.java:141`, `IntersectionManager.java:366,495,500,524`.
  - `RandomGenerator` references are owned by the component (set when simulation starts, replaced when re-seeded).
  - Tests inject a deterministic stub by passing a known-seed `RandomGenerator` to constructors.

- **D-04:** **Auto-generate seed from `System.nanoTime()` and log at INFO** when no seed is configured. Backwards compatible — all 7 existing scenario JSONs continue to run; the generated seed appears in logs and in the replay log so any run can be reproduced.
  - Log line format: `[SimulationEngine] Started with seed=<long> source=<json|command|auto>`.
  - Replay log header includes `seed` and `source` fields.

### KPI suite — definitions, rubrics, delivery

- **D-05:** **Mean delay = per-vehicle (actual − free-flow) computed on despawn**, averaged over a rolling 60-second window of despawned vehicles (matches existing throughput window).
  - `Vehicle` gains `long spawnTick` and a recorded `List<String> roadIdsTraversed` (or accumulated free-flow time, see D-05a).
  - **D-05a:** Free-flow time accumulates **incrementally** as the vehicle enters each road: `freeFlowSeconds += road.length / road.speedLimit`. Avoids storing the full path.
  - On despawn: `actualSeconds = (despawnTick − spawnTick) * tickDt`; `delaySeconds = actualSeconds − freeFlowSeconds`.
  - Mean over rolling 60s of `(despawnTick, delaySeconds)` pairs.

- **D-06:** **Queue threshold is `speed < 0.30 × segment.speedLimit`** (per-segment adaptive). Queue length per segment = max contiguous run of queued vehicles measured **from the segment exit going upstream**, expressed in **meters**.
  - 95th-percentile queue length = p95 over the rolling 60s window of per-tick max-queue-length samples per segment.
  - Per-intersection queue length = max queue length across the inbound segments of that intersection.

- **D-07:** **Density-based LOS A–F, single table for v3.0 MVP.** Same thresholds for highway and arterial — documented as a deliberate simplification for synthetic networks. Future v4.0 OSM phase can split arterial vs highway tables.
  - Thresholds (vehicles per km per lane, computed per segment):
    - A: ≤ 7
    - B: ≤ 11
    - C: ≤ 16
    - D: ≤ 22
    - E: ≤ 28
    - F: > 28
  - Per-intersection LOS = worst (highest letter) LOS of inbound segments.

- **D-08:** **KPIs ride on the existing `/topic/state` STOMP channel** — extend `SimulationStateDto.stats` with a `KpiDto` block + `List<SegmentKpiDto>` + `List<IntersectionKpiDto>`. Network-level KPIs push every tick (20 Hz). Per-segment / per-intersection KPI lists are **sub-sampled every 5 ticks (4 Hz)** and reuse the previous values in between to control payload growth.
  - `KpiDto` (network-level): `throughputVehiclesPerMin`, `meanDelaySeconds`, `p95QueueLengthMeters`, `worstLos` (string A–F).
  - `SegmentKpiDto`: `roadId`, `densityPerKm`, `flowVehiclesPerMin`, `meanSpeedMps`, `p95QueueLengthMeters`, `los`.
  - `IntersectionKpiDto`: `intersectionId`, `inboundQueueLengthMeters`, `worstLos`.
  - Sub-sampling logic in `SnapshotBuilder`: only recompute the per-segment / per-intersection lists when `tick % 5 == 0`; otherwise reuse last computed lists from cache.

### Side-output visualisation + ring-road scenario

- **D-09:** **New collapsible "Diagnostics" panel** rendered below `StatsPanel`. Default collapsed so existing simulation users are not disrupted. Contains two charts: space-time diagram (top) + fundamental diagram (bottom). Both redraw live at 20 Hz from the existing Zustand store.
  - Component path: `frontend/src/components/DiagnosticsPanel.tsx`.
  - Toggle button label: "Show diagnostics" / "Hide diagnostics" — stored in Zustand `uiSlice.diagnosticsOpen`.
  - When collapsed, the underlying canvas elements are not mounted — zero per-tick render cost.

- **D-10:** **Raw HTML5 Canvas for both diagrams**, matching the existing rendering pattern. No new chart library dependency.
  - Space-time diagram: rolling buffer of last `N=600` ticks (= 30 s @ 20 Hz) × per-road vehicle positions, colored by speed (green→red gradient using the same speed scale as the main canvas).
  - Fundamental diagram: rolling 60-second sample of `(density, flow)` points per segment, plotted as a scatter. Sampled once per simulated second (every 20th tick).
  - Axes + legends drawn manually in Canvas — small helper module `frontend/src/rendering/diagramAxes.ts`.

- **D-11:** **Ring-road geometry: 2000 m circumference, 2 lanes, 80 vehicles uniformly spaced.** Density = 20 veh/km/lane = right at LOS D/E boundary so jams emerge under perturbation but free flow is still possible without one.
  - **Deliberately chose 2 lanes over the canonical 1-lane Sugiyama setup** — the user wants lane-change (MOBIL) dynamics included in the baseline, since v3.0 LLM-redesign will operate on multi-lane networks. Single-lane purity is acceptable to lose because the perturbation mechanism (D-12) is deterministic enough on its own.
  - Ring topology in JSON: a single circular road via 8 chord-approximation segments connected end-to-end (no intersections), forming a closed loop. The MapConfig schema already supports closed loops via `nodes` + `roads` referencing the same start node.
  - File: `backend/src/main/resources/maps/ring-road.json`.

- **D-12:** **Perturbation = slow-leader pulse.** At `tick=200`, `vehicleIndex=0` (leftmost spawned vehicle) clamps to `targetSpeed=5 m/s` for `durationTicks=60` (= 3 s), then resumes normal IDM behaviour. Configurable in scenario JSON under a top-level `perturbation` block — extends the existing `MapConfig` schema.
  - Schema:
    ```json
    "perturbation": {
      "tick": 200,
      "vehicleIndex": 0,
      "targetSpeed": 5.0,
      "durationTicks": 60
    }
    ```
  - Implementation hook: `PhysicsEngine.tick()` checks for active perturbation before computing IDM acceleration; if vehicle matches and tick window is active, override desired speed.
  - **No obstacles, no spawn rate jiggle** — keeps ring-road as the cleanest possible KPI baseline.

### Claude's Discretion (deferred area: scenario duration & replay)

The "Scenario duration & replay" gray area was explicitly skipped in gray-area selection. The following defaults are locked for the planner — they follow the rescope text literally and the existing project conventions; no clarification needed before planning.

- **D-13:** **`RUN_FOR_TICKS` runs in wall-clock mode (20 Hz) by default**, respecting the existing `simulationSpeed` multiplier. A separate `RUN_FOR_TICKS_FAST` command (added in this phase) runs as fast as the JVM permits — used for headless batch / replay verification. Both auto-stop with a terminal snapshot.
  - Live observation path stays unchanged from today (existing `@Scheduled(fixedRate = 50)` ticker).
  - Fast path uses a tight loop in a worker thread, bypassing `@Scheduled`, and emits no STOMP frames during the run — only the terminal snapshot.

- **D-14:** **Replay log format = NDJSON** (one tick = one JSON line). Append-only, streamable, greppable.
  - Storage: `target/replays/{seed}-{ISO8601}.ndjson` (gitignored — written to Maven build output, not committed).
  - Header line: `{ "type": "header", "seed": <long>, "source": "<json|command|auto>", "mapId": "<id>", "tickDt": 0.05 }`.
  - Per-tick line: `{ "type": "tick", "tick": <n>, "vehicles": [{ id, roadId, laneIndex, position, speed }, ...] }`.
  - Toggle via Spring property `simulator.replay.enabled` (default `false` to avoid disk fill in casual runs); auto-enabled when `RUN_FOR_TICKS` / `RUN_FOR_TICKS_FAST` is invoked.

- **D-15:** **Replay model = re-run from seed (recompute), not play-back.** The byte-identical guarantee is the contract. The NDJSON log is for diff/audit (compare two runs with same seed → expect identical lines), **not** for animated playback. Reduces scope significantly — no playback UI, no scrubber, no pause-mid-replay.

## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Project-level decisions
- `.planning/PROJECT.md` — Tech stack constraints (Java 17 / Spring Boot 3.3 / React 18 / Canvas / STOMP).
- `.planning/REQUIREMENTS.md` §"Statistics" — STAT-01/02/03 already-validated KPI baseline (avgSpeed, density, throughput).
- `.planning/ROADMAP.md` — Phase 25 rescope text + Strategic Re-prioritisation 2026-04-26 + critical path to v5.0 LLM-redesign MVP.

### Existing code touchpoints (for migration)
- `backend/src/main/java/com/trafficsimulator/engine/VehicleSpawner.java` §line 141 — `ThreadLocalRandom` for IDM ±20% noise; first migration target.
- `backend/src/main/java/com/trafficsimulator/engine/IntersectionManager.java` §lines 366, 495, 500, 524 — `ThreadLocalRandom` for routing decisions; second migration target.
- `backend/src/main/java/com/trafficsimulator/scheduler/TickEmitter.java` §line 45 — `@Scheduled(fixedRate = 50)` 20 Hz wall-clock driver; `RUN_FOR_TICKS` interacts with this.
- `backend/src/main/java/com/trafficsimulator/scheduler/SnapshotBuilder.java` §`computeStats` — current per-tick stats computation; KPI block plugs in here.
- `backend/src/main/java/com/trafficsimulator/dto/StatsDto.java` — extend, don't replace.
- `backend/src/main/java/com/trafficsimulator/dto/SimulationStateDto.java` — add `KpiDto`, `List<SegmentKpiDto>`, `List<IntersectionKpiDto>`.
- `backend/src/main/java/com/trafficsimulator/engine/SimulationEngine.java` — owns simulation start; resolves seed precedence; spawns sub-RNGs.
- `backend/src/main/java/com/trafficsimulator/config/MapConfig.java` — extend with optional `seed` and `perturbation` fields.
- `backend/src/main/resources/maps/*.json` — 7 existing scenarios; ring-road joins as the 8th.

### Frontend touchpoints
- `frontend/src/components/StatsPanel.*` — new `DiagnosticsPanel` mounts below.
- `frontend/src/store/` — Zustand store gains `kpi`, `segmentKpis`, `intersectionKpis` slices and `uiSlice.diagnosticsOpen`.
- `frontend/src/types/` — DTOs mirror new backend types.
- `frontend/src/rendering/` — new `diagramAxes.ts` helper for Canvas axes/legends.

### Domain references
- HCM 2010 (Highway Capacity Manual) — LOS density thresholds for arterials (used as inspiration for D-07; simplified to a single table for MVP).
- Treiber et al. 2000 — Intelligent Driver Model (already implemented in `PhysicsEngine`).
- Sugiyama et al. 2008 — original phantom-jam ring-road experiment (we deviate to 2 lanes; documented in D-11).

## Existing Code Insights

### Reusable Assets
- `StatsDto` + `SnapshotBuilder.computeStats()` — proven per-tick aggregation pattern; KPI block extends it.
- `VehicleSpawner.getThroughput()` — existing 60-second rolling window pattern; reuse the same window for delay + queue.
- `MapConfig` JSON loader (`MapLoader`) — accepts new optional fields without breaking existing scenarios.
- Canvas rendering pattern from `SimulationCanvas` — reuse `requestAnimationFrame` + Zustand subscription for diagrams.
- `SimulationCommand` sealed interface — `RUN_FOR_TICKS`, `RUN_FOR_TICKS_FAST` slot in as new permits.

### Established Patterns
- Lombok `@Data @Builder @NoArgsConstructor @AllArgsConstructor` on every DTO — KPI DTOs follow.
- Constructor injection for Spring components — RNG injection follows.
- `IXxx` interface + `XxxImpl` for testability (e.g. `IPhysicsEngine`, `IVehicleSpawner`) — KPI computation gains `IKpiAggregator` for the same reason.

### Integration Points
- STOMP topic: `/topic/state` already broadcasts `SimulationStateDto` — KPIs ride on this; no new broker config.
- REST: `SimulationController` exposes `GET /api/simulation/status` — could add `GET /api/simulation/kpi/snapshot` later if needed; out of scope for this phase.
- Tests: 308 backend tests provide regression coverage. New tests target byte-identical-tick-stream property + KPI rubric correctness on canned scenarios.

## Specific Ideas

- **Byte-identical tick stream as an integration test.** Spin up the simulation twice with the same seed on the ring-road scenario, compare the NDJSON replay logs line-for-line, assert equality. This is the headline acceptance criterion for the determinism guarantee.
- **Phantom-jam visual sanity check.** After RUN_FOR_TICKS=2000 on `ring-road.json`, the space-time diagram should show a backward-propagating dark band (the jam wave) that is visually distinguishable from green free-flow. Acceptance is "user can see the wave" — formalised in a Playwright smoke that asserts at least one continuous low-speed band of length ≥ 100 m on the canvas at tick 500.
- **LOS as a sanity check on KPI.** At ring-road steady state (before perturbation), all segments should be LOS C or D. After perturbation (tick 250+), at least one segment should hit LOS F. This bounds the LOS table from observed simulation behaviour.

## Deferred Ideas

- **Per-vehicle delay timeseries export** (CSV) — useful for v3.0 reward function (Phase 28), but the reward function will own this.
- **Live KPI overlays on the main simulation canvas** (color-tint segments by LOS) — nice UX touch but adds rendering coupling; defer until LOS proves itself useful in v3.0.
- **Replay scrubber / playback UI** — explicitly deferred per D-15. If v3.0 development reveals a real need, revisit.
- **HCM-faithful split LOS tables (arterial vs highway vs intersection)** — defer to v4.0 when OSM faithful representation lands and segment classification becomes meaningful.
- **Heatmap overlay (`AVIS-01`)** — already in `REQUIREMENTS.md` as Future; LOS-tinted segments would be a partial fulfillment, but full heatmap stays deferred.
- **CRUD scenario editor** — v3.0 Phase 26.
- **Headless batch runner** — v3.0 Phase 27.

---

*Phase: 25-traffic-flow-visualization (directory name retained from pre-rescope; rename optional and not required for v3.0 critical path)*
*Context gathered: 2026-04-26 via /gsd-discuss-phase*
