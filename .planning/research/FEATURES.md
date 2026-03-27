# Feature Research

**Domain:** Traffic simulation web application
**Researched:** 2026-03-27
**Confidence:** HIGH

---

## Feature Landscape

### Table Stakes

Features users expect from any credible traffic simulator. Absence of these causes immediate rejection.

| Feature | Description | Reference |
|---------|-------------|-----------|
| **Vehicle physics — car-following** | Vehicles maintain safe gaps, accelerate toward free-flow speed, decelerate smoothly when leader slows. Modelled by IDM (Intelligent Driver Model) or Nagel-Schreckenberg CA. Core of any microsimulation. | SUMO, VISSIM, IDM literature |
| **Vehicle physics — individual parameters** | Each vehicle has its own max speed, acceleration, deceleration, reaction time. Without this, traffic looks robotic and phantom jams cannot form naturally. | SUMO vehicle types |
| **Road network — multi-lane roads** | At minimum 1–4 lanes per direction. Lane boundaries must be respected; vehicles may not occupy two lanes simultaneously. | All microsimulators |
| **Road network — intersections** | Junction nodes where roads meet. Geometry defines which lane connects to which exit. | SUMO, VISSIM |
| **Traffic signals** | Green/yellow/red cycles at intersections. Configurable cycle duration and phase splits. This is the #1 intervention users want to experiment with. | SUMO tls, NetLogo models |
| **Lane changing** | Vehicles change lanes when blocked or when approaching a lane-drop. Without this, lane-level road narrowing effects cannot be observed. | SUMO MOBIL model |
| **Real-time top-down visualization** | 2D overhead view showing cars as rectangles on roads. Users cannot reason about emergent phenomena without seeing them unfold in real time. | All educational simulators |
| **Play / Pause / Speed controls** | Standard simulation controls (1x, 2x, 5x, 10x speed). Pause to inspect state. Without this, tool is unusable as an interactive tool. | NetLogo, SUMO-GUI |
| **Spawn rate control** | Control how many vehicles enter the network per unit time. This is the primary independent variable for congestion experiments. | All traffic models |
| **Statistics dashboard** | Real-time metrics: average speed, vehicle count/density, throughput (vehicles/min). Minimum viable feedback loop for experiments. | SUMO output, VISSIM |

---

### Differentiators

Features that go beyond the baseline and give this simulator a unique research/educational angle. Competitors rarely expose these as interactive controls.

| Feature | Value Proposition | Complexity |
|---------|-------------------|------------|
| **Live obstacle placement** | Drop a blocking obstacle in any lane while simulation runs. Instantly triggers shockwave propagation — the core educational moment. SUMO requires editing XML and restarting; no web tool does this live. | Medium |
| **Road narrowing simulation** | Reduce active lanes on a road segment mid-run. Models construction zones, accidents. Demonstrates merge-induced congestion and capacity reduction. | Medium |
| **Roundabout simulation** | Circular junction with yield-on-entry rules. Distinct traffic dynamics from signalised intersections — often outperforms signals at moderate volume. | Medium-High |
| **Phantom traffic jam visualisation** | Highlight the backward-propagating density wave that forms from nothing. With correct IDM parameters this emerges naturally from random deceleration events. No tool makes this visible as a named phenomenon. | Low (emergent) + Low UI overlay |
| **JSON-configurable maps** | Users can write/share road network configs as JSON without a graphical editor. Enables reproducible experiments and community sharing. SUMO uses XML (verbose); no lightweight JSON format exists. | Low |
| **Heatmap overlays** | Colour road segments by congestion intensity over time. Shows where jams form, how they move. Strong visual signal for educational use. | Medium (v2) |
| **Right-of-way / priority roads** | Uncontrolled intersections with priority-from-right rule. Completes the intersection taxonomy: signals + roundabout + priority. | Medium |
| **Configurable signal cycles via UI** | Change green/red duration on a running simulation and observe immediate downstream effects. Most simulators require restart. | Low |

---

### Anti-Features

Commonly requested features that should be explicitly declined. Each adds cost that exceeds educational value for this tool's purpose.

| Feature | Why It's Problematic |
|---------|----------------------|
| **3D rendering / perspective view** | 10x+ rendering complexity. No educational gain over top-down for studying flow dynamics. WebGL required — Canvas sufficient for 2D. Kills performance on mobile. |
| **AI/ML vehicle control** | Contradicts the project goal: we want to study emergent physics, not trained behaviour. ML agents mask the IDM dynamics that produce phantom jams. Enormous scope increase. |
| **Map editor (draw roads with mouse)** | High UI complexity (snap-to-grid, undo, validation). Not needed to validate simulation physics. Correctly deferred to v2; JSON config is sufficient for v1. |
| **Multiplayer / multi-user control** | WebSocket is already per-session. Shared simulation state requires conflict resolution, auth, and server-side isolation. No educational benefit over single-user. |
| **Pedestrians and cyclists** | Different physics model, different rendering, different right-of-way rules. Separate domain; scope doubles for minimal gain. |
| **Persistent storage / database** | Simulation is stateful in-memory by design. Adding a DB creates operational burden with no user-facing benefit — sessions are exploratory, not longitudinal. |
| **User authentication** | Single-user local tool. Auth adds complexity with zero value. |
| **Route planning / navigation** | GPS-style routing is a different problem domain. Vehicles in this simulator follow road topology, not optimal paths. |
| **Real-world map import (OSM)** | OpenStreetMap parsing requires significant data cleaning, coordinate projection, and topology repair. Complexity far exceeds educational value. v3 at earliest. |
| **Accident / crash simulation** | Collision detection requires response rules, cleanup logic, chain-reaction handling. Not necessary for congestion research; adds disturbing visual noise. |

