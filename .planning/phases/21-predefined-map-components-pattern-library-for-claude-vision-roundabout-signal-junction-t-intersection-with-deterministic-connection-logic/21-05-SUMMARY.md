---
phase: 21
plan: 05
subsystem: frontend/map-sidebar
tags: [ui, frontend, button, handler]
requirements: [P21-FRONTEND-BUTTON, P21-FRONTEND-HANDLER]
key-files:
  modified:
    - frontend/src/components/MapSidebar.tsx
    - frontend/src/pages/MapPage.tsx
    - frontend/src/components/__tests__/MapSidebar.test.tsx
commit: a72dc18
---

# Phase 21 Plan 05: Frontend Component-Library Button Summary

Added a third button to `MapSidebar` in the idle state labelled **"AI Vision (component library)"**. When pressed it routes the current bbox to `POST /api/vision/analyze-components-bbox` and loads the returned `MapConfig` through the same path the Phase 20 "AI Vision" button already uses. Loading and error states are shared with the existing vision flow.

`MapSidebar.test.tsx` exercises the new button's presence, enablement, and handler dispatch.

## Verification
`npm test` — `MapSidebar.test.tsx` green (3/3).
