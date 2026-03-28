---
phase: 2
plan: 2
name: "Command Queue Pattern"
status: complete
completed_at: "2026-03-27"
---

# Plan 2.2 Summary: Command Queue Pattern

## Outcome

All 5 tasks completed and compiled successfully. The command queue pattern is fully implemented with thread-safe delivery, state machine validation, and proper value storage for rate/multiplier commands.

## Files Created

| File | Purpose |
|------|---------|
| `backend/src/main/java/com/trafficsimulator/engine/command/SimulationCommand.java` | Sealed interface with 7 record command types |
| `backend/src/main/java/com/trafficsimulator/engine/SimulationStatus.java` | Enum: STOPPED, RUNNING, PAUSED |
| `backend/src/main/java/com/trafficsimulator/engine/SimulationEngine.java` | Spring component with LinkedBlockingQueue, drainTo, state machine |
| `backend/src/main/java/com/trafficsimulator/dto/CommandDto.java` | STOMP payload DTO with nullable fields |
| `backend/src/main/java/com/trafficsimulator/controller/CommandHandler.java` | @MessageMapping("/command") STOMP controller |
| `backend/src/main/java/com/trafficsimulator/model/RoadNetwork.java` | Minimal stub for compilation (full impl: Plan 2.1) |
| `backend/src/main/java/com/trafficsimulator/model/Road.java` | Minimal stub for compilation (full impl: Plan 2.1) |
| `backend/src/main/java/com/trafficsimulator/model/Lane.java` | Minimal stub for compilation (full impl: Plan 2.1) |
| `backend/src/main/java/com/trafficsimulator/model/Vehicle.java` | Minimal stub for compilation (full impl: Plan 2.1) |

## Review Feedback Applied

### HIGH: SetSpawnRate/SetSpeedMultiplier must store values (not just log)
**Fixed:** `SimulationEngine` now has `volatile double spawnRate` and `volatile double speedMultiplier` fields with `@Getter`. Both commands update these fields. Plan 2.4 VehicleSpawner can read `engine.getSpawnRate()` to get the current value.

### MEDIUM: Add state machine validation
**Fixed:** `applyCommand` now enforces:
- `Start` only accepted from `STOPPED` state
- `Pause` only accepted from `RUNNING` state
- `Resume` only accepted from `PAUSED` state
- `Stop` ignored if already `STOPPED`
Invalid transitions emit a WARN log and return early (command ignored).

### LOW: CommandDto String type — better error messages for unknown types
**Fixed:** `CommandHandler` has a `VALID_TYPES` constant and the default case in the switch produces: `"Unknown command type: 'FOO'. Valid types are: LOAD_MAP, PAUSE, RESUME, SET_SPAWN_RATE, SET_SPEED_MULTIPLIER, START, STOP"`. Clear feedback for frontend integration errors.

## Technical Notes

- **Java 17 compatibility:** Pattern matching in switch with sealed interfaces is a preview feature in Java 17 (standard in Java 21). Used `instanceof` checks instead to avoid enabling `--enable-preview`, keeping the pom.xml clean.
- **Model stubs:** Plan 2.2 creates minimal stubs for `RoadNetwork`, `Road`, `Lane`, `Vehicle` to allow compilation. Plan 2.1 will overwrite these with full implementations.
- **Thread safety:** `LinkedBlockingQueue` is the only cross-thread communication path. All domain state mutations happen on the tick thread via `drainCommands()`. The `spawnRate` and `speedMultiplier` fields are `volatile` for safe visibility from any thread reading them.

## Verification

```
mvn compile -q → Exit code: 0
All acceptance criteria greps: PASSED
```

## Commits

1. `feat(02-02): Task 2.2.1 — SimulationCommand sealed interface with record types`
2. `feat(02-02): Task 2.2.2 — SimulationStatus enum`
3. `feat(02-02): Task 2.2.3 — SimulationEngine with command queue and state machine`
4. `feat(02-02): Task 2.2.4 — CommandDto for STOMP command messages`
5. `feat(02-02): Task 2.2.5 — CommandHandler STOMP controller`
