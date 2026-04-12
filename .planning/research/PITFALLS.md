# Pitfalls Research

**Domain:** Map Screenshot to Traffic Simulation Conversion
**Researched:** 2026-04-10
**Confidence:** HIGH (Google ToS), MEDIUM (coordinate systems, AI detection), MEDIUM (topology conversion)

---

## Critical Pitfalls

### Pitfall 1: Google Maps ToS Prohibits Using Map Content to Train or Feed ML/AI

**What goes wrong:**
The project captures a Google Maps screenshot and passes it to an AI/CV model to detect roads. This directly violates the Google Maps Platform Terms of Service. The ToS explicitly states: customers "will not use Google Maps Content to improve machine learning and artificial intelligence models, including to train, test, validate or fine-tune the models." Additionally, "customers will not create content based on Google Maps Content" and specifically will not "trace or digitize roadways... from the Maps JavaScript API Satellite base map type."

**Why it happens:**
The ToS restriction is easy to overlook because Google Maps is the most convenient data source for road layout. Developers assume that if they have a paid API key, they can do anything with the rendered output.

**How to avoid:**
Use an alternative map source whose license permits derivative work and AI processing:
- **OpenStreetMap (OSM)** data is published under ODbL which permits derivative work including AI analysis. Render tiles via MapTiler, Stadia Maps, or self-hosted tile server. OSM data is also directly queryable as structured road vectors via Overpass API — potentially bypassing the need for AI detection entirely.
- **Mapbox** Terms permit screenshot/static image use for display but not for AI training. Read Mapbox ToS carefully before using.
- **Static Maps API** (Google) still applies the same content restrictions.

If Google Maps is kept for display/navigation on the map page, the AI analysis must use a separately sourced image (e.g., OSM tiles for the same viewport), not the Google Maps canvas.

**Warning signs:**
- The implementation passes `map.getDiv()` canvas content or `html2canvas` output of the Google Maps iframe directly to an AI API call.
- Any prompt to an AI model includes "this is a Google Maps screenshot."

**Phase to address:**
Map page architecture/design phase — before any map page code is written.

---

### Pitfall 2: Screenshot of Google Maps iframe is Technically Blocked by CORS

**What goes wrong:**
Google Maps renders inside a cross-origin iframe. `html2canvas`, `dom-to-image`, and similar DOM-capture libraries cannot capture cross-origin iframe content — the browser blocks it with a security error. The capture silently produces a blank rectangle where the map should be, or throws a CORS exception.

**Why it happens:**
Developers see the map rendered on screen and assume `html2canvas` over the parent div will capture it. The iframe security model prevents this. Google Maps JavaScript API does not expose a programmatic method to export the current view as an image.

**How to avoid:**
Use the **Maps Static API** endpoint — construct a Static API URL using the current center/zoom/size parameters from the live map and fetch that as the image to analyze. Or switch to a map library (Leaflet + OSM tiles, Mapbox GL JS) that renders to a WebGL/Canvas element you control, which can be exported via `canvas.toDataURL()`.

Do NOT use `html2canvas` on a page containing Google Maps.

**Warning signs:**
- Implementation uses `html2canvas` or `domtoimage` anywhere near the map div.
- `canvas.toDataURL()` is called on a canvas that has loaded cross-origin tiles (also blocked unless tiles include CORS headers).

**Phase to address:**
Screenshot capture proof-of-concept phase. Validate the capture mechanism before building the AI analysis pipeline.

---

### Pitfall 3: Pixel Coordinates Not Converted to Real-World Meters Before IDM Physics

**What goes wrong:**
The AI/CV pipeline outputs road geometry in pixel coordinates of the screenshot (e.g., road is 340px long, 8px wide, intersection at pixel (212, 178)). These pixel values are imported directly into the simulation JSON as road `length` and `position` fields. The IDM physics engine expects lengths in meters and speeds in m/s. At zoom level 17, 1 pixel is approximately 1.19 meters; at zoom level 15, approximately 4.77 meters. Using pixel values as meters produces physically nonsensical simulations: cars with 30 m/s max speed traverse a 340-meter "road" in 11 seconds when it should take around 45 seconds, or the safe-following-distance (IDM parameter `s0`, typically 2m) dwarfs the "road length" of 8 pixels.

