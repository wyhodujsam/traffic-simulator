# Architecture Research — v2.0 Map Screenshot to Simulation

**Domain:** Map screenshot capture + AI road detection + simulation config generation
**Researched:** 2026-04-10
**Confidence:** HIGH (existing codebase read in full; integration points verified against live code)

---

> This document is additive to the original ARCHITECTURE.md (v1 baseline).
> It answers only the v2.0 question: how do the new features integrate with what already exists.
> Read the original document first for the full system overview, tick loop, and baseline components.

---

## 1. What Already Exists (Integration Surface)

The entry points into the existing system that v2.0 plugs into:

| Existing Component | How v2.0 Uses It |
|---|---|
| `MapConfig` (POJO) | AI output must produce a valid `MapConfig` JSON — this is the target format |
| `MapLoader.loadFromClasspath(path)` | v2.0 needs a parallel method: `loadFromConfig(MapConfig)` that skips classpath IO |
| `CommandDispatcher.handleLoadMap(cmd)` | New command `LOAD_MAP_CONFIG` passes a `MapConfig` object directly (not a classpath ID) |
| `SimulationController POST /api/command` | No change — existing `LOAD_MAP` REST endpoint extended, or new endpoint added |
| `useSimulationStore.setAvailableMaps()` | No change — map list page is a new route, not injected into existing store |
| `useWebSocket` hook | No change — new Map page has its own fetch calls |
| Frontend routing | New: `react-router-dom` added; `/` stays simulation view, `/map` is new page |

The existing `CommandDispatcher.handleLoadMap()` currently does:
```
mapLoader.loadFromClasspath("maps/" + cmd.mapId() + ".json")
engine.setRoadNetwork(loaded.network())
```

v2.0 adds a parallel path:
```
engine.setRoadNetwork(mapConfig)   // MapConfig object comes from AI, not classpath
```

---

## 2. System Overview — v2.0 Extended Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│                         BROWSER (Client)                             │
│                                                                      │
│  Route: /                          Route: /map (NEW)                 │
│  ┌─────────────────────────┐       ┌──────────────────────────────┐  │
│  │  Existing Simulation    │       │  MapCapturePage              │  │
│  │  View (unchanged)       │       │                              │  │
│  │  Canvas / Controls /    │       │  ┌──────────────────────┐    │  │
│  │  Stats / WebSocket      │       │  │  GoogleMapEmbed      │    │  │
│  └─────────────────────────┘       │  │  (@vis.gl/react-     │    │  │
│                                    │  │   google-maps)       │    │  │
│                                    │  └──────────┬───────────┘    │  │
│                                    │             │ capture()      │  │
│                                    │  ┌──────────▼───────────┐    │  │
│                                    │  │  CaptureOverlay      │    │  │
│                                    │  │  (canvas + button)   │    │  │
│                                    │  └──────────┬───────────┘    │  │
│                                    │             │ base64 PNG     │  │
│                                    │  ┌──────────▼───────────┐    │  │
│                                    │  │  useMapCapture hook  │    │  │
│                                    │  │  POST /api/map/      │    │  │
│                                    │  │  analyze-screenshot  │    │  │
│                                    │  └──────────┬───────────┘    │  │
│                                    │             │ MapConfig JSON │  │
│                                    │  ┌──────────▼───────────┐    │  │
│                                    │  │  MapPreviewPanel     │    │  │
│                                    │  │  (review + launch)   │    │  │
│                                    │  └──────────┬───────────┘    │  │
│                                    │             │ "Load & Run"   │  │
│                                    └─────────────┼────────────────┘  │
│                                                  │ navigate("/")      │
│                                                  │ + store mapConfig  │
└──────────────────────────────────────────────────┼────────────────────┘
                                                   │
                              REST: POST /api/map/analyze-screenshot
                              REST: POST /api/map/load-config
                                                   │
