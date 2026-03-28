---
phase: 5
plan: 4
status: done
started: 2026-03-28
completed: 2026-03-28
---

# Plan 5.4 Summary: Controls panel — buttons and sliders

## What was done

### Task 5.4.1: Debounce hook utility
- Created `frontend/src/hooks/useDebouncedCallback.ts`
- Generic hook that returns a debounced version of any callback
- Uses `useRef` for timer and `useCallback` for stable reference
- Clears previous timeout on rapid calls (200ms default in consumer)

### Task 5.4.2: ControlsPanel component
- Created `frontend/src/components/ControlsPanel.tsx`
- Start/Pause/Stop buttons with state-aware enable/disable logic
- Speed multiplier slider (0.5x-5x, step 0.5)
- Spawn rate slider (0.5-5 veh/s, step 0.5)
- Max speed slider (30-200 km/h, step 10, converts to m/s before sending)
- All sliders debounced at 200ms via `useDebouncedCallback`
- Commands sent via Zustand store's `sendCommand` (CommandDto over WebSocket)
- Dark theme styling with monospace font

## Files created
- `frontend/src/hooks/useDebouncedCallback.ts`
- `frontend/src/components/ControlsPanel.tsx`

## Verification
- All acceptance criteria pass (grep checks for exports, command types, handlers, debounce usage)
- `npx tsc --noEmit` passes with zero errors