**Why it happens:**
Pixel-to-meter conversion requires knowing the zoom level and latitude of the captured viewport — values available during capture but easy to not persist into the analysis pipeline. The conversion formula is:

```
meters_per_pixel = 156543.03392 * cos(latitude_radians) / (2 ^ zoom_level)
```

This formula is not obvious, and the latitude-dependent cosine factor means screenshots from different cities have different scales at the same zoom level.

**How to avoid:**
At capture time, record and embed in the image metadata (or pass as a side-channel JSON):
1. Center latitude/longitude of the viewport
2. Current zoom level
3. Screenshot pixel dimensions

In the conversion pipeline, compute `meters_per_pixel` from these values and scale all detected pixel distances to meters before writing to the simulation JSON. Add a validation step: a typical city block is 80–200 meters; if detected roads are outside 20m–2000m, reject or flag the output.

**Warning signs:**
- The conversion pipeline only accepts an image file with no associated geographic metadata.
- Simulation output shows cars traversing roads in under 3 seconds (too short) or over 10 minutes (too long).
- Road widths in JSON are single-digit numbers when they should be 3–5 meters per lane.

**Phase to address:**
Coordinate conversion layer, immediately after AI detection produces pixel-space output.

---

### Pitfall 4: AI Road Detection Produces Disconnected Segments Instead of a Connected Graph

**What goes wrong:**
Semantic segmentation models (whether a dedicated road-detection model or GPT-4o vision) label pixels as "road" or "not road." Converting this raster mask to a vector road graph requires a vectorization/skeletonization step. The skeleton often breaks at intersections (where multiple roads meet, the thinning algorithm creates T-junctions with gaps or spurious branches), at image tile edges (the model has no context beyond the image boundary), and under occlusions (trees, buildings overlapping roads). The result: the simulation receives roads that dead-end at intersections or have phantom branches, causing vehicles to stop at non-existent dead-ends or be unable to navigate turns.

**Why it happens:**
Researchers describe this as the fundamental topology recovery problem: "graph-based approaches usually suffer from incorrect crossroad connectivity and false disconnection on the road." Developers underestimate this step — the segmentation mask looks correct visually, but the graph extraction is the hard part.

**How to avoid:**
- Build an explicit post-processing validation step that checks the extracted graph: every intersection node must have at least 3 connected road segments; no road segment should terminate within the image bounds unless it is at the image edge.
- Use a snap-to-grid or node merging step: road endpoints within N pixels of each other should be merged into a single intersection node.
- Consider using OSM structured data directly via Overpass API instead of image-based detection — the data already encodes correct topology.
- If using AI detection, add a manual review/edit UI step where the user can correct topology before the simulation is generated.

**Warning signs:**
- Detected road count is much higher than visually expected (spurious branches from thinning).
- Simulation vehicles stop at unexpected points mid-network.
- Intersection nodes with only 1 or 2 connected roads appear in the graph.

**Phase to address:**
AI detection output post-processing phase, before topology-to-simulation conversion.

---

### Pitfall 5: Intersection Inbound/Outbound Road Mapping Is Wrong

**What goes wrong:**
The existing simulation engine (Java backend) uses an `Intersection` model with explicit `inboundRoads` and `outboundRoads` lists and turn routing logic. When importing from a detected road graph, the direction (which end of a road segment is "inbound" to an intersection) must be computed from geometry. A naive approach assigns roads randomly as inbound or outbound, or treats all connected roads as bidirectional when some are one-way. The result: vehicles enter intersections but cannot exit (no valid outbound road), or the traffic flow direction is reversed from the real map.

**Why it happens:**
In the manual JSON format, a human author consciously assigns inbound/outbound. In automated conversion from a detected graph, directionality must be inferred from either one-way tags (only available if using OSM data) or heuristics (road bearing angles). Map images contain no directionality information — a two-lane road looks the same as a one-way street visually.

