# Plan 12.3 Summary: Frontend — clip road rendering at intersection boundaries

## What was done

### Task 12.3.1: Trim road fill and markings by clipStart/clipEnd
- Added `visibleStart`/`visibleLength`/`visibleEnd` calculation from `road.clipStart` and `road.clipEnd` in `drawRoad()`
- Road fill rectangle now draws only the visible segment
- All road markings (boundaries, lane dividers, yellow center line, closed lane hatching) clipped to visible range
- Early return when `visibleLength <= 0` (road entirely inside intersection)

### Task 12.3.2: Remove drawIntersectionBoxes hack
- Deleted `drawIntersectionBoxes()` function entirely (was a workaround for overlapping markings at intersections)
- Removed the call from `drawRoads()`
- Kept `findPairedBoundaries()` — still used for yellow center line on paired approach roads

## Files modified
- `frontend/src/rendering/drawRoads.ts` — clip logic added, intersection box hack removed

## Verification
- `npx tsc --noEmit` passes
- All acceptance criteria confirmed (grep checks for clipStart, clipEnd, visibleStart, visibleLength, no drawIntersectionBoxes, findPairedBoundaries retained)
