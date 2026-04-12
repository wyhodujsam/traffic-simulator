# Phase 17: Routing & Map Embed - Research

**Researched:** 2026-04-12
**Domain:** React client-side routing (react-router-dom) + Leaflet interactive map with bounding box selector
**Confidence:** HIGH

## Summary

Phase 17 introduces two new frontend capabilities to the existing React 18 + Vite + Zustand codebase: (1) client-side routing with react-router-dom so the user can navigate between `/` (simulation) and `/map` without a full page reload, and (2) an interactive OSM map on the `/map` page using Leaflet with a draggable/resizable bounding box overlay.

The critical version constraint is that **react-leaflet v5 requires React 19**, which this project does not use (React 18.3.x). The correct version to use is **react-leaflet 4.2.1** (latest v4), which explicitly targets React 18 and Leaflet 1.9.x. For routing, **react-router-dom v6.30.3** is the correct choice — v7 is the latest but is a major rework introducing file-based routing and React Router framework concepts that are unnecessary and potentially disruptive for this SPA. v6 uses the familiar `BrowserRouter` + `<Routes>` + `<Route>` pattern.

The simulation state (Zustand store) lives outside React Router and will survive navigation automatically — no special persistence work is needed, since `useSimulationStore` is module-level singleton state. The WebSocket connection (`useWebSocket`) is mounted at the app root and must be moved above `<Routes>` so it continues ticking while the user is on `/map`.

**Primary recommendation:** Use react-router-dom ^6.30.3 + react-leaflet ^4.2.1 + leaflet ^1.9.4. Wrap the App in `BrowserRouter`, split into `SimulationPage` and `MapPage` components, mount the WebSocket hook at root level. For the bounding box, use `L.Rectangle` imperative Leaflet API inside a `useEffect` — react-leaflet has no built-in bounding-box component.

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| MAP-01 | User can open /map page with interactive OSM Leaflet map | react-leaflet 4.2.1 + MapContainer + TileLayer (OpenStreetMap) |
| MAP-02 | User can pan and zoom the map to any area | Leaflet default behavior — no custom code needed |
| MAP-03 | User sees bounding box showing the area to be fetched | `L.Rectangle` created imperatively in useEffect, updated on map `moveend`/`zoomend` |
| MAP-04 | App has routing between / (simulation) and /map without full reload | react-router-dom v6 BrowserRouter + Routes + Route + Link |
</phase_requirements>

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| react-router-dom | ^6.30.3 | Client-side routing, `<BrowserRouter>`, `<Routes>`, `<Route>`, `<Link>`, `useNavigate` | v6 is stable SPA pattern; v7 is framework-oriented and adds unnecessary complexity |
| leaflet | ^1.9.4 | Interactive map core library | Mature, OSM-compatible, no React 19 requirement |
| react-leaflet | ^4.2.1 | React wrapper for Leaflet | v4 = React 18 compatible; v5 requires React 19 |
| @types/leaflet | ^1.9.21 | TypeScript types for Leaflet | Matches leaflet 1.9.x |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| @types/react-router-dom | (bundled in react-router-dom v6) | TypeScript types | Included in package — no separate install needed |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| react-router-dom v6 | react-router-dom v7 | v7 adds React Router framework patterns (loader, action, file routing) — overkill for a simple 2-page SPA; also a major API change |
| react-leaflet v4 | react-leaflet v5 | v5 requires React 19 — incompatible with this project's React 18.3.x |
| react-leaflet v4 | vanilla Leaflet in useEffect | Vanilla Leaflet works but requires manual DOM cleanup; react-leaflet v4 handles mount/unmount lifecycle cleanly |

**Installation:**
```bash
cd frontend
npm install react-router-dom@^6.30.3 leaflet@^1.9.4 react-leaflet@^4.2.1 @types/leaflet@^1.9.21
```

**Version verification (confirmed 2026-04-12 against npm registry):**
- `react-router-dom` latest: 7.14.0 — using v6 tag: 6.30.3
- `react-leaflet` latest: 5.0.0 (React 19 required) — using v4: 4.2.1
- `leaflet` latest: 1.9.4
- `@types/leaflet` latest: 1.9.21

## Architecture Patterns