**How to avoid:**
- Default all detected roads to bidirectional (both inbound and outbound at each connected intersection). This is physically wrong for one-way streets but produces a functioning simulation.
- If using OSM data, extract `oneway` tags and apply them correctly.
- Document in the simulation JSON schema that `inboundRoads`/`outboundRoads` at an intersection are directional, and validate after import: at every intersection, count inbound and outbound connections — an intersection with 0 outbound roads is invalid.

**Warning signs:**
- Vehicles accumulate at one intersection and the queue never drains.
- Console errors like "no outbound road found for intersection X."
- The simulation runs but vehicles never complete trips.

**Phase to address:**
Simulation JSON schema generation phase (topology-to-JSON converter).

---

### Pitfall 6: Google Maps API Key Exposed in Frontend Bundle

**What goes wrong:**
The Google Maps JavaScript API key is embedded in the React frontend (either hardcoded in source or in a `VITE_` environment variable that gets bundled). The key appears in plain text in the browser's network requests and in the compiled JS bundle. Anyone can extract it and use it to incur billing charges. In late 2025, researchers found 2,863 exposed Google API keys on the public internet — including at major financial institutions — that could authenticate to the Gemini API in addition to Maps.

**Why it happens:**
Developers set `VITE_GOOGLE_MAPS_API_KEY` in `.env` and consider this "hidden." Vite `VITE_` variables are explicitly designed to be bundled into client-side code — they are not secret.

**How to avoid:**
The Maps JavaScript API key exposed in the frontend cannot be fully hidden — this is by design. The correct mitigation is API key restrictions in the Google Cloud Console:
1. Restrict the key to HTTP referrers (only your domain).
2. Restrict the key to the Maps JavaScript API only.
3. Set a billing alert and quota cap.
4. Do NOT use the same key for backend calls (Static API, Geocoding, etc.) — create separate keys for each.

Never use an unrestricted key in the frontend.

**Warning signs:**
- The same API key is used for both frontend Maps JS embed and any backend API calls.
- The key has no HTTP referrer restriction in Google Cloud Console.
- The key appears in a `.env` file committed to the repo (even in history).

**Phase to address:**
Map page setup phase, before any key is written into source code.

---

## Technical Debt Patterns

Shortcuts that seem reasonable but create long-term problems.

| Shortcut | Immediate Benefit | Long-term Cost | When Acceptable |
|----------|-------------------|----------------|-----------------|
| Use pixel coordinates directly in simulation JSON | Skip coordinate conversion math | Physically wrong simulation; all IDM tuning breaks | Never |
| Keep Google Maps as AI analysis source | Simpler architecture | ToS violation, potential account ban, legal exposure | Never |
| Skip post-processing topology validation | Faster first demo | Dead-end roads cause runtime simulation errors | Never in production |
| Hardcode API key without HTTP referrer restriction | Faster dev setup | Unlimited billing exposure; key may access Gemini API | Never, not even in dev (use dev-only key with quota cap) |
| Treat all roads as bidirectional | Correct intersections easier to implement | One-way streets simulated incorrectly | Acceptable for MVP; document explicitly |
| Use OSM Overpass API data instead of AI image detection | Avoids AI accuracy issues, no ToS risk | Requires learning Overpass query language | Strongly recommended alternative path for topology |

---

## Integration Gotchas

Common mistakes when connecting to external services.

| Integration | Common Mistake | Correct Approach |
|-------------|----------------|------------------|
| Google Maps JS API | Calling `html2canvas` on the map div to capture screenshot | Use Maps Static API URL with same center/zoom, or switch to Leaflet+OSM which exposes a real canvas |
| OpenAI / AI vision API | Sending raw screenshot pixels, expecting structured road coordinates back | Prompt must request structured JSON output with normalized coordinates; validate schema before use |
| Google Maps API key | One key for all purposes | Separate keys per API product, each with appropriate restrictions |
| OSM Overpass API | No rate limiting, hammering the public instance | Use the overpass-api.de endpoint with ≤1 req/sec; for higher volume use a local Overpass instance |
| IDM physics engine | Importing road lengths without unit check | Always assert `road.length > 10` (meters) and `road.length < 5000` after import |

