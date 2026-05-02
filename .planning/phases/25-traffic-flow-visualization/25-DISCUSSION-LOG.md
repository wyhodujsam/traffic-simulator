# Phase 25: Determinism + KPI foundation - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-26
**Phase:** 25-traffic-flow-visualization (rescoped to Determinism + KPI foundation)
**Areas discussed:** RNG & determinism contract, KPI suite — definitions/rubrics/delivery, Side-output viz + ring-road scenario
**Areas skipped (Claude's discretion):** Scenario duration & replay

---

## Gray-area selection

| Area | Selected |
|------|----------|
| RNG & determinism contract | ✓ |
| Scenario duration & replay | (skipped — Claude's discretion) |
| KPI suite — definitions, rubrics, delivery | ✓ |
| Side-output viz + ring-road scenario | ✓ |

---

## RNG & determinism contract

### Q1: Where does the seed come from?

| Option | Description | Selected |
|--------|-------------|----------|
| Scenario JSON `seed` field | Self-contained scenario, JSON-default | |
| STOMP `START` command parameter | Per-run flexibility, scenario stays seed-agnostic | |
| Both — JSON default + command override | JSON sets default, command overrides | ✓ |

**User's choice:** Both — JSON default + command override.
**Notes:** D-01 — precedence: command > JSON > auto-generated.

### Q2: How is RandomGenerator shared?

| Option | Description | Selected |
|--------|-------------|----------|
| One master + per-component sub-RNGs | SplittableRandom.split(), insulates components | ✓ |
| Single shared RandomGenerator bean | Simpler, fragile to draw-count changes | |
| Per-component independent seeds | Independent but harder to reason about | |

**User's choice:** One master + per-component sub-RNGs (recommended).
**Notes:** D-02 — spawn order documented in `SimulationEngine.start()`; new consumers append, never insert.

### Q3: How are existing ThreadLocalRandom call sites migrated?

| Option | Description | Selected |
|--------|-------------|----------|
| Inject RandomGenerator field via Spring | Direct replacement, minimal abstraction | ✓ |
| Wrap behind RandomSource interface | Flexible, more boilerplate | |
| Static helper class | Easiest retrofit, hostile to tests | |

**User's choice:** Inject RandomGenerator field via Spring (recommended).
**Notes:** D-03 — affects 5 call sites in `VehicleSpawner` + `IntersectionManager`.

### Q4: What happens when a scenario has no seed configured?

| Option | Description | Selected |
|--------|-------------|----------|
| Auto-generate from System.nanoTime() and log it | Backwards compatible, generated seed logged | ✓ |
| Fail-loud — require explicit seed | Forces determinism everywhere | |
| Hardcoded default seed (e.g. 42) | Hides the choice, surprises debugging | |

**User's choice:** Auto-generate from System.nanoTime() and log it (recommended).
**Notes:** D-04 — log line `[SimulationEngine] Started with seed=<long> source=<json|command|auto>`; replay log captures it.

---

## KPI suite — definitions, rubrics, delivery

### Q1: How is mean delay computed?

| Option | Description | Selected |
|--------|-------------|----------|
| Per-vehicle (actual − free-flow) on despawn | Honest, reflects experienced delay | ✓ |
| Network-level snapshot (avgSpeed vs speedLimit) | Cheap, biased toward free-flowing cars | |
| Per-vehicle live tracking (every tick) | Most accurate, biggest CPU + payload cost | |

**User's choice:** Per-vehicle on despawn (recommended).
**Notes:** D-05 — free-flow time accumulates incrementally per road entered (D-05a); rolling 60s window of despawn samples.

### Q2: When is a vehicle counted as "queued"?

| Option | Description | Selected |
|--------|-------------|----------|
| speed < 5 m/s (~18 km/h) | Standard HCM-intuition threshold | |
| speed < 30% of segment speedLimit | Adapts to highway vs arterial | ✓ |
| speed < 1 m/s (essentially stopped) | Only dead-stops | |

**User's choice:** speed < 30% of segment speedLimit (user override of recommendation).
**Notes:** D-06 — per-segment adaptive; queue length measured from segment exit going upstream, in meters; p95 over rolling 60s.

### Q3: Which LOS A–F mapping?

| Option | Description | Selected |
|--------|-------------|----------|
| Density-based, single table for v3.0 MVP | Simple, document the simplification | ✓ |
| Speed-based v/c ratio | Conflates flow with capacity | |
| Full HCM 2010 split | Correct, high complexity | |

**User's choice:** Density-based single table (recommended).
**Notes:** D-07 — A≤7, B≤11, C≤16, D≤22, E≤28, F>28 (veh/km/lane); per-intersection LOS = worst inbound segment.

### Q4: How do KPIs reach the frontend?

| Option | Description | Selected |
|--------|-------------|----------|
| Extend SimulationStateDto.stats, push every tick | One channel, sub-sample per-segment lists | ✓ |
| New /topic/kpi at lower rate (1Hz) | Two subscriptions, KPI lags state | |
| REST GET snapshot only | No live KPI, batch-only | |

**User's choice:** Extend SimulationStateDto.stats with KPI block (recommended).
**Notes:** D-08 — network-level KPIs every tick (20Hz); per-segment + per-intersection sub-sampled every 5 ticks (4Hz) with cache reuse.

---

## Side-output viz + ring-road scenario

### Q1: Where do the diagrams live?

| Option | Description | Selected |
|--------|-------------|----------|
| New collapsible "Diagnostics" panel below StatsPanel | Side output, default collapsed, live | ✓ |
| Separate /metrics page with full-screen charts | Heavier, tilts toward primary deliverable | |
| Post-run only — PNG/CSV dump | Simplest frontend, loses live debugging | |

**User's choice:** Collapsible Diagnostics panel below StatsPanel (recommended).
**Notes:** D-09 — Zustand `uiSlice.diagnosticsOpen`; canvases unmount when collapsed → zero per-tick cost.

### Q2: Charting approach?

| Option | Description | Selected |
|--------|-------------|----------|
| Raw HTML5 Canvas | Reuse pattern, zero new deps | ✓ |
| Recharts | Declarative, +50KB, re-render cost | |
| Mixed Canvas + Recharts | Best fit per chart, adds dep | |

**User's choice:** Raw HTML5 Canvas (recommended).
**Notes:** D-10 — `frontend/src/rendering/diagramAxes.ts` helper for axes/legends.

### Q3: Ring-road geometry?

| Option | Description | Selected |
|--------|-------------|----------|
| 1000m, 1 lane, 30 vehicles | Sugiyama-canonical, single-lane purity | |
| 2000m, 2 lanes, 80 vehicles | Lane-change dynamics included | ✓ |
| 500m, 1 lane, 15 vehicles | Compact, wave wraps fast | |

**User's choice:** 2000m, 2 lanes, 80 vehicles (user override of recommendation).
**Notes:** D-11 — deliberate departure from Sugiyama purity; user wants MOBIL lane-change dynamics in the baseline because v3.0 LLM-redesign operates on multi-lane networks. Documented in CONTEXT.md.

### Q4: Perturbation mechanism?

| Option | Description | Selected |
|--------|-------------|----------|
| Slow-leader pulse (vehicle 0 → 5 m/s for 60 ticks at tick 200) | Deterministic, no obstacles | ✓ |
| No perturbation — IDM noise alone | Cleanest, slow onset | |
| Brief obstacle injection at tick 200 | Reliable, conflates with Phase 6 | |

**User's choice:** Slow-leader pulse (recommended).
**Notes:** D-12 — configurable `perturbation` block in scenario JSON; hook in `PhysicsEngine.tick()` overrides desired speed during the active window.

---

## Claude's Discretion (deferred area: Scenario duration & replay)

User explicitly skipped this gray area. Claude's defaults locked in CONTEXT.md as D-13/D-14/D-15:

- D-13: `RUN_FOR_TICKS` = wall-clock 20Hz with speed multiplier; new `RUN_FOR_TICKS_FAST` runs as fast as JVM permits, no STOMP frames during run, only terminal snapshot.
- D-14: Replay log = NDJSON (one tick per line), header + per-tick lines, written to `target/replays/{seed}-{ISO8601}.ndjson` (gitignored), Spring-property-toggleable.
- D-15: Replay model = re-run from seed (recompute), NOT play-back animation. NDJSON is for diff/audit only.

---

## Deferred Ideas (captured during discussion)

- Per-vehicle delay timeseries CSV export — defer to v3.0 reward function (Phase 28)
- Live KPI canvas overlay (LOS-tinted segments) — defer until LOS proven useful
- Replay scrubber / playback UI — explicitly deferred per D-15
- HCM-faithful split LOS tables — defer to v4.0 OSM phase
- Full heatmap overlay (AVIS-01) — stays deferred
- CRUD scenario editor — Phase 26
- Headless batch runner — Phase 27
