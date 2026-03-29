---
phase: 5
plan: 2
status: done
---

# Plan 5.2 Summary: Frontend types, Zustand store expansion, and sendCommand wiring

## What was done

### Task 5.2.1: TypeScript interfaces matching backend DTOs
- Created `frontend/src/types/simulation.ts` with all interfaces: `VehicleDto`, `StatsDto`, `SimulationStateDto`, `RoadDto`, `SimulationStatusDto`, `CommandDto`, `Snapshot`
- Types match backend Java DTOs (verified against actual source)
- Includes `Snapshot` wrapper with `vehicleMap: Map<string, VehicleDto>` for O(1) lookup during interpolation

### Task 5.2.2: Zustand store expansion
- Rewrote `frontend/src/store/useSimulationStore.ts` replacing Phase 1 debug types
- Store now holds: `currSnapshot`, `prevSnapshot` (for interpolation), `status`, `stats`, `roads`, `sendCommand`
- `setTick()` builds vehicleMap and rotates prev/curr snapshots
- `setRoads()` and `setSendCommand()` actions added
- Updated `App.tsx` to use new store shape (currSnapshot instead of lastTick)
- Updated both test files (`App.test.tsx`, `useSimulationStore.test.ts`) with new store shape and expanded test coverage (vehicleMap, prev/curr snapshots, roads)

### Task 5.2.3: sendCommand wiring in useWebSocket
- Rewrote `frontend/src/hooks/useWebSocket.ts` to:
  - Fetch road geometry via `GET /api/roads` on mount
  - Wire `sendCommand` into Zustand store on STOMP connect
  - Clear `sendCommand` on disconnect
  - Import and use new `CommandDto` and `RoadDto` types

## Verification
- All acceptance criteria grep checks pass
- `npx tsc --noEmit` passes with zero errors
- 3 commits created (one per task)

## Files modified
- `frontend/src/types/simulation.ts` (new)
- `frontend/src/store/useSimulationStore.ts` (rewritten)
- `frontend/src/hooks/useWebSocket.ts` (rewritten)
- `frontend/src/App.tsx` (updated for new store shape)
- `frontend/src/test/useSimulationStore.test.ts` (updated)
- `frontend/src/test/App.test.tsx` (updated)
