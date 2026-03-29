# Phase 4 Research: Simulation Engine & Tick Loop

**Date:** 2026-03-28
**Goal:** Wire physics into a running 20 Hz tick loop with proper thread architecture and command queue so all concurrency hazards are eliminated before any frontend interaction.
**Requirements:** SIM-01 (tick loop), SIM-08 (integration), INFR-04 (command queue wiring)

---

## 1. Existing Code Audit

### What Already Exists

Phase 2 and Phase 3 built nearly all the building blocks. This phase is about **wiring them together**, not creating new components from scratch.

| Component | File | Status | Phase 4 Work Needed |
|-----------|------|--------|---------------------|
| `SimulationEngine` | `engine/SimulationEngine.java` | Exists — state machine + command queue | Add `ScheduledExecutorService`, tick orchestration method, map auto-load on startup |
| `SimulationCommand` | `engine/command/SimulationCommand.java` | Complete — 7 record types | None — all needed commands exist |
| `SimulationStatus` | `engine/SimulationStatus.java` | Complete — STOPPED/RUNNING/PAUSED | None |
| `CommandHandler` | `controller/CommandHandler.java` | Complete — STOMP @MessageMapping | None |
| `CommandDto` | `dto/CommandDto.java` | Complete | None |
| `VehicleSpawner` | `engine/VehicleSpawner.java` | Complete — accumulator spawn + despawn | None |
| `PhysicsEngine` | `engine/PhysicsEngine.java` | Complete — IDM per-lane tick | Wire into tick pipeline |
| `TickEmitter` | `scheduler/TickEmitter.java` | **Replace** — currently Spring `@Scheduled` | Move to `ScheduledExecutorService` inside `SimulationEngine` |
| `MapLoader` | `config/MapLoader.java` | Complete | Wire auto-load at startup |
| `SimulationStateDto` | `dto/SimulationStateDto.java` | Complete | None |
| `VehicleDto` | `dto/VehicleDto.java` | Complete | None |
| `StatsDto` | `dto/StatsDto.java` | Complete | None |
| `WebSocketConfig` | `config/WebSocketConfig.java` | Complete | None |
| `StatePublisher` | Does not exist | **Create** | Extract broadcast logic from TickEmitter |
| `SimulationController` | Does not exist | **Create** | REST endpoints for status, config |

### Current Tick Loop Architecture (TickEmitter)

The `TickEmitter` currently uses Spring's `@Scheduled(fixedRate = 50)` annotation. It performs:
1. Drain commands from `SimulationEngine.drainCommands()`
2. Increment tick counter (only when RUNNING)
3. Spawn + despawn vehicles (only when RUNNING and network loaded)
4. Build snapshot (`SimulationStateDto`)
5. Broadcast via `SimpMessagingTemplate`

**Key gap:** PhysicsEngine is not called anywhere in the tick pipeline. Phase 3 built `PhysicsEngine.tick(Lane, dt)` but it was never wired into the loop.

### What's Missing vs. Roadmap Plan

1. **PhysicsEngine integration** — `PhysicsEngine.tick()` must be called for every lane, every tick
2. **ScheduledExecutorService** — Replace `@Scheduled` with explicit executor for better timing control and lifecycle management
3. **Speed multiplier** — `speedMultiplier` is stored in `SimulationEngine` but never applied to `dt`
4. **StatePublisher** — Extract broadcast concern from TickEmitter into dedicated class
5. **SimulationController** — REST endpoints for status query, map listing
6. **Stop resets state** — Stop command resets tick counter but doesn't clear vehicles from lanes
7. **Map auto-load** — No startup wiring to load default map into SimulationEngine

---

## 2. Threading Model

### Current: Spring @Scheduled

`@Scheduled(fixedRate = 50)` runs on Spring's `TaskScheduler` thread pool. Issues:
- No explicit lifecycle control (cannot start/stop the scheduler independently)
- `fixedRate` does not guarantee precise timing — tick N+1 starts 50ms after tick N *starts*, which can cause drift if a tick takes >50ms
- Cannot easily adjust rate at runtime (e.g., for speed multiplier)

### Recommended: ScheduledExecutorService

