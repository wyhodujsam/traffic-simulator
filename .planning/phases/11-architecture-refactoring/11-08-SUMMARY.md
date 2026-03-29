# Plan 11-08 Summary: ReentrantReadWriteLock Synchronization

## Status: DONE

## What was done

Added `ReentrantReadWriteLock` to `SimulationEngine` to eliminate race conditions between the tick thread and STOMP command thread that could cause `ConcurrentModificationException` when both access `RoadNetwork` simultaneously.

### Changes

1. **SimulationEngine** — Added `ReentrantReadWriteLock networkLock` field with `readLock()` and `writeLock()` accessor methods. `drainCommands()` now acquires writeLock before dispatching commands. Added `drainCommandsUnlocked()` variant for callers that already hold the lock.

2. **TickEmitter** — `emitTick()` now acquires writeLock for the entire tick pipeline (drain + physics + snapshot + broadcast), ensuring no STOMP thread can mutate road network state during tick execution. Uses `drainCommandsUnlocked()` since it already holds writeLock.

3. **ConcurrencySafetyTest** — 4 new unit tests verifying: concurrent command dispatch safety, no deadlock under load, writeLock reentrancy, and heavy stress with mixed enqueue/drain operations.

### Lock strategy

- **writeLock for full tick**: Since the tick pipeline mutates state (spawn/despawn vehicles), writeLock is used for the entire `emitTick()` body rather than readLock.
- **writeLock for drainCommands**: Commands from STOMP threads are queued via `enqueue()` (thread-safe `LinkedBlockingQueue`) and dispatched under writeLock.
- **No contention concern**: Tick takes <40ms at 20 Hz. STOMP commands only call `enqueue()` (lock-free), so the writeLock in `emitTick()` doesn't block user interactions.

## Files modified

- `backend/src/main/java/com/trafficsimulator/engine/SimulationEngine.java`
- `backend/src/main/java/com/trafficsimulator/scheduler/TickEmitter.java`
- `backend/src/test/java/com/trafficsimulator/engine/ConcurrencySafetyTest.java`

## Test results

All existing + 4 new concurrency tests pass: `cd backend && mvn test -q`