┌──────────────────────────────────────────────────┼────────────────────┐
│                      BACKEND (Spring Boot)        │                   │
│                                                  │                   │
│  ┌───────────────────────────────────────────────▼───────────────┐   │
│  │                 NEW: MapAnalysisController                    │   │
│  │  POST /api/map/analyze-screenshot  (MultipartFile or base64)  │   │
│  │  POST /api/map/load-config         (MapConfig JSON body)      │   │
│  └─────────────────────────┬─────────────────────────────────────┘   │
│                            │                                         │
│  ┌─────────────────────────▼─────────────────────────────────────┐   │
│  │                 NEW: MapAnalysisService                       │   │
│  │  analyzeScreenshot(byte[] image) → MapConfig                  │   │
│  │  Calls Spring AI ChatClient with vision + structured output   │   │
│  │  Validates result via existing MapValidator                   │   │
│  └─────────────────────────┬─────────────────────────────────────┘   │
│                            │                                         │
│  ┌─────────────────────────▼─────────────────────────────────────┐   │
│  │                 MODIFIED: CommandDispatcher                   │   │
│  │  Existing: handleLoadMap(LoadMap cmd)  → classpath JSON       │   │
│  │  New:      handleLoadConfig(LoadConfig cmd) → MapConfig obj   │   │
│  └─────────────────────────┬─────────────────────────────────────┘   │
│                            │                                         │
│  ┌─────────────────────────▼─────────────────────────────────────┐   │
│  │                 MODIFIED: MapLoader                           │   │
│  │  Existing: loadFromClasspath(path) → LoadedMap                │   │
│  │  New:      loadFromConfig(MapConfig) → LoadedMap              │   │
│  │  (skips Jackson IO; runs same buildRoadNetwork() pipeline)    │   │
│  └─────────────────────────┬─────────────────────────────────────┘   │
│                            │                                         │
│  ┌─────────────────────────▼─────────────────────────────────────┐   │
│  │         EXISTING (unchanged): SimulationEngine                │   │
│  │         engine.setRoadNetwork(loaded.network())               │   │
│  └───────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 3. New Components

### 3.1 Backend — New Files

| File | Package | Responsibility |
|---|---|---|
| `MapAnalysisController.java` | `controller/` | REST: receive screenshot, return MapConfig JSON. Separate from existing `SimulationController` to maintain SRP |
| `MapAnalysisService.java` | `service/` | Calls Spring AI ChatClient with base64 image. Builds the GPT-4o prompt. Maps AI response to `MapConfig`. |
| `MapConfigDto.java` (optional) | `dto/` | If the AI response shape differs from `MapConfig` POJO, a dedicated DTO bridges the gap before validation |

### 3.2 Backend — Modified Files

| File | Change | Risk |
|---|---|---|
| `MapLoader.java` | Add `loadFromConfig(MapConfig config)` method that calls existing `buildRoadNetwork()` directly. Zero change to existing `loadFromClasspath()`. | LOW — additive only |
| `CommandDispatcher.java` | Add `handleLoadConfig(SimulationCommand.LoadConfig cmd)` handler that accepts a `MapConfig` object. Existing `handleLoadMap()` untouched. | LOW — additive only |
| `SimulationCommand.java` | Add new sealed record `LoadConfig(MapConfig config)`. | LOW — additive only |
| `pom.xml` | Add `spring-ai-openai-spring-boot-starter` dependency. | MEDIUM — new major dependency, version pinning required |

### 3.3 Frontend — New Files

| File | Location | Responsibility |
|---|---|---|
| `MapCapturePage.tsx` | `pages/` | Route `/map`. Hosts the Google Maps embed + capture flow |
| `GoogleMapEmbed.tsx` | `components/map/` | Wraps `@vis.gl/react-google-maps`. Controlled component with center/zoom state. |
| `CaptureOverlay.tsx` | `components/map/` | Canvas overlay on top of map div. `drawImage` + crop to selection. Exports base64 PNG. |
| `MapPreviewPanel.tsx` | `components/map/` | Shows detected road graph. Allows user to review nodes/roads before loading. |
| `useMapCapture.ts` | `hooks/` | POST to `/api/map/analyze-screenshot`. Manages loading/error state. |
| `useMapAnalysis.ts` | `hooks/` | Holds the `MapConfig` result from analysis. Exposes "load into simulation" action. |

### 3.4 Frontend — Modified Files

| File | Change | Risk |
|---|---|---|
| `App.tsx` | Add `react-router-dom` `<Routes>`. Route `/` → existing layout, `/map` → `MapCapturePage`. | LOW — existing view unchanged |
| `main.tsx` | Wrap with `<BrowserRouter>`. | LOW — one line |
| `package.json` | Add `react-router-dom`, `@vis.gl/react-google-maps`. | LOW |