---

## Performance Traps

Patterns that work at small scale but fail as usage grows.

| Trap | Symptoms | Prevention | When It Breaks |
|------|----------|------------|----------------|
| Calling AI vision API synchronously during capture | Frontend hangs for 5–30 seconds on "Capture" button press | Process asynchronously; show loading state; stream partial results if API supports it | Immediately on first use |
| Sending full-resolution screenshot to AI API | Slow API response; high token cost (GPT-4o vision charges per image tile) | Resize image to 1024x1024 before API call; most road detection does not benefit from higher resolution | Every request |
| Re-analyzing same screenshot on every page visit | Repeated API costs | Cache detection results by image hash; store analyzed JSON in session | After a few repeated uses |
| Loading full OSM road network for a city | Overpass query returns 100K+ roads; browser freezes | Bound the Overpass query to the viewport bounding box only | When viewport is larger than a few city blocks |

---

## Security Mistakes

Domain-specific security issues beyond general web security.

| Mistake | Risk | Prevention |
|---------|------|------------|
| Unrestricted Google Maps API key in frontend | Billing fraud; key may also access Gemini API per 2025 research | HTTP referrer + API restriction in Cloud Console; separate keys per API |
| Passing user-uploaded images to AI API without validation | Oversized images causing timeout/cost spikes; malicious metadata | Validate file type, strip EXIF metadata, enforce size limit (max 5MB) before sending |
| AI model returns road geometry used without sanitization | Malformed coordinates crash simulation engine | Validate all AI-returned coordinates are within image bounds and within expected range before import |
| Backend proxying AI API without rate limiting | Endpoint abused to rack up AI API costs | Rate-limit the `/api/analyze-map` endpoint; require session/CSRF token |

---

## UX Pitfalls

Common user experience mistakes in this domain.

| Pitfall | User Impact | Better Approach |
|---------|-------------|-----------------|
| No feedback during AI analysis (5–30 seconds) | User thinks app froze; clicks Capture again causing double analysis | Show progress spinner + "Analyzing roads..." message; disable Capture button during processing |
| Showing raw AI output (pixel coordinates, JSON) before simulation loads | Confusing; not actionable | Show a preview overlay of detected roads on the original screenshot before importing to simulation |
| No way to correct bad AI detection before simulation starts | User sees broken simulation with no recourse | Provide a "detected roads" review step where user can accept/reject before loading |
| Simulation fails silently if topology is invalid | User sees empty canvas with no error | Validate the imported map JSON and display specific errors: "Intersection 3 has no outbound roads" |
| Map capture at wrong zoom level | Simulation is unworkably large (zoom 12) or contains only one intersection (zoom 19) | Recommend or enforce zoom range 16–18 for best road detection; show guidance message |

---

## "Looks Done But Isn't" Checklist

Things that appear complete but are missing critical pieces.

- [ ] **Screenshot capture:** Verify the captured image actually contains road pixels, not a blank white or black rectangle — the CORS iframe failure is silent.
- [ ] **Coordinate conversion:** Verify `meters_per_pixel` is computed and applied — check road lengths in simulation JSON are in range 30m–2000m.
- [ ] **Topology connectivity:** Verify every intersection node in the extracted graph has at least one inbound and one outbound road.
- [ ] **IDM physics sanity:** Run a single-vehicle test on the imported road — vehicle should complete a city block (100m) in 5–15 seconds at 30 km/h.
- [ ] **API key restriction:** Confirm in Google Cloud Console that the Maps JS key has HTTP referrer restriction set before first deploy.
- [ ] **ToS compliance:** Confirm the image source used for AI analysis is NOT Google Maps content — must be OSM, or user-uploaded image.
- [ ] **AI output schema validation:** Confirm the code handles malformed AI responses gracefully (try/catch + user-visible error, not crash).