### Recommended Project Structure
```
frontend/src/
├── App.tsx              # Wrap in BrowserRouter; mount useWebSocket here; render Routes
├── pages/
│   ├── SimulationPage.tsx   # Extracted from App.tsx — simulation canvas + sidebar
│   └── MapPage.tsx          # New /map page — Leaflet map + bbox overlay
├── components/
│   ├── SimulationCanvas.tsx # Unchanged
│   ├── ControlsPanel.tsx    # Unchanged
│   ├── StatsPanel.tsx       # Unchanged
│   └── BoundingBoxMap.tsx   # Leaflet MapContainer + bbox rectangle logic
├── hooks/
│   ├── useWebSocket.ts      # Unchanged — called in App.tsx root
│   └── useBoundingBox.ts    # Derives default bbox from map center + zoom level
└── store/
    └── useSimulationStore.ts  # Unchanged — survives navigation as module singleton
```

### Pattern 1: BrowserRouter wrapping with WebSocket at root
**What:** Mount routing above all pages; mount WebSocket hook at root so it stays connected during navigation.
**When to use:** Any multi-page SPA where real-time state must persist across routes.
**Example:**
```typescript
// App.tsx
import { BrowserRouter, Routes, Route } from 'react-router-dom';
import { useWebSocket } from './hooks/useWebSocket';
import { SimulationPage } from './pages/SimulationPage';
import { MapPage } from './pages/MapPage';

function App() {
  useWebSocket(); // keeps WS alive on /map too
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<SimulationPage />} />
        <Route path="/map" element={<MapPage />} />
      </Routes>
    </BrowserRouter>
  );
}
```

### Pattern 2: Leaflet MapContainer with bbox rectangle
**What:** `MapContainer` renders the map; a `useEffect` creates an `L.Rectangle` and attaches `moveend`/`zoomend` listeners to update the bbox as the user pans.
**When to use:** When you need imperative Leaflet operations (drawing shapes) alongside declarative react-leaflet components.
**Example:**
```typescript
// BoundingBoxMap.tsx
import { MapContainer, TileLayer, useMap } from 'react-leaflet';
import L from 'leaflet';
import { useEffect, useRef } from 'react';

function BoundingBoxLayer() {
  const map = useMap();
  const rectRef = useRef<L.Rectangle | null>(null);

  useEffect(() => {
    const updateRect = () => {
      const bounds = map.getBounds();
      const center = bounds.getCenter();
      const pad = 0.01; // degrees — initial bbox size
      const bbox = L.latLngBounds(
        [center.lat - pad, center.lng - pad],
        [center.lat + pad, center.lng + pad]
      );
      if (rectRef.current) {
        rectRef.current.setBounds(bbox);
      } else {
        rectRef.current = L.rectangle(bbox, { color: '#0088ff', weight: 2 }).addTo(map);
      }
    };
    updateRect();
    map.on('moveend zoomend', updateRect);
    return () => {
      map.off('moveend zoomend', updateRect);
      rectRef.current?.remove();
      rectRef.current = null;
    };
  }, [map]);

  return null;
}

export function BoundingBoxMap() {
  return (
    <MapContainer
      center={[52.2297, 21.0122]} // Warsaw default
      zoom={13}
      style={{ height: '100%', width: '100%' }}
    >
      <TileLayer
        attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
        url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
      />
      <BoundingBoxLayer />
    </MapContainer>
  );
}
```

### Pattern 3: Navigation link on simulation page
**What:** Add a `<Link to="/map">` button in the header/controls panel. Use `<Link to="/">` on the map page to return.
**When to use:** Any navigation between routes in react-router-dom.
**Example:**
```typescript
import { Link } from 'react-router-dom';

// In SimulationPage header:
<Link to="/map" style={{ color: '#4af', textDecoration: 'none' }}>
  Open Map
</Link>

// In MapPage header:
<Link to="/" style={{ color: '#4af', textDecoration: 'none' }}>
  Back to Simulation
</Link>
```

