# Plan 09-03 Summary: Frontend Map Selector & Road Re-fetch

## Status: DONE

## What was implemented

### Task 1: MapInfo type + SimulationStateDto update
- Added `MapInfo` interface (id, name, description) to `simulation.ts`
- Added `error` and `mapId` fields to `SimulationStateDto`

### Task 2: Zustand store map state
- Added `availableMaps`, `currentMapId`, `mapError`, `refetchRoads` to store
- `setTick` now extracts `mapId` and `error` from tick data
- Added `clearSnapshots` action for map change cleanup

### Task 3: Maps fetch + reusable fetchRoads
- Extracted roads fetch into reusable `fetchRoads` function in `useWebSocket`
- Added `GET /api/maps` fetch on mount, stores result via `setAvailableMaps`
- `fetchRoads` stored in Zustand via `setRefetchRoads` for ControlsPanel access

### Task 4 & 5: Map selector dropdown + snapshot clearing
- Added scenario dropdown above simulation buttons in ControlsPanel
- Dropdown disabled when status is RUNNING
- Shows selected map description below dropdown
- Red error banner for `mapError`
- On map change: clears snapshots, sends LOAD_MAP, re-fetches roads after 200ms delay

## Files modified
- `frontend/src/types/simulation.ts`
- `frontend/src/store/useSimulationStore.ts`
- `frontend/src/hooks/useWebSocket.ts`
- `frontend/src/components/ControlsPanel.tsx`

## Verification
- TypeScript compiles cleanly (`npx tsc --noEmit` passes)