---

## 4. Data Flow: Screenshot to Running Simulation

```
User navigates to /map
    │
    ▼
GoogleMapEmbed renders Google Maps
    │  @vis.gl/react-google-maps — key from env var VITE_GOOGLE_MAPS_API_KEY
    │
User pans/zooms to target area, clicks "Capture"
    │
    ▼
CaptureOverlay.capture()
    │  Uses html2canvas or direct canvas.drawImage on the map div
    │  NOTE: Google Maps tiles are cross-origin → canvas becomes tainted
    │  WORKAROUND: Use Google Maps Static API instead (see Section 5)
    │  Returns: base64 PNG string
    │
    ▼
useMapCapture.analyze(base64Image)
    │  POST /api/map/analyze-screenshot
    │  Body: { image: "<base64>" }  OR  multipart/form-data
    │
    ▼
MapAnalysisController receives request
    │
    ▼
MapAnalysisService.analyzeScreenshot(imageBytes)
    │  Spring AI ChatClient — model: gpt-4o
    │  System prompt: road extraction instructions (see Section 6)
    │  User message: image attachment (Media object)
    │  Response format: entity(MapConfig.class) → structured output
    │
    ▼
MapValidator.validate(mapConfig)
    │  Reuses existing validator — fail fast before returning to client
    │
    ▼
Response: MapConfig JSON → frontend
    │
    ▼
MapPreviewPanel shows road graph
    │  Renders detected roads/intersections as SVG or canvas preview
    │  User can review, then clicks "Load and Run"
    │
    ▼
useMapAnalysis.loadIntoSimulation(mapConfig)
    │  POST /api/map/load-config   body: MapConfig JSON
    │  Backend: CommandDispatcher.handleLoadConfig → MapLoader.loadFromConfig
    │          → engine.setRoadNetwork(network)
    │
    ▼
Frontend navigates to "/" (simulation view)
    │  Triggers existing refetchRoads() in useWebSocket
    │
    ▼
Simulation view shows new road network
User sends START command — simulation runs
```

---

## 5. The Screenshot Problem and Recommended Solution

**The core problem:** The Google Maps JS API renders tiles from `maps.googleapis.com`. Any attempt to call `canvas.toDataURL()` after drawing these tiles onto a canvas causes a `SecurityError: tainted canvas`. `html2canvas` and similar libraries fail for the same reason. This is a known, unsolved issue with no compliant workaround using the Maps JS API.

**Recommended solution: Google Maps Static API (server-side proxy)**

Instead of screenshotting the live JS map, the frontend records the current map viewport state (center lat/lng + zoom) and sends those to the backend, which fetches a clean static map image from Google Maps Static API.

```
Frontend: "Capture" button clicked
    │
    ├── Records: { lat, lng, zoom, width, height }
    │   (from @vis.gl/react-google-maps map state)
    │
    ▼
POST /api/map/capture
    { lat: 52.229, lng: 21.012, zoom: 16, width: 640, height: 640 }
    │
    ▼
MapAnalysisController.captureAndAnalyze(...)
    │
    │  1. Fetch from Google Maps Static API (server-side HTTP call)
    │     URL: https://maps.googleapis.com/maps/api/staticmap
    │          ?center={lat},{lng}&zoom={zoom}&size=640x640
    │          &maptype=roadmap&key={GOOGLE_MAPS_API_KEY}
    │
    │  2. imageBytes → MapAnalysisService.analyzeScreenshot(imageBytes)
    │
    ▼
Returns MapConfig JSON
```

This eliminates all CORS/tainted-canvas issues. The API key stays server-side only. Google allows fetching map images server-side with the Static API (it is its intended use case).

**Coordinate-to-pixel mapping:** Given `lat/lng center`, `zoom level`, and `640x640` image size, you can derive pixel-to-coordinate ratios. At zoom 16, one pixel ≈ 2.4 meters (at equator). The backend can use this ratio to set approximate `length` values on roads when writing `RoadConfig.length`.

```java
// Approximate meters per pixel at given zoom and latitude
double metersPerPixel = 156543.03 * Math.cos(Math.toRadians(lat)) / Math.pow(2, zoom);
```