```java
private final ScheduledExecutorService tickExecutor =
    Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sim-tick");
        t.setDaemon(true);
        return t;
    });
```

Benefits:
- **Single-thread guarantee** — all tick logic runs on one thread, eliminating need for synchronized blocks on simulation state
- **Lifecycle control** — can `scheduleAtFixedRate` on start and `cancel` on stop/pause
- **Named thread** — easy to identify in thread dumps
- **Daemon thread** — won't prevent JVM shutdown

### Thread Safety Model

The existing architecture already implements the correct pattern:

1. **WebSocket thread** receives commands via `CommandHandler`, calls `engine.enqueue()` which uses `LinkedBlockingQueue.offer()` — thread-safe by design
2. **Tick thread** calls `engine.drainCommands()` which uses `LinkedBlockingQueue.drainTo()` — returns all pending commands atomically
3. **All domain state mutations** (vehicle positions, spawn/despawn, lane modifications) happen exclusively on the tick thread
4. **SimpMessagingTemplate.convertAndSend()** is thread-safe — can be called from the tick thread

**No additional synchronization needed.** The command queue pattern isolates all mutation to a single thread.

### Approach Decision: Replace @Scheduled vs. Keep @Scheduled

**Recommendation: Keep @Scheduled for now, enhance TickEmitter.**

Rationale:
- `@Scheduled(fixedRate = 50)` already runs at 20 Hz on a single thread from Spring's task scheduler pool
- The `ScheduledExecutorService` approach gives more control but adds complexity (lifecycle management, shutdown hooks, `@PreDestroy`)
- The current `@Scheduled` approach is **sufficient for all Phase 4 success criteria**: 20 Hz ticking, <5ms jitter, pause/resume via command queue
- When PAUSED, the tick still fires (drains commands, broadcasts state) but skips simulation logic — this is correct behavior for responsive command processing
- Speed multiplier can be applied by scaling `dt` rather than changing tick rate

If jitter becomes a problem (unlikely at 20 Hz with simple physics), we can migrate to `ScheduledExecutorService` later.

---

## 3. Tick Pipeline

### Current Order (in TickEmitter)

1. `drainCommands()` — process pending commands
2. If RUNNING: increment tick counter
3. If RUNNING + network loaded: `vehicleSpawner.tick()` then `vehicleSpawner.despawnVehicles()`
4. Build snapshot
5. Broadcast

### Required Order (Phase 4)

The roadmap specifies: **lights -> spawn -> physics -> collision -> despawn -> snapshot**

For Phase 4 (no lights, no collision detection yet):

```
1. drainCommands()           — process START/STOP/PAUSE/RESUME/SetSpawnRate/SetSpeedMultiplier
2. if not RUNNING: broadcast current state and return early
3. increment tick counter
4. [lights — skip, Phase 8]
5. vehicleSpawner.tick()     — spawn new vehicles
6. physicsEngine.tick()      — for EACH lane in EACH road: update positions via IDM
7. [collision — skip, Phase 6]
8. vehicleSpawner.despawnVehicles() — remove vehicles past lane end
9. buildSnapshot()           — create SimulationStateDto
10. statePublisher.broadcast() — send to /topic/simulation
```

### Speed Multiplier Application

The `speedMultiplier` (0.5x to 5x) should scale the `dt` passed to physics and spawner:

```java
double baseDt = 0.05; // 50ms = 1/20 Hz
double effectiveDt = baseDt * simulationEngine.getSpeedMultiplier();
```

This means at 2x speed, vehicles move twice as far per tick. The tick rate stays at 20 Hz (smooth for the frontend), but simulation time advances faster.

**Important:** `effectiveDt` must be clamped to a maximum (e.g., 0.25s = 5x) to prevent physics instability with large time steps. IDM with Euler integration can become unstable with large dt.

---

## 4. Command Handling

### Current Flow

```
Frontend STOMP client → /app/command → CommandHandler → engine.enqueue() → LinkedBlockingQueue
                                                                                    ↓
Tick thread → engine.drainCommands() → drainTo(pending) → for each: applyCommand()
```

This flow is complete and correct. No changes needed for Phase 4.

