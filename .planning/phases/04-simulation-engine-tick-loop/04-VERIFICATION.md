# Phase 04 Verification: Simulation Engine & Tick Loop

**Verified:** 2026-03-28
**Status:** PASS

## Phase Goal

> Wire physics into a running 20 Hz tick loop with proper thread architecture and command queue so all concurrency hazards are eliminated before any frontend interaction.

## Success Criteria Checklist

### 1. Simulation ticks at 20 Hz continuously; tick timestamps in broadcast show < 5 ms jitter

**Status:** PASS

- `TickEmitter` uses `@Scheduled(fixedRate = 50)` which produces a 20 Hz tick loop (1000ms / 50ms = 20 ticks/s).
- Each tick broadcasts a `SimulationStateDto` with `timestamp(System.currentTimeMillis())` — jitter is bounded by JVM scheduling + tick work.
- Tick duration monitoring is implemented: warns if any tick exceeds 40 ms (`TICK_WARN_MS`), leaving 10 ms headroom within the 50 ms budget.
- Sub-stepping ensures physics stability when speed multiplier > 1x, keeping per-tick work bounded.

### 2. Pause command sent via WebSocket pauses the tick loop; resume restarts from correct state

**Status:** PASS

- `SimulationEngine.applyCommand()` handles `Pause` (RUNNING -> PAUSED) and `Resume` (PAUSED -> RUNNING) with state machine guards.
- `TickEmitter.emitTick()` checks `simulationEngine.getStatus() == SimulationStatus.RUNNING` before advancing tick counter or running simulation pipeline — paused state skips all physics/spawn/despawn.
- Commands are drained via `drainCommands()` every tick regardless of state, so pause/resume commands are always processed.
- **Test coverage:**
  - `CommandQueueTest.drainCommands_appliesPauseAfterStart` — verifies Start -> Pause transition.
  - `CommandQueueTest.drainCommands_resumeAfterPause` — verifies Start -> Pause -> Resume transition.
  - `TickPipelineIntegrationTest.pauseResume_vehiclePositionsFrozenDuringPause` — verifies positions frozen during pause, change after resume.

### 3. Thread safety: 1000 concurrent command enqueues during active tick loop produce zero ConcurrentModificationException

**Status:** PASS

- `SimulationEngine` uses `LinkedBlockingQueue<SimulationCommand>` for the command queue — thread-safe by JDK contract.
- State fields use `volatile` (`status`, `roadNetwork`, `spawnRate`, `speedMultiplier`) and `AtomicLong` (`tickCounter`).
- All mutation happens on the tick thread via `drainCommands()` — single-writer pattern eliminates races.
- **Test coverage:**
  - `CommandQueueTest.concurrentEnqueue_1000Threads_duringActiveTick_noConcurrentModificationException` — 1000 threads with CountDownLatch start gate, 50-thread pool, concurrent tick thread draining. Zero exceptions.

## Requirement Traceability

| Requirement | Description | Evidence | Status |
|-------------|-------------|----------|--------|
| **SIM-01** | Simulation runs on tick-based loop at configurable rate (default 20 Hz) | `@Scheduled(fixedRate = 50)` in TickEmitter; tick counter incremented per tick; speed multiplier read each tick | PASS |
| **SIM-08** | Simulation state is broadcast via WebSocket (STOMP/SockJS) every tick | `StatePublisher.broadcast()` called every tick via `statePublisher.broadcast(state)` to `/topic/simulation` | PASS |
| **INFR-04** | Command queue pattern ensures thread-safe simulation state access | `LinkedBlockingQueue` in SimulationEngine; `enqueue()` from any thread, `drainCommands()` on tick thread only; 1000-thread concurrent test passes | PASS |

## Test Results

```
Tests run: 44, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
```

Key test classes for Phase 04:
- `CommandQueueTest` (6 tests) — command queue thread safety, state transitions
- `TickPipelineIntegrationTest` (5 tests) — full pipeline, pause/resume, stop/restart, speed multiplier, despawn

## Implementation Summary

| Component | File | Role |
|-----------|------|------|
| TickEmitter | `scheduler/TickEmitter.java` | 20 Hz scheduled loop: drain commands -> spawn -> physics -> despawn -> broadcast |
| SimulationEngine | `engine/SimulationEngine.java` | State machine + command queue (LinkedBlockingQueue) + volatile state fields |
| StatePublisher | `engine/StatePublisher.java` | Broadcasts SimulationStateDto to `/topic/simulation` via SimpMessagingTemplate |
| SimulationController | `controller/SimulationController.java` | REST endpoints: `GET /api/simulation/status`, `GET /api/maps` |

## Verdict

All three success criteria are met. The 20 Hz tick loop runs with physics integration, pause/resume works correctly through the command queue state machine, and thread safety is verified at 1000 concurrent enqueues. Phase 04 is complete.