---

## Feature Dependencies

```
JSON Map Config
    └── Road Network (lanes, geometry)
            ├── Traffic Signals
            │       └── Configurable Signal Cycles (UI)
            ├── Roundabout (yield rules)
            ├── Right-of-way / Priority
            └── Lane Changing (MOBIL)
                    ├── Road Narrowing
                    └── Obstacle Placement (live)

Vehicle Physics (IDM)
    ├── Individual Vehicle Parameters
    ├── Car-Following (gap maintenance)
    └── Phantom Traffic Jam (emergent — no extra code)

WebSocket Real-Time Tick
    └── Canvas Rendering (top-down)
            ├── Play/Pause/Speed Controls
            ├── Spawn Rate Control
            ├── Statistics Dashboard
            └── Heatmap Overlay (v2, needs accumulated tick data)
```

Key constraint: **Vehicle Physics** and **Road Network** must both be complete before any intersection type (signals, roundabout, priority) can be implemented. **Lane Changing** is required for Road Narrowing to have visible effect. Heatmap is a pure overlay that depends only on accumulated statistics — safe to defer.

---

## MVP Definition

The smallest runnable simulation that demonstrates phantom traffic jams.

**MVP = these features, nothing else:**

1. Single straight multi-lane road (no intersections)
2. IDM car-following with individual vehicle parameters
3. Spawn rate control (vehicles enter from one end, exit at other)
4. Live obstacle placement (block one lane)
5. Play / Pause / Speed controls (1x, 2x, 5x)
6. Canvas top-down rendering, vehicles as coloured rectangles
7. Basic stats: average speed + vehicle count
8. WebSocket tick delivery (backend → frontend)
9. Hardcoded map (no JSON loader yet)

**What MVP proves:** The physics engine produces realistic following behaviour and shockwave propagation. Everything else is additive.

**MVP excludes:** Intersections, signals, roundabouts, lane changing, JSON config, heatmap.

**Definition of Done for MVP:**
- Drop an obstacle → traffic backs up behind it within 10 seconds of sim time
- Remove obstacle → jam dissipates naturally (not instantly)
- Average speed metric visibly drops when jam forms

---

## Feature Prioritization Matrix

Scoring: **Value** (1–5, educational/user impact) × **Effort** (1–5, implementation cost, inverted so low effort = high score).

| Feature | Value | Effort | Score | Priority |
|---------|-------|--------|-------|----------|
| IDM car-following physics | 5 | 2 (high) | 10 | P0 — MVP |
| Individual vehicle parameters | 4 | 5 (low) | 20 | P0 — MVP |
| Canvas top-down rendering | 5 | 3 | 15 | P0 — MVP |
| WebSocket tick delivery | 5 | 2 | 10 | P0 — MVP |
| Play/Pause/Speed controls | 5 | 4 | 20 | P0 — MVP |
| Spawn rate control | 5 | 4 | 20 | P0 — MVP |
| Obstacle placement (live) | 5 | 3 | 15 | P0 — MVP |
| Basic stats dashboard | 4 | 4 | 16 | P0 — MVP |
| Multi-lane roads + lane topology | 4 | 2 | 8 | P1 |
| Lane changing (MOBIL) | 4 | 2 | 8 | P1 |
| Road narrowing simulation | 5 | 3 | 15 | P1 |
| Traffic signals + configurable cycles | 4 | 3 | 12 | P1 |
| JSON map configuration | 4 | 4 | 16 | P1 |
| Predefined map scenarios | 3 | 4 | 12 | P1 |
| Roundabout simulation | 4 | 2 | 8 | P2 |
| Right-of-way / priority intersections | 3 | 3 | 9 | P2 |
| Phantom jam visualisation overlay | 4 | 3 | 12 | P2 |
| Heatmap congestion overlay | 3 | 2 | 6 | P3 — v2 |
| Map editor (graphical) | 2 | 1 | 2 | Backlog — v2 |

**Priority tiers:**
- **P0 — MVP:** Physics engine + rendering + basic controls. Goal: prove the simulation works.
- **P1 — v1 Complete:** Full road network, all intersection types, JSON config, scenarios.
- **P2 — v1 Polish:** Visual aids, roundabouts, less-common intersection types.
- **P3 / Backlog:** Deferred post-validation.

---

## Sources

- **SUMO (Simulation of Urban MObility)** — Eclipse project, leading open-source microsimulator. Feature set documented at sumo.dlr.de. Reference for: vehicle types, traffic lights, lane-changing (MOBIL), junction types.
- **Intelligent Driver Model (IDM)** — Treiber, Hennecke, Helbing (2000). "Congested Traffic States in Empirical Observations and Microscopic Simulations." Primary car-following model for this project.
- **Nagel-Schreckenberg cellular automaton** — Discrete-time traffic model. Origin of phantom jam research in computational form.
- **NetLogo Traffic models** — Educational CA-based traffic models (Traffic Basic, Traffic 2 Lanes). Reference for educational feature set and UI conventions.
- **VISSIM (PTV Group)** — Commercial microsimulator. Reference for professional feature completeness and statistics output.
- **Phantom traffic jam research** — Sugiyama et al. (2008), circular road experiment. Validates IDM parameter ranges that produce spontaneous jams.
- **PROJECT.md** — /home/sebastian/traffic-simulator/.planning/PROJECT.md. Primary requirements source; features above are cross-referenced against stated Active requirements and Out of Scope items.
