# Plan 12.2 Summary: Backend — IntersectionDto endpoint for frontend rendering

## Status: DONE

## What was done

### Task 12.2.1: Create IntersectionDto and GET /api/intersections endpoint
- Created `IntersectionDto.java` with fields: `id`, `x`, `y`, `size`
- Added `GET /api/intersections` endpoint in `SimulationController.java`
- Endpoint iterates intersections, resolves center coordinates from connected roads, and computes size from `intersectionSize` config (falling back to `maxLaneCount * 14px`)
- Backend compiles cleanly

### Task 12.2.2: Add IntersectionDto to frontend types and fetch in store
- Added `IntersectionDto` interface in `simulation.ts`
- Added `intersections: IntersectionDto[]` state field and `setIntersections` action to `useSimulationStore.ts`
- Added parallel `fetch('/api/intersections')` call in `useWebSocket.ts` alongside road fetch (also re-fetches on map load)
- Frontend compiles cleanly with `tsc --noEmit`

## Files modified
- `backend/src/main/java/com/trafficsimulator/dto/IntersectionDto.java` (new)
- `backend/src/main/java/com/trafficsimulator/controller/SimulationController.java`
- `frontend/src/types/simulation.ts`
- `frontend/src/store/useSimulationStore.ts`
- `frontend/src/hooks/useWebSocket.ts`

## Acceptance criteria
- [x] `grep "class IntersectionDto" backend/.../dto/IntersectionDto.java` exits 0
- [x] `grep "getIntersections" backend/.../controller/SimulationController.java` exits 0
- [x] `cd backend && mvn compile -q` exits 0
- [x] `grep "IntersectionDto" frontend/src/types/simulation.ts` exits 0
- [x] `grep "intersections" frontend/src/store/useSimulationStore.ts` exits 0
- [x] `grep "/api/intersections" frontend/src/hooks/useWebSocket.ts` exits 0
- [x] `cd frontend && npx tsc --noEmit` exits 0
