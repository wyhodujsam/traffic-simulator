---
phase: 14-playwright-bugfixes-responsive
plan: 01
subsystem: physics
tags: [java, intersection, vehicle-transfer, gap-check, merge]

# Dependency graph
requires:
  - phase: 13-ui-simulation-bugfixes
    provides: merge lane targeting (ramp -> lane 0)
provides:
  - Same-tick vehicle overlap prevention at merge intersections
affects: [intersection-rendering, simulation-physics]

# Tech tracking
tech-stack:
  added: []
  patterns: [shared-tick-map for preventing duplicate placement, effective-position calculation]

key-files:
  created: []
  modified:
    - backend/src/main/java/com/trafficsimulator/engine/IntersectionManager.java

key-decisions:
  - "Used shared Map<String, Double> lastPlacedPosition per intersection per tick to prevent cross-road overlap"
  - "effectivePosition = max(outBuffer, lastPlaced + MIN_ENTRY_GAP) ensures minimum 7m spacing on transfer"
  - "Congestion guard: skip transfer if effectivePosition > outBuffer + 3*MIN_ENTRY_GAP"

patterns-established:
  - "Per-tick shared state map pattern: pass Map through all sub-calls within one processTransfers() invocation"

requirements-completed: [BUG-1]

# Metrics
duration: 10min
completed: 2026-04-02
---

# Phase 14 Plan 01: Merge Vehicle Overlap Fix Summary

**Same-tick multi-road vehicle overlap at highway-merge intersections eliminated via shared lastPlacedPosition map tracking effectivePosition per target lane.**

## Performance

- **Duration:** ~10 min
- **Started:** 2026-04-02T21:00:00Z
- **Completed:** 2026-04-02T21:10:00Z
- **Tasks:** 1
- **Files modified:** 1

## Accomplishments
- Fixed BUG-1: vehicles from ramp and main road no longer overlap at merge point at spawn rate >= 2.0
- Added `lastPlacedPosition` map shared across all `transferVehiclesFromLane` calls in a single tick
- Effective entry position accounts for vehicles just placed in the same tick from other inbound roads
- Congestion guard prevents vehicles from stacking beyond 3 gap-lengths past the entry buffer
- All 140 backend tests continue to pass

## Task Commits

1. **Task 1: Fix same-tick multi-transfer gap check in IntersectionManager** - `7441c9f` (fix)

**Plan metadata:** _(pending final commit)_

## Files Created/Modified
- `backend/src/main/java/com/trafficsimulator/engine/IntersectionManager.java` - Added lastPlacedPosition parameter to transferVehiclesFromLane, created shared map in processTransfers, compute effectivePosition with congestion guard

## Decisions Made
- Shared `Map<String, Double> lastPlaced` created once per intersection per tick in `processTransfers`, passed into `transferVehiclesFromLane` — ensures all inbound roads to the same outbound lane see cumulative placement state.
- Congestion guard threshold `outBuffer + 3 * MIN_ENTRY_GAP` (= entry + 21m) prevents infinite stacking; vehicles are simply skipped when queue too long.
- MIN_ENTRY_GAP value unchanged at 7.0 (4.5m vehicle + 2.5m s0), per plan specification.

## Deviations from Plan
None - plan executed exactly as written.

## Issues Encountered
None — Maven test path required running from `backend/` subdirectory directly (not `mvn test -pl backend` from root).

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- BUG-1 fix is complete; merge intersections now prevent overlap at high spawn rates
- Plans 14-02 and 14-03 handle responsive layout and UX fixes independently

---
*Phase: 14-playwright-bugfixes-responsive*
*Completed: 2026-04-02*
