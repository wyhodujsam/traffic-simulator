# Plan 08-04 Summary: Traffic Light Rendering & SET_LIGHT_CYCLE Command

## Status: DONE

## What was implemented

### Task 1: TrafficLightDto
- Created `TrafficLightDto` with fields: intersectionId, roadId, state, x, y, angle
- Added `List<TrafficLightDto> trafficLights` to `SimulationStateDto`

### Task 2: Traffic light DTOs in TickEmitter snapshot
- Built traffic light DTOs from intersection data in `buildSnapshot()`
- Each inbound road of a signalised intersection produces one DTO
- State reflects current signal phase, coordinates at road end (stop line)

### Task 3: SET_LIGHT_CYCLE command backend pipeline
- Added `SetLightCycle` record to `SimulationCommand` sealed interface
- Added `intersectionId`, `greenDurationMs`, `yellowDurationMs` fields to `CommandDto`
- Added `SET_LIGHT_CYCLE` case to `CommandHandler` switch
- Added handler in `SimulationEngine.applyCommand()` that rebuilds phases preserving road groupings with all-red clearance

### Task 4: Frontend TypeScript types
- Added `TrafficLightDto` interface matching backend shape
- Added `trafficLights` to `SimulationStateDto`
- Added `SET_LIGHT_CYCLE` to `CommandType` union
- Added optional signal timing fields to `CommandDto`

### Task 5: drawTrafficLights.ts renderer
- Created renderer with 3 circles (RED/YELLOW/GREEN) per traffic light
- Active state bright, others dimmed
- Positioned at road end, rotated to match road direction

### Task 6: Store and canvas wiring
- Added `trafficLights` state to Zustand store, updated in `setTick`
- Wired `drawTrafficLights` into `SimulationCanvas` render loop after obstacles

### Task 7: Signal timing control
- Added green duration slider (5-60s) to ControlsPanel
- Sends debounced `SET_LIGHT_CYCLE` command via STOMP
- Hardcoded to `n_center` intersection (Phase 9 can add selector)

## Verification
- Backend: `mvn compile -q` passes
- Frontend: `npx tsc --noEmit` passes
- All acceptance criteria met

## Files modified
- `backend/src/main/java/com/trafficsimulator/dto/TrafficLightDto.java` (new)
- `backend/src/main/java/com/trafficsimulator/dto/SimulationStateDto.java`
- `backend/src/main/java/com/trafficsimulator/scheduler/TickEmitter.java`
- `backend/src/main/java/com/trafficsimulator/engine/command/SimulationCommand.java`
- `backend/src/main/java/com/trafficsimulator/dto/CommandDto.java`
- `backend/src/main/java/com/trafficsimulator/controller/CommandHandler.java`
- `backend/src/main/java/com/trafficsimulator/engine/SimulationEngine.java`
- `frontend/src/types/simulation.ts`
- `frontend/src/rendering/drawTrafficLights.ts` (new)
- `frontend/src/store/useSimulationStore.ts`
- `frontend/src/components/SimulationCanvas.tsx`
- `frontend/src/components/ControlsPanel.tsx`