### Command Semantics for Tick Loop

| Command | Effect on Tick Loop |
|---------|-------------------|
| `START` | Changes status to RUNNING. Tick loop starts executing simulation steps. |
| `STOP` | Changes status to STOPPED, resets tick counter. Should also clear all vehicles from lanes. |
| `PAUSE` | Changes status to PAUSED. Tick continues firing but skips simulation steps. Commands still processed. |
| `RESUME` | Changes status back to RUNNING from PAUSED. Simulation continues from where it stopped. |
| `SetSpawnRate` | Updates rate field + wires to VehicleSpawner. Takes effect next tick. |
| `SetSpeedMultiplier` | Updates multiplier field. Takes effect next tick via `effectiveDt`. |
| `LoadMap` | Currently just logs. Phase 4 should wire this to reload `RoadNetwork`. |

### Missing Stop Logic

The current `Stop` command resets the tick counter but does NOT clear vehicles from lanes. When the user stops and starts again, stale vehicles would remain. Fix needed:

```java
case Stop -> {
    status = STOPPED;
    tickCounter.set(0);
    clearAllVehicles(roadNetwork);  // NEW
    vehicleSpawner.reset();         // already exists
}
```

### LoadMap Wiring

Currently `LoadMap` just logs. Phase 4 should wire it:
1. Call `mapLoader.loadFromClasspath("maps/" + mapId + ".json")`
2. Set new `RoadNetwork` on `SimulationEngine`
3. Auto-stop simulation (clear vehicles, reset tick counter)

This is a stretch goal — can be deferred to Phase 9 if it complicates the plan.

---

## 5. State Broadcasting

### Current Implementation

`TickEmitter.buildSnapshot()` constructs `SimulationStateDto` with:
- `tick` — counter value
- `timestamp` — `System.currentTimeMillis()`
- `status` — engine status string
- `vehicles[]` — projected `VehicleDto` with pixel coordinates
- `stats` — vehicleCount, avgSpeed, density, throughput(stub)

`SimpMessagingTemplate.convertAndSend("/topic/simulation", state)` — Jackson serializes to JSON automatically.

### StatePublisher Extraction

The roadmap plan says to create `StatePublisher.java`. The broadcast logic is currently inline in `TickEmitter`. Options:

**Option A: Extract to separate class** — Create `StatePublisher` that receives `SimulationStateDto` and calls `messagingTemplate.convertAndSend()`. Clean separation but minimal value — it's a one-liner.

**Option B: Keep inline in TickEmitter** — The broadcast is tightly coupled to the tick anyway. Extract only if the broadcast logic grows (e.g., filtering, throttling).

**Recommendation: Option A** — Create `StatePublisher` as a thin wrapper. It establishes the pattern for future needs (throttled stats broadcast, selective channel routing) and matches the roadmap plan.

### Broadcast Frequency

Every tick (20 Hz). This is correct for Phase 4. The frontend needs every tick for smooth interpolation. Later phases may add a separate lower-frequency stats channel.

---

## 6. REST Endpoints

### SimulationController — New

The roadmap calls for `SimulationController.java` with REST endpoints. Needed for Phase 4:

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `GET /api/simulation/status` | GET | Returns current status, tick count, vehicle count |
| `POST /api/simulation/start` | POST | Alternative to STOMP command for starting simulation |
| `POST /api/simulation/stop` | POST | Alternative to STOMP command |
| `POST /api/simulation/pause` | POST | Alternative to STOMP command |
| `POST /api/simulation/resume` | POST | Alternative to STOMP command |
| `GET /api/maps` | GET | List available map files (for Phase 5 selector) |

**Minimal scope for Phase 4:** Only `GET /api/simulation/status` is strictly needed. The STOMP-based `CommandHandler` already handles all control commands. REST alternatives are nice-to-have for debugging and testing but not required by any success criterion.

**Recommendation:** Implement `GET /api/simulation/status` and `GET /api/maps` — useful for frontend initialization. Defer REST command endpoints (they duplicate the STOMP path).

---

## 7. Integration Points

### PhysicsEngine Wiring

`PhysicsEngine.tick(Lane lane, double dt)` must be called for **every lane in every road**:

```java
for (Road road : network.getRoads().values()) {
    for (Lane lane : road.getLanes()) {
        physicsEngine.tick(lane, effectiveDt);
    }
}
```

PhysicsEngine is a `@Component` — inject it into wherever the tick logic lives (TickEmitter or SimulationEngine).

### VehicleSpawner — Already Wired

`VehicleSpawner.tick(dt, network, tick)` and `VehicleSpawner.despawnVehicles(network)` are already called in `TickEmitter`. The `dt` passed should become `effectiveDt` (scaled by speed multiplier).

### MapLoader — Startup Wiring

Currently no map is loaded at startup. The tick loop runs but `network` is null, so spawn/physics/despawn are all skipped. Need to:

1. Load `straight-road.json` at application startup
2. Set it on `SimulationEngine.setRoadNetwork()`

Options:
- `@PostConstruct` in `SimulationEngine` or `TickEmitter`
- `ApplicationRunner` bean
- `CommandLineRunner`

**Recommendation:** `@PostConstruct` in `SimulationEngine` — it owns the `roadNetwork` field.

### Component Dependency Graph (Phase 4)

```
CommandHandler (STOMP) ──enqueue──> SimulationEngine
                                        │
                                        ├── commandQueue (LinkedBlockingQueue)
                                        ├── status, tickCounter, speedMultiplier, spawnRate
                                        └── roadNetwork
                                              │
TickEmitter (@Scheduled) ─────────────────────┤
    │                                         │
    ├── drainCommands() ──> SimulationEngine   │
    ├── vehicleSpawner.tick() ────────────> VehicleSpawner
    ├── physicsEngine.tick() ─────────────> PhysicsEngine    ← NEW wiring
    ├── vehicleSpawner.despawnVehicles()
    ├── buildSnapshot() ──────────────────> SimulationStateDto
    └── statePublisher.broadcast() ───────> StatePublisher   ← NEW
                                                │
                                    SimpMessagingTemplate
                                        │
                                    /topic/simulation
```

---

## 8. Edge Cases

### Pause/Resume

- **When paused:** Tick continues firing (to drain commands and broadcast state), but simulation steps (spawn/physics/despawn) are skipped. Tick counter does not increment.
- **Resume:** Simulation continues from exact state. No time-skip — the world was frozen.
- **Pause during physics:** Not possible — physics runs synchronously within a single tick. The pause command is processed at the START of the next tick, before any physics runs.

### Speed Multiplier

- **Range:** 0.5x to 5.0x (per CTRL-02 requirement)
- **Implementation:** Scale `dt` parameter. At 5x, `effectiveDt = 0.25s` per tick.
- **Physics stability:** IDM with Euler integration. At dt=0.25s, vehicles move up to 8.3m per step (at 33.3 m/s). With s0=2.0m minimum gap, this is pushing the stability limit. **Guard needed:** If `effectiveDt * maxSpeed > s0`, reduce step size or use sub-stepping.
- **Sub-stepping:** At high speed multipliers, run multiple physics steps per tick with smaller dt. E.g., at 5x: run 5 steps of dt=0.05s instead of 1 step of dt=0.25s. This keeps physics stable.

### Concurrent Command Handling

- `LinkedBlockingQueue` handles unlimited concurrent `offer()` calls safely
- `drainTo()` is atomic — returns a consistent snapshot of all pending commands
- Commands applied in FIFO order within a single tick
- The existing `CommandQueueTest` already validates 100-thread concurrent enqueue. Phase 4 success criterion requires 1000 threads — need to bump the test.

### Stop and Restart

When STOP is issued:
1. Status -> STOPPED
2. Tick counter -> 0
3. All vehicles must be cleared from all lanes (currently missing)
4. VehicleSpawner accumulator must be reset (currently missing from Stop handler)

When START is issued after STOP:
1. Status -> RUNNING
2. Fresh simulation begins with empty roads
3. Vehicles start spawning from tick 1

### Network Not Loaded

If `roadNetwork` is null (before map load), the tick loop should:
1. Still drain commands (might receive START)
2. Still broadcast state (empty vehicles, tick=0)
3. Skip spawn/physics/despawn

