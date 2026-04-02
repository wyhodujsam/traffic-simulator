---
phase: 13-ui-simulation-bugfixes
plan: 02
subsystem: ui
tags: [canvas, traffic-lights, rendering, typescript, java]

# Dependency graph
requires:
  - phase: 12-intersection-rendering-refactor
    provides: traffic light rendering foundation in drawTrafficLights.ts
provides:
  - Traffic light stop-line positioning with road-side offset
  - Box-blocking visual indicator (orange border + "B" label) when GREEN but blocked
  - boxBlocked flag from backend SnapshotBuilder to frontend TrafficLightDto
affects: [rendering, traffic-lights, intersection-visibility]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Traffic light positioned backward along road angle (STOP_LINE_OFFSET=25px) + perpendicular offset to road side"
    - "Connection line drawn in world coords before ctx.save/translate for spatial anchoring"
    - "boxBlocked flag computed in SnapshotBuilder: GREEN + all outbound roads full = true"

key-files:
  created: []
  modified:
    - frontend/src/rendering/drawTrafficLights.ts
    - backend/src/main/java/com/trafficsimulator/dto/TrafficLightDto.java
    - backend/src/main/java/com/trafficsimulator/scheduler/SnapshotBuilder.java
    - frontend/src/types/simulation.ts

key-decisions:
  - "STOP_LINE_OFFSET=25px chosen to clearly separate light from intersection center without hiding on road"
  - "Box-blocking check in SnapshotBuilder uses same outbound road logic as IntersectionManager but simplified (position > 10.0 threshold)"
  - "Connection line drawn before ctx.save so it anchors the light to the stop line in world space"

patterns-established:
  - "boxBlocked: boolean propagated from backend TrafficLightDto to frontend type for rendering logic"

requirements-completed: [FIX-02]

# Metrics
duration: 15min
completed: 2026-04-02
---

# Phase 13 Plan 02: Traffic Light Visual Fixes Summary

**Traffic lights repositioned to stop lines with road-side offset, and box-blocking shows orange border with "B" indicator when green but entry blocked**

## Performance

- **Duration:** ~15 min
- **Started:** 2026-04-02T17:10:00Z
- **Completed:** 2026-04-02T17:25:00Z
- **Tasks:** 2
- **Files modified:** 4

## Accomplishments
- Added `boxBlocked` field to `TrafficLightDto` (backend + frontend) computed when signal is GREEN but all outbound roads are full
- Traffic lights now render at stop-line position (25px offset back from intersection center) with a perpendicular side offset so each light is clearly associated with its approach road
- Visual connection line drawn from light to the road stop line in world coordinates
- Orange border + "B" label appear on a traffic light when `boxBlocked=true && state=GREEN`, explaining why vehicles are stopped on green

## Task Commits

Each task was committed atomically:

1. **Task 1: Add boxBlocked flag to TrafficLightDto and SnapshotBuilder** - `63ded1a` (feat)
2. **Task 2: Improve traffic light rendering — positioning and box-blocking indicator** - `fda8a8c` (feat)

## Files Created/Modified
- `frontend/src/rendering/drawTrafficLights.ts` - Rewritten: stop-line offset positioning, perpendicular side offset, connection line, box-blocking orange indicator
- `backend/src/main/java/com/trafficsimulator/dto/TrafficLightDto.java` - Added `private boolean boxBlocked` field
- `backend/src/main/java/com/trafficsimulator/scheduler/SnapshotBuilder.java` - Compute boxBlocked for each GREEN light by checking outbound road occupancy
- `frontend/src/types/simulation.ts` - Added `boxBlocked: boolean` to TrafficLightDto interface

## Decisions Made
- `STOP_LINE_OFFSET = 25` pixels: enough to separate light from intersection center, stays visually near the road
- Box-blocking simplified check (`position > 10.0`) in SnapshotBuilder vs full `computeStopLineBuffer` logic in IntersectionManager — avoids tight coupling between SnapshotBuilder and IntersectionManager internals
- Connection line drawn BEFORE `ctx.save()` so it uses world coordinates and visually anchors the rotated light to the stop line

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
None

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- Traffic light visual ambiguity resolved: each light is clearly positioned at its road's stop line
- Box-blocking indicator gives users a visual explanation for "stopped on green" behavior
- Ready to continue with remaining 13-xx plans

---
*Phase: 13-ui-simulation-bugfixes*
*Completed: 2026-04-02*

## Self-Check: PASSED

- drawTrafficLights.ts: FOUND
- TrafficLightDto.java: FOUND
- SnapshotBuilder.java: FOUND
- simulation.ts: FOUND
- 13-02-SUMMARY.md: FOUND
- Commit 63ded1a (Task 1): FOUND
- Commit fda8a8c (Task 2): FOUND