### Anti-Patterns to Avoid
- **Mounting `useWebSocket` inside a route component:** The WebSocket connection will disconnect and reconnect every time the user navigates. Mount it once in App.tsx above `<Routes>`.
- **Using `window.location.href` for navigation:** Causes a full page reload, losing React state. Use `<Link>` or `useNavigate()` from react-router-dom.
- **Creating a Leaflet map directly in a `useEffect` without react-leaflet:** Works but requires manual DOM node management and cleanup. Use `<MapContainer>` instead.
- **Forgetting leaflet CSS import:** Without `import 'leaflet/dist/leaflet.css'`, tiles render but map controls (zoom buttons, attribution) appear broken or unstyled. Import in MapPage.tsx or main.tsx.
- **Not setting explicit height on MapContainer:** Leaflet maps require a pixel height. If the container has `height: 0` or is `auto`, the map renders as a zero-height div with tiles invisible.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Tile-based interactive map | Custom Canvas tile renderer | `leaflet` + `react-leaflet` | Tile caching, pan/zoom math, touch support, attribution — thousands of edge cases |
| Client-side URL routing | `window.location` + `history.pushState` manually | `react-router-dom` | Browser back/forward, `<Link>` prefetching, nested routes, query params — all handled |
| Map bounding box computation | Custom lat/lng math | `L.LatLngBounds`, `map.getBounds()`, `L.rectangle()` | Leaflet's CRS-aware bounds math handles edge cases at antimeridian and poles |

**Key insight:** Both react-router-dom and react-leaflet are thin wrappers that handle browser/map lifecycle correctly. Building alternatives introduces memory leaks (event listeners on unmounted components) and state sync bugs.

## Common Pitfalls

### Pitfall 1: react-leaflet v5 / React 18 incompatibility
**What goes wrong:** `npm install react-leaflet` installs v5.0.0 by default, which has `react: "^19.0.0"` as a peer dependency. The project uses React 18, causing peer dependency warnings or runtime errors.
**Why it happens:** react-leaflet v5 was released targeting React 19. `npm install react-leaflet` without pinning installs latest.
**How to avoid:** Always install `react-leaflet@^4.2.1` explicitly. The `^4` range will not accidentally upgrade to v5.
**Warning signs:** `npm install` peer dependency warning mentioning React 19 requirement.

### Pitfall 2: Missing Leaflet CSS
**What goes wrong:** Map renders as a grey box, zoom controls are missing, or tile images appear without grid positioning.
**Why it happens:** Leaflet requires its stylesheet (`leaflet/dist/leaflet.css`) to be imported. React-leaflet does not import it automatically.
**How to avoid:** Add `import 'leaflet/dist/leaflet.css';` at the top of MapPage.tsx or in main.tsx.
**Warning signs:** Map container is visible but tiles are stacked/misaligned; zoom buttons absent.

### Pitfall 3: MapContainer height not set
**What goes wrong:** The Leaflet map is invisible — container exists in DOM but renders at 0px height.
**Why it happens:** Leaflet `MapContainer` renders as a `<div>` and inherits its parent height. If the parent has no explicit height (common with `flex: 1` containers), the map div collapses.
**How to avoid:** Set `style={{ height: '100%', width: '100%' }}` on `<MapContainer>` AND ensure the parent element has an explicit height (px or vh, not `auto`).
**Warning signs:** Empty grey rectangle, no tile requests in browser network tab.

### Pitfall 4: WebSocket disconnect on navigation
**What goes wrong:** User navigates to `/map`, WebSocket disconnects because the component that called `useWebSocket()` was unmounted.
**Why it happens:** If `useWebSocket` is called inside the simulation page component, unmounting that route unmounts the hook.
**How to avoid:** Call `useWebSocket()` inside `App` (or a layout component) that wraps all routes — it stays mounted during navigation.
**Warning signs:** Console shows WebSocket disconnect when navigating to /map; simulation state stops updating on return.

### Pitfall 5: BrowserRouter 404 on direct URL access (production build)
**What goes wrong:** Navigating directly to `/map` in a production build returns a 404 from the dev server.
**Why it happens:** The Vite dev server must be configured to serve `index.html` for all paths (HTML5 history API fallback).
**How to avoid:** Vite dev server handles this automatically. For production, configure the web server to return index.html for all routes. Not an issue for dev, but document for later.
**Warning signs:** Direct URL `/map` returns 404 in production; works fine when navigating from `/`.