This is already the case in `TickEmitter` — the `if (network != null)` guard handles it.

---

## 9. Test Strategy

### Thread Safety Tests

1. **1000-thread concurrent enqueue** — Scale existing `CommandQueueTest` from 100 to 1000 threads. Assert zero exceptions and all commands processed.
2. **Concurrent enqueue during active tick** — Start simulation, then flood commands from multiple threads while tick loop is running. Assert no `ConcurrentModificationException`.

### Tick Timing Tests

3. **20 Hz jitter test** — Run simulation for 100 ticks, record timestamps from `SimulationStateDto`. Assert max jitter (difference between consecutive tick intervals) < 5ms.
4. **Speed multiplier affects dt** — Set multiplier to 2.0, verify vehicle positions advance at double rate compared to 1.0x.

### Integration Tests

5. **Full tick pipeline** — Load map, start simulation, run 20 ticks. Assert: vehicles spawned, positions updated by physics, vehicles past lane end despawned.
6. **Pause/resume preserves state** — Start, run 10 ticks, pause, verify state frozen (vehicle positions unchanged across 5 ticks), resume, verify positions change again.
7. **Stop clears state** — Start, run 10 ticks, stop. Assert tick counter = 0, vehicle count = 0.

### WebSocket Integration Tests (optional)

8. **STOMP broadcast received** — Spring Boot test with `TestStompClient`. Send START, verify `/topic/simulation` receives `SimulationStateDto` with status RUNNING.

**Note:** WebSocket integration tests require `@SpringBootTest` with full context and are slow. Consider deferring to Phase 5 when frontend is available for manual verification.

### Existing Tests to Preserve

All 38 existing tests must continue to pass:
- `MapLoaderTest` (8)
- `VehicleSpawnerTest` (7)
- `VehicleDespawnTest` (3)
- `SimulationStateDtoTest` (3)
- `CommandQueueTest` (5)
- `FullPipelineTest` (2)
- `PhysicsEngineTest` (9)
- Phase 1 test (1)

---

## 10. Implementation Plan Summary

### Plan 4.1: Wire PhysicsEngine into TickEmitter

**What:** Add `PhysicsEngine` as a dependency of `TickEmitter`. In the tick method, after spawn and before despawn, iterate all lanes and call `physicsEngine.tick(lane, effectiveDt)`. Apply speed multiplier to dt. Add sub-stepping guard for high multipliers.

**Files modified:** `TickEmitter.java`
**Files created:** None

### Plan 4.2: StatePublisher + Stop/Reset Logic

**What:** Extract broadcast to `StatePublisher`. Fix Stop command to clear vehicles and reset spawner. Add `@PostConstruct` map auto-load in SimulationEngine.

**Files modified:** `TickEmitter.java`, `SimulationEngine.java`
**Files created:** `StatePublisher.java`

### Plan 4.3: SimulationController REST Endpoints

**What:** Create `SimulationController` with `GET /api/simulation/status` and `GET /api/maps`. Minimal REST surface for frontend initialization.

**Files created:** `SimulationController.java`, `SimulationStatusDto.java` (response DTO)

### Plan 4.4: Thread Safety & Integration Tests

**What:** Scale concurrent test to 1000 threads. Add tick pipeline integration test with physics. Add pause/resume and stop/restart tests. Add timing jitter test.

**Files modified:** `CommandQueueTest.java`
**Files created:** `TickPipelineIntegrationTest.java`

---

## 11. Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Physics instability at 5x speed | Medium | High (NaN/Inf) | Sub-stepping: run N small steps instead of 1 large step |
| Tick jitter > 5ms | Low | Medium | 20 Hz is very lenient; physics for <100 vehicles takes <1ms |
| TickEmitter refactoring breaks existing flow | Medium | High | Keep @Scheduled approach; add PhysicsEngine call; test thoroughly |
| Stop not clearing vehicles | Certain (known bug) | Medium | Fix in Plan 4.2 |
| STOMP broadcast performance with many vehicles | Low (Phase 4) | Low | JSON for 100 vehicles ~10KB; fine at 20 Hz |
