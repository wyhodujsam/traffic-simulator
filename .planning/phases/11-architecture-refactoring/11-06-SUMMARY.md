---
phase: 11
plan: 6
name: "Move Pixel Projection to Frontend"
status: complete
---

# Plan 11-06 Summary: Move Pixel Projection to Frontend

## What Changed

Moved pixel coordinate computation (x/y/angle) from backend SnapshotBuilder to a new frontend projection module. Backend DTOs now send domain data only (roadId, laneIndex, position in metres, laneChangeProgress, laneChangeSourceIndex). Frontend performs projection using road geometry from RoadDto.

## Tasks Completed

1. **Backend DTOs updated** — VehicleDto and ObstacleDto: removed `x`, `y`, `angle`, `targetLaneId`; added `roadId`, `laneIndex`, `laneChangeSourceIndex`
2. **SnapshotBuilder simplified** — Removed `LANE_WIDTH_PX` constant, `projectVehicle()`, `projectObstacle()` methods. Replaced with simple `buildVehicleDto()` and `buildObstacleDto()` that map domain fields directly. Updated SnapshotBuilderTest and SimulationStateDtoTest.
3. **Frontend TypeScript types updated** — VehicleDto and ObstacleDto interfaces match new backend shape
4. **Frontend projection module created** — `frontend/src/rendering/projection.ts` with `projectVehicle()` and `projectObstacle()` functions
5. **Rendering pipeline updated** — interpolation.ts interpolates domain fields then projects; drawObstacles.ts and hitTest.ts use projection; SimulationCanvas passes roads to all rendering functions

## Files Modified

### Backend
- `backend/src/main/java/com/trafficsimulator/dto/VehicleDto.java`
- `backend/src/main/java/com/trafficsimulator/dto/ObstacleDto.java`
- `backend/src/main/java/com/trafficsimulator/scheduler/SnapshotBuilder.java`
- `backend/src/test/java/com/trafficsimulator/scheduler/SnapshotBuilderTest.java`
- `backend/src/test/java/com/trafficsimulator/dto/SimulationStateDtoTest.java`

### Frontend
- `frontend/src/types/simulation.ts`
- `frontend/src/rendering/projection.ts` (NEW)
- `frontend/src/rendering/interpolation.ts`
- `frontend/src/rendering/drawObstacles.ts`
- `frontend/src/rendering/hitTest.ts`
- `frontend/src/components/SimulationCanvas.tsx`
- `frontend/src/rendering/constants.ts`
- `frontend/src/rendering/__tests__/interpolation.test.ts`

## Verification

- Backend: `mvn test -q` — all tests pass
- Frontend: `tsc --noEmit` — no TypeScript errors
- Frontend: `vitest run` — all unit tests pass (35/35; 1 E2E test pre-existing failure unrelated)
- `LANE_WIDTH_PX` — 0 occurrences in backend, exists only in frontend projection.ts and constants.ts
