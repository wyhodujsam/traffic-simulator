---
phase: 10
plan: 1
name: "Roundabout backend logic"
status: complete
completed: "2026-04-01"
---

# Plan 10.1 Summary: Roundabout backend logic

## What was done

1. **Intersection model** — added `roundaboutCapacity` field (default 8) to Intersection and MapConfig
2. **IntersectionManager** — added ROUNDABOUT handling to `canEnterIntersection()`:
   - `isRoundaboutEntryBlocked()`: checks yield + gating conditions
   - `countRoundaboutOccupancy()`: counts vehicles on outbound roads within yield zone (15m)
   - `hasCirculatingTraffic()`: geometric angle-based check — yields to traffic on LEFT outbound road (counterclockwise flow)
   - Entry gating: blocks all entries when occupancy >= 80% of capacity
3. **IntersectionDto** — added `type` field so frontend can distinguish ROUNDABOUT from SIGNAL/PRIORITY
4. **SimulationController** — wires intersection type to DTO

## Tests added (5)

- `roundaboutTransferWhenEmpty` — vehicle passes through empty roundabout
- `roundaboutYieldToCirculatingTraffic` — stop line generated when circulating traffic on left outbound
- `roundaboutEntryGatingAtHighCapacity` — blocks entry when occupancy >= 80%
- `roundaboutAllowsEntryBelowCapacity` — allows entry when below threshold
- `roundaboutDeadlockResolves` — deadlock watchdog force-advances after 200 ticks

All 138 backend tests pass.
