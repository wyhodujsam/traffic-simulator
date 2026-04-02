# Phase 14: Playwright Bug Fixes & Responsive Layout - Context

**Gathered:** 2026-04-02
**Status:** Ready for planning
**Source:** PRD Express Path (bugs.md)

<domain>
## Phase Boundary

Fix 6 bugs identified during Playwright testing session + UX polish items. Bugs range from physics overlap at merge points to responsive layout issues.

</domain>

<decisions>
## Implementation Decisions

### BUG-1: Merge vehicle overlap [HIGH]
- Vehicles overlap at highway-merge intersection point when spawn rate >= 2.0
- Root cause: transfer places multiple vehicles at same `outBuffer` position; no cross-road gap check
- Fix: After transferring a vehicle, subsequent transfers to same lane must see the just-added vehicle
- Also: vehicle render size (VEHICLE_LENGTH_PX=5) may still be larger than physical gap at high density
- NOTE: Phase 13 already reduced vehicle size from 10→5px and fixed ramp→lane0 targeting. This bug persists.

### BUG-2: Mobile responsiveness [HIGH]
- On 375x667 viewport, sidebar is completely hidden
- Fix: Stack layout vertically on narrow screens (canvas on top, controls below)
- Use CSS media query or flexbox wrap at breakpoint ~768px

### BUG-3: Sidebar cutoff on Straight Road [MEDIUM]
- Canvas 800m straight road pushes sidebar off-screen at 1280x720
- Phase 13 added `minWidth: 0` to main — may already be fixed
- Verify and ensure sidebar `minWidth: 260px` is respected

### BUG-4: Canvas doesn't scale to available space [MEDIUM]
- On 1920x1080, canvas is small (~630px) with empty space
- Fix: Scale canvas to fit available viewport while maintaining aspect ratio
- Use CSS `transform: scale()` or resize canvas dimensions to fill container

### BUG-5: Page title [LOW]
- Change `<title>` from "Vite + React + TS" to "Traffic Simulator"
- File: `frontend/index.html`

### BUG-6: Button layout [LOW]
- Start/Pause/Stop buttons wrap to two lines
- Fix: Reduce button padding or use compact layout (icons or abbreviations)

### UX Polish (Claude's Discretion)
- Throughput shows "0" for first 30s — consider showing "-" or tooltip
- Slider tooltips for clarity

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Layout
- `frontend/src/App.tsx` — Main layout (flex, sidebar, canvas area)
- `frontend/src/components/SimulationCanvas.tsx` — Canvas sizing via `computeCanvasSize()`
- `frontend/src/components/ControlsPanel.tsx` — Button and slider layout
- `frontend/src/components/StatsPanel.tsx` — Stats display

### Rendering
- `frontend/src/rendering/constants.ts` — VEHICLE_LENGTH_PX, LANE_WIDTH_PX dimensions
- `frontend/src/rendering/projection.ts` — Domain→pixel coordinate projection

### Physics/Transfer
- `backend/src/main/java/com/trafficsimulator/engine/IntersectionManager.java` — Vehicle transfer at intersections
- `backend/src/main/java/com/trafficsimulator/engine/VehicleSpawner.java` — DEFAULT_VEHICLE_LENGTH = 4.5m

### Bug report
- `bugs.md` — Full bug descriptions with screenshots

</canonical_refs>

<specifics>
## Specific Ideas

- BUG-1: Consider adding `MIN_ENTRY_GAP` check that accounts for vehicles just transferred in same tick
- BUG-2: Use `@media (max-width: 768px)` or inline style check for flexDirection switch
- BUG-4: CSS `object-fit: contain` or dynamic canvas scaling based on container size
- BUG-5: One-line change in `index.html`
- BUG-6: `gap: 4px; padding: 4px 8px;` on button container

</specifics>

<deferred>
## Deferred Ideas

- Slider reset on Stop — minor UX, not a bug
- Tooltip on throughput "0" — nice-to-have

</deferred>

---

*Phase: 14-playwright-bugfixes-responsive*
*Context gathered: 2026-04-02 via PRD Express Path*