---

## 6. AI Road Detection Integration

### Technology: Spring AI + GPT-4o Vision

**Dependency (pom.xml):**
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-openai-spring-boot-starter</artifactId>
    <version>1.0.0</version>  <!-- pin to current stable release -->
</dependency>
```

**Configuration (application.properties):**
```properties
spring.ai.openai.api-key=${OPENAI_API_KEY}
spring.ai.openai.chat.options.model=gpt-4o
spring.servlet.multipart.max-file-size=5MB
```

**Service pattern:**
```java
@Service
@RequiredArgsConstructor
public class MapAnalysisService {

    private final ChatClient.Builder chatClientBuilder;

    public MapConfig analyzeScreenshot(byte[] imageBytes, String mimeType) {
        ChatClient client = chatClientBuilder.build();
        return client.prompt()
            .system(ROAD_EXTRACTION_SYSTEM_PROMPT)
            .user(u -> u
                .text("Analyze this map image and extract the road network.")
                .media(MimeTypeUtils.parseMimeType(mimeType),
                       new ByteArrayResource(imageBytes))
            )
            .call()
            .entity(MapConfig.class);  // Spring AI appends JSON schema automatically
    }
}
```

The `entity(MapConfig.class)` call causes Spring AI to append the full JSON schema of `MapConfig` to the prompt and enforce structured output. The response is deserialized directly to a `MapConfig` object.

### Prompt Engineering

The system prompt must constrain output to match the `MapConfig` schema:

- Roads must have `id`, `fromNodeId`, `toNodeId`, `length`, `speedLimit`, `laneCount`
- Nodes must have `id`, `type` (ENTRY/EXIT/INTERSECTION), `x`, `y`
- Coordinates should be in canvas pixels with the image origin at (0,0)
- Speed limits inferred from road visual type: motorway=130, primary=70, residential=30 km/h
- Intersections should be typed: SIGNAL for traffic-light intersections, ROUNDABOUT for roundabouts, PRIORITY otherwise
- Spawn points at each ENTRY node, despawn points at each EXIT node

The prompt should acknowledge limitations: AI will make mistakes on complex road networks. The user review step in `MapPreviewPanel` is the safety gate, not the AI.

### AI Output Quality Expectations

| Scenario | Expected AI Quality | Notes |
|---|---|---|
| Simple 2-road intersection | HIGH | Clear visual cues, reliable extraction |
| Grid of 4-6 city blocks | MEDIUM | May miss one-way direction, lane count estimation rough |
| Complex spaghetti junction | LOW | Multiple overlapping roads; likely needs manual correction |
| Roundabout | MEDIUM | Model recognizes the shape; roundaboutCapacity is guessed |

This is acceptable because the user preview step exists. The AI output is a starting point, not ground truth.

---

## 7. Integration Points with Existing Code — Summary

### MapLoader — The Critical Integration

The existing `loadFromClasspath()` ends at `buildRoadNetwork(config)`. That method is already separated. The new method is:

```java
public LoadedMap loadFromConfig(MapConfig config) {
    List<String> errors = mapValidator.validate(config);
    if (!errors.isEmpty()) {
        throw new IllegalArgumentException("Map config validation failed: " + errors);
    }
    RoadNetwork network = buildRoadNetwork(config);  // reuses EXACT same method
    return new LoadedMap(network, config.getDefaultSpawnRate());
}
```

Zero new logic. Zero duplication. The only difference is the source of `MapConfig` (AI vs classpath JSON).

### SimulationCommand — Adding LoadConfig

```java
// Existing (unchanged)
record LoadMap(String mapId) implements SimulationCommand {}

