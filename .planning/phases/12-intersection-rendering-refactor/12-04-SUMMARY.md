# Plan 12.4 Summary: Frontend — drawIntersections.ts filled intersection boxes

## Status: DONE

## What was done

### Task 12.4.1: Create drawIntersections.ts
- Created `frontend/src/rendering/drawIntersections.ts` with a clean module that draws intersections as filled dark gray rounded rectangles
- Uses `roundRect` for softened corners, fill color `#3a3a3a` (matching road surface), subtle border `#4a4a4a`
- Skips intersections with `size <= 0`

### Task 12.4.2: Integrate drawIntersections into rendering pipeline
- Imported `drawIntersections` in `SimulationCanvas.tsx`
- Subscribed to `intersections` from the Zustand store
- Called `drawIntersections(ctx, intersections)` after `drawRoads()` on the static canvas layer
- Added `intersections` to the useEffect dependency array
- Old hacky `drawIntersectionBoxes` function was already removed from `drawRoads.ts` (by plan 12-02)

## Files modified
- `frontend/src/rendering/drawIntersections.ts` (new)
- `frontend/src/components/SimulationCanvas.tsx` (modified)

## Verification
- `npx tsc --noEmit` passes cleanly