### Pitfall 6: Leaflet default marker icon broken with Vite
**What goes wrong:** Default Leaflet marker icons (`L.marker()`) show broken image icons.
**Why it happens:** Vite's asset bundling breaks Leaflet's internal URL resolution for icon images.
**How to avoid:** This phase does not use markers — only `L.Rectangle` for the bbox. Not relevant here. Note for future phases that use markers.
**Warning signs:** Broken image icon where a map pin should appear.

## Code Examples

### Route setup in App.tsx
```typescript
// Source: react-router-dom v6 official docs
import { BrowserRouter, Routes, Route } from 'react-router-dom';
import { useWebSocket } from './hooks/useWebSocket';

function App() {
  useWebSocket(); // must be above Routes to survive navigation
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<SimulationPage />} />
        <Route path="/map" element={<MapPage />} />
      </Routes>
    </BrowserRouter>
  );
}
```

### MapPage with Leaflet map
```typescript
// Source: react-leaflet v4 docs https://react-leaflet.js.org/docs/start-introduction/
import 'leaflet/dist/leaflet.css';
import { MapContainer, TileLayer } from 'react-leaflet';
import { BoundingBoxLayer } from '../components/BoundingBoxLayer';

export function MapPage() {
  return (
    <div style={{ height: '100vh', display: 'flex', flexDirection: 'column' }}>
      <header>...</header>
      <div style={{ flex: 1 }}>
        <MapContainer center={[52.23, 21.01]} zoom={13} style={{ height: '100%', width: '100%' }}>
          <TileLayer
            url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
            attribution='&copy; OpenStreetMap contributors'
          />
          <BoundingBoxLayer />
        </MapContainer>
      </div>
    </div>
  );
}
```