// New
record LoadConfig(MapConfig config) implements SimulationCommand {}
```

### CommandDispatcher — Adding handleLoadConfig

The new handler follows the exact same pattern as `handleLoadMap()`:

```java
private void handleLoadConfig(SimulationCommand.LoadConfig cmd) {
    // Same cleanup as handleLoadMap
    engine.setStatus(SimulationStatus.STOPPED);
    engine.getTickCounter().set(0);
    engine.clearAllVehicles();
    if (vehicleSpawner != null) vehicleSpawner.reset();

    // Different: use loadFromConfig instead of loadFromClasspath
    MapLoader.LoadedMap loaded = mapLoader.loadFromConfig(cmd.config());
    engine.setRoadNetwork(loaded.network());
    engine.setSpawnRate(loaded.defaultSpawnRate());
    if (vehicleSpawner != null) vehicleSpawner.setVehiclesPerSecond(loaded.defaultSpawnRate());
    engine.setLastError(null);
}
```

### Frontend Store — No Changes Required

The simulation store's `setAvailableMaps`, `setCurrentMapId`, and `refetchRoads` remain unchanged. The new map page manages its own state via `useMapCapture` and `useMapAnalysis` hooks. After loading, `navigate("/")` + existing `refetchRoads()` picks up the newly loaded network.

---

## 8. Component Boundary Diagram

```
NEW COMPONENTS                          EXISTING COMPONENTS (unchanged unless marked)

frontend/src/pages/
  MapCapturePage.tsx  ─────────────────► App.tsx  [MODIFIED: add router]

frontend/src/components/map/
  GoogleMapEmbed.tsx  ─► @vis.gl/react-google-maps (npm)
  CaptureOverlay.tsx  ─► records map center/zoom state
  MapPreviewPanel.tsx ─► renders MapConfig as visual preview

frontend/src/hooks/
  useMapCapture.ts    ─► POST /api/map/capture
  useMapAnalysis.ts   ─► POST /api/map/load-config
                         then navigate("/")
                         then useSimulationStore.refetchRoads()  [EXISTING store action]

backend controller/
  MapAnalysisController.java  ─► MapAnalysisService.java (NEW)
                                 MapValidator.java         (EXISTING, reused)
                                 SimulationEngine.enqueue(LoadConfig)

backend service/
  MapAnalysisService.java  ─► Spring AI ChatClient
                              Google Maps Static API (HTTP)
                              MapValidator (EXISTING)

backend config/
  MapLoader.java  [MODIFIED: add loadFromConfig()]
                  ─► MapValidator  (EXISTING)
                  ─► buildRoadNetwork()  (EXISTING, shared by both load paths)

backend engine/
  CommandDispatcher.java  [MODIFIED: add handleLoadConfig()]
                          ─► MapLoader.loadFromConfig()

backend engine/command/
  SimulationCommand.java  [MODIFIED: add LoadConfig record]