---

## Recovery Strategies

When pitfalls occur despite prevention, how to recover.

| Pitfall | Recovery Cost | Recovery Steps |
|---------|---------------|----------------|
| ToS violation discovered post-launch | HIGH | Swap map provider to OSM/Mapbox; rebuild capture flow; API key may be suspended forcing immediate action |
| CORS screenshot capture returns blank | LOW | Switch to Static API URL or alternative map library; affects only one component |
| Pixel coordinates used as meters throughout | HIGH | Add conversion layer in both frontend (capture metadata) and backend (simulation import); re-test all IDM physics |
| Topology disconnection errors in simulation | MEDIUM | Add post-processing snap/merge step in converter; does not affect map display or capture |
| API key billing fraud | MEDIUM | Revoke and regenerate key immediately; add restrictions; dispute fraudulent charges with Google |
| AI detection produces wrong road structure | LOW-MEDIUM | Add manual correction UI step; the simulation engine itself does not need changes |

---

## Pitfall-to-Phase Mapping

How roadmap phases should address these pitfalls.

| Pitfall | Prevention Phase | Verification |
|---------|------------------|--------------|
| Google Maps ToS for AI analysis | Architecture/design phase — before any map page code | Confirm image source for AI is OSM or equivalent; document the decision |
| CORS screenshot capture failure | Map page proof-of-concept phase | Capture test: assert returned image is non-blank and contains road pixels |
| Pixel-to-meter coordinate conversion | Coordinate conversion layer phase | Unit test: zoom 17 at 52°N latitude → `meters_per_pixel` ≈ 0.75; imported road lengths in 20–2000m range |
| Disconnected topology graph | AI detection post-processing phase | Graph validation: every node has at least 1 inbound and 1 outbound edge |
| Wrong inbound/outbound road mapping | Simulation JSON generation phase | Simulation test: single vehicle navigates a loop without getting stuck |
| API key exposure | Map page setup (day one) | Google Cloud Console: key shows HTTP referrer restriction before any frontend deploy |
| IDM units wrong | Physics validation phase | IDM test: car at 13.9 m/s (50 km/h) covers 100m road in approximately 7 seconds |

---

## Sources

- Google Maps Platform Service Specific Terms (content use, ML prohibition): https://cloud.google.com/maps-platform/terms/maps-service-terms
- Google Maps Platform Terms of Service (general): https://cloud.google.com/maps-platform/terms
- Google Maps API Security Best Practices: https://developers.google.com/maps/api-security-best-practices
- html2canvas cross-origin iframe restriction (GitHub issue #1532): https://github.com/niklasvh/html2canvas/issues/1532
- Google Maps zoom levels and meters-per-pixel: https://wiki.openstreetmap.org/wiki/Zoom_levels
- MapTiler tile coordinate system and Mercator projection: https://docs.maptiler.com/google-maps-coordinates-tile-bounds-projection/
- DeepRoadMapper topology error analysis: https://openaccess.thecvf.com/content_ICCV_2017/papers/Mattyus_DeepRoadMapper_Extracting_Road_ICCV_2017_paper.pdf
- Segment Anything Model for road network graph extraction (topology gaps): https://arxiv.org/html/2403.16051v1
- GPT-4o vision limitations for spatial/geometric tasks: https://community.openai.com/t/best-openai-model-for-image-analysis-in-2025-gpt-4o-gpt-4o-mini-or-something-else/1359595
- Google API key exposure risk (Gemini API access, 2025): https://trufflesecurity.com/blog/google-api-keys-werent-secrets-but-then-gemini-changed-the-rules
- SUMO road network inbound/outbound intersection model: https://sumo.dlr.de/docs/Simulation/Intersections.html
- IDM physics parameters and units (meters, m/s): https://traffic-simulation.de/info/info_IDM.html

---
*Pitfalls research for: Map Screenshot to Traffic Simulation Conversion (v2.0 milestone)*
*Researched: 2026-04-10*