### Exposing bbox state for Phase 18
```typescript
// BoundingBoxLayer exposes bbox via callback prop for Phase 18
interface BoundingBoxLayerProps {
  readonly onBoundsChange?: (bounds: L.LatLngBounds) => void;
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| react-router v5 (`<Switch>`, `component={}`) | react-router-dom v6 (`<Routes>`, `element={}`) | 2021 (v6 release) | Cleaner nested routing; `element` prop replaces `component`/`render` |
| react-leaflet v3 (class-based context) | react-leaflet v4 (hook-based context, `useMap()`) | 2022 (v4 release) | Hooks replace `withLeaflet` HOC; `useMap()` for imperative map access |
| react-leaflet v4 | react-leaflet v5 | 2024 (v5 release) | Requires React 19 — NOT compatible with this project |

**Deprecated/outdated:**
- `<Switch>` component: Replaced by `<Routes>` in react-router-dom v6
- `withLeaflet()` HOC: Replaced by `useMap()` hook in react-leaflet v4
- `react-router` v5 pattern `<Route component={Foo}>`: Use `<Route element={<Foo />}>` in v6

## Open Questions

1. **Default map center location**
   - What we know: A default center is needed for the Leaflet map on first load. Warsaw (52.23, 21.01) is a sensible default given the project's Polish naming conventions.
   - What's unclear: Whether the user prefers a different default city or configurable default.
   - Recommendation: Use Warsaw as default; this can be made configurable in a later iteration.

2. **Bounding box initial size and resizeability**
   - What we know: MAP-03 requires the bbox to be visible and update as user moves/zooms. The requirement does not specify drag-to-resize handles.
   - What's unclear: Whether the bbox should be user-resizable (drag handles) or auto-sized relative to the current viewport.
   - Recommendation: For Phase 17, implement auto-sized bbox (fixed percentage of visible map area, updating on pan/zoom). User-resizable handles are an enhancement for Phase 18 when the bbox is actually used to trigger a fetch.

3. **Where to store bbox state**
   - What we know: Phase 18 (OSM pipeline) needs the bbox to trigger Overpass API calls. Phase 17 only needs to display it.
   - What's unclear: Whether to store bbox in Zustand store or component-local state.
   - Recommendation: Component-local state in Phase 17. Promote to Zustand store in Phase 18 when backend fetch is wired.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Node.js | npm install | ✓ | 18.19.1 | — |
| npm | package install | ✓ | 9.2.0 | — |
| react-router-dom@^6.30.3 | MAP-04 routing | not installed yet | — | Install required |
| leaflet@^1.9.4 | MAP-01 map | not installed yet | — | Install required |
| react-leaflet@^4.2.1 | MAP-01 map | not installed yet | — | Install required |
| @types/leaflet@^1.9.21 | TypeScript types | not installed yet | — | Install required |

**Missing dependencies with no fallback:**
- All four packages must be installed before any map or routing code is written.

**Missing dependencies with fallback:**
- None — all have clear install paths.

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | Vitest 1.6.1 |
| Config file | `frontend/vite.config.ts` (test section) |
| Quick run command | `cd frontend && npm test` |
| Full suite command | `cd frontend && npm test` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| MAP-04 | Navigating to /map renders MapPage; navigating to / renders SimulationPage | unit | `cd frontend && npm test -- --reporter=verbose` | ❌ Wave 0 |
| MAP-01 | MapPage contains a Leaflet map container | unit (smoke) | `cd frontend && npm test -- --reporter=verbose` | ❌ Wave 0 |
| MAP-02 | Leaflet pan/zoom — default Leaflet behavior, no custom code | manual | — | N/A |
| MAP-03 | BoundingBoxLayer renders an L.Rectangle on the map | unit (smoke) | `cd frontend && npm test -- --reporter=verbose` | ❌ Wave 0 |

### Sampling Rate
- **Per task commit:** `cd frontend && npm test`
- **Per wave merge:** `cd frontend && npm test`
- **Phase gate:** Full suite green + `tsc --noEmit` passes before `/gsd:verify-work`

### Wave 0 Gaps
- [ ] `frontend/src/pages/__tests__/MapPage.test.tsx` — smoke render test for MapPage (MAP-01, MAP-03)
- [ ] `frontend/src/pages/__tests__/SimulationPage.test.tsx` — smoke render test after refactor (regression)
- [ ] `frontend/src/App.routing.test.tsx` — routing test: `<MemoryRouter initialEntries={['/map']}>` renders MapPage (MAP-04)

**Testing note:** Leaflet requires a DOM environment (jsdom). `MapContainer` in test environment needs a container div with explicit dimensions. Use `vi.mock('react-leaflet')` to stub map components in pure unit tests, or test routing separately from map rendering.

## Project Constraints (from CLAUDE.md)

- **Tech stack (locked):** React 18 + TypeScript + Vite (frontend) — no upgrading to React 19
- **State management:** Zustand 4.x — simulation state stays in Zustand, not React Router state
- **CSS approach:** Inline styles (existing project pattern) — no CSS frameworks to add
- **TypeScript:** `tsc --noEmit` must pass after all changes
- **Cognitive complexity:** Functions <= 15 (SonarQube rule applies to frontend too)
- **Optional chaining:** Use `?.` instead of manual null checks
- **Props interfaces:** Mark as `readonly`
- **No React Router state for simulation:** Simulation WebSocket state lives in Zustand, navigation must not reset it

## Sources

### Primary (HIGH confidence)
- npm registry `react-leaflet` dist-tags — verified 2026-04-12: v5 (latest) requires React 19, v4.2.1 requires React 18
- npm registry `react-router-dom` dist-tags — verified 2026-04-12: latest 7.14.0; v6 tag 6.30.3
- npm registry `leaflet` — verified 2026-04-12: latest 1.9.4
- npm registry `@types/leaflet` — verified 2026-04-12: latest 1.9.21
- Existing `frontend/package.json` — confirmed React 18.3.1 and absence of routing/map deps

### Secondary (MEDIUM confidence)
- react-leaflet v4 docs — https://react-leaflet.js.org/docs/start-introduction/ — hook-based API, `useMap()`, `MapContainer` usage pattern
- react-router-dom v6 docs — https://reactrouter.com/6.30.3/start/overview — `BrowserRouter`, `Routes`, `Route`, `Link`, `useNavigate`

### Tertiary (LOW confidence)
- None — all critical claims verified against npm registry

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — verified against npm registry 2026-04-12
- Architecture: HIGH — based on existing codebase analysis + library documentation patterns
- Pitfalls: HIGH — react-leaflet v5 incompatibility confirmed by peer dependency inspection

**Research date:** 2026-04-12
**Valid until:** 2026-05-12 (stable libraries; react-leaflet v4 maintenance unlikely to change)