```

---

## 9. Build Order (Dependency-Driven)

The correct sequence respects what each step depends on:

**Step 1 — `MapLoader.loadFromConfig()`**
Required by everything else. Pure Java, no external deps, testable immediately.
Depends on: nothing new.

**Step 2 — `SimulationCommand.LoadConfig` + `CommandDispatcher.handleLoadConfig()`**
Required before the REST endpoint can trigger simulation loading.
Depends on: Step 1.

**Step 3 — `POST /api/map/load-config` endpoint**
Accepts a `MapConfig` JSON body, enqueues `LoadConfig` command.
Depends on: Steps 1 and 2.

**Step 4 — `MapAnalysisService` (Spring AI wiring, prompt, entity extraction)**
Can be stubbed initially to return a hardcoded `MapConfig` for testing Step 3.
Depends on: Spring AI dependency added to pom.xml. Steps 1-3 working.

**Step 5 — `POST /api/map/capture` endpoint (Static Maps API proxy)**
Fetches map image from Google, passes to MapAnalysisService.
Depends on: Step 4 working. Google Maps API key available.

**Step 6 — Frontend routing (`react-router-dom`)**
Add router to `App.tsx`, create empty `MapCapturePage`.
Depends on: nothing backend (can be parallelised with Step 4).

**Step 7 — `GoogleMapEmbed` + `CaptureOverlay`**
Map rendering + capturing viewport state (center/zoom).
Depends on: Step 6, `@vis.gl/react-google-maps`, VITE_GOOGLE_MAPS_API_KEY env var.

**Step 8 — `useMapCapture` hook**
Sends viewport state to backend, receives `MapConfig` JSON.
Depends on: Steps 5 and 7.

**Step 9 — `MapPreviewPanel` + "Load and Run" button**
Shows road graph, triggers `POST /api/map/load-config`, navigates to `/`.
Depends on: Steps 3, 7, 8.

**Step 10 — End-to-end test**
Full flow: open `/map` → pan map → capture → AI detects roads → preview → load → simulation runs.
Depends on: All above.

---

## 10. Anti-Patterns to Avoid

### Anti-Pattern 1: Screenshot the Google Maps JS map div directly

**What people try:** `html2canvas(mapDiv)` or `canvas.drawImage()` from the map div.
**Why it fails:** Cross-origin tiles taint the canvas. `canvas.toDataURL()` throws `SecurityError`. This is unbypassable without violating Google's ToS (you cannot proxy their tiles).
**Do this instead:** Record viewport state (lat/lng/zoom), proxy through backend to Static Maps API.

### Anti-Pattern 2: Validate AI output only on the frontend

**What people do:** Trust the AI's MapConfig shape and try to load it directly.
**Why it's wrong:** GPT-4o makes schema mistakes — wrong field names, missing required fields, negative values. Sending unvalidated config to `MapLoader` causes runtime exceptions inside the engine tick loop.
**Do this instead:** Run `MapValidator.validate()` server-side before returning to frontend, and return 422 with error list if invalid.

### Anti-Pattern 3: Inject AI-generated map into the simulation via the existing `LOAD_MAP` string command

**What people do:** Save the AI-generated MapConfig to classpath and pass a fake `mapId`.
**Why it's wrong:** Requires file I/O, classpath mutations at runtime, and creates naming collisions. The engine's classpath scan (via `PathMatchingResourcePatternResolver`) would pick up garbage files.
**Do this instead:** Add the `LoadConfig` command that accepts a `MapConfig` object directly. No file I/O.

### Anti-Pattern 4: Put the OpenAI API key in the frontend

**What people do:** Add `VITE_OPENAI_API_KEY` to the Vite environment and call OpenAI directly from React.
**Why it's wrong:** The key is exposed in the browser bundle. Rate-limit abuse, cost exposure, credential leak.
**Do this instead:** All AI calls go through the backend `MapAnalysisService`. The frontend only sends image bytes and receives `MapConfig` JSON.

### Anti-Pattern 5: Overfit the AI prompt for one map type

**What people do:** Tune the prompt on a single city's road style and ship it.
**Why it's wrong:** Road appearance varies by region, zoom level, and map style. A prompt tuned on US suburbs fails on European city centres.
**Do this instead:** Keep the prompt abstract (road type → speed limit inference, lane count estimation from road width). Accept that AI output quality varies; the user preview step is the correction gate.

---

## 11. External Service Summary

| Service | Usage | Authentication | Notes |
|---|---|---|---|
| Google Maps JavaScript API | Frontend map embed (user navigation) | `VITE_GOOGLE_MAPS_API_KEY` (client-side, restrict to domain) | `@vis.gl/react-google-maps` wrapper |
| Google Maps Static API | Backend map image fetch | `GOOGLE_MAPS_API_KEY` (server-side env var, never exposed) | Server-side HTTP call only |
| OpenAI API (GPT-4o) | Road detection from image | `OPENAI_API_KEY` (server-side env var) | Via Spring AI `ChatClient` |

Two separate Google Maps API keys are recommended: one restricted to the frontend domain (JS API), one unrestricted for server-side use (Static API). Or the same key with both APIs enabled and HTTP referrer + IP restrictions.

---

## Sources

- Existing codebase: `MapConfig.java`, `MapLoader.java`, `CommandDispatcher.java`, `SimulationEngine.java`, `useSimulationStore.ts`, `App.tsx` — read directly
- Spring AI multimodality + image structured output: https://vaadin.com/blog/java-ai-image-data-extraction-spring-ai
- Spring AI structured outputs announcement: https://spring.io/blog/2024/08/09/spring-ai-embraces-openais-structured-outputs-enhancing-json-response/
- @vis.gl/react-google-maps official library: https://visgl.github.io/react-google-maps/
- Google Maps Static API parameters: https://developers.google.com/maps/documentation/maps-static/start
- html2canvas Google Maps tainted canvas (known unresolved): https://github.com/niklasvh/html2canvas/issues/485
- OpenAI structured outputs with vision: https://platform.openai.com/docs/guides/structured-outputs

---

*Document covers: v2.0 milestone integration — Map Screenshot to Simulation*
*Researched: 2026-04-10*
