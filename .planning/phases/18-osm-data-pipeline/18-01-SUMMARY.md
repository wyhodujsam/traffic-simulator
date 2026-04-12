---
phase: 18-osm-data-pipeline
plan: 01
subsystem: backend
tags: [osm, overpass-api, map-config, command-chain, tdd]
dependency_graph:
  requires: []
  provides:
    - OsmPipelineService.fetchAndConvert(BboxRequest) → MapConfig
    - OsmPipelineService.convertOsmToMapConfig(String, BboxRequest) → MapConfig
    - MapLoader.loadFromConfig(MapConfig) → LoadedMap
    - SimulationCommand.LoadConfig record in sealed interface
    - CommandDispatcher.handleLoadConfig() handler
  affects:
    - SimulationCommand sealed interface (new permit)
    - CommandDispatcher handler table (new entry)
    - MapLoader (new public method)
tech_stack:
  added:
    - Spring RestClient (overpassRestClient @Bean)
    - BboxRequest record DTO
  patterns:
    - TDD (RED → GREEN for both tasks)
    - Haversine distance for road length
    - Linear WGS84 → pixel coordinate projection
    - Highway-type default lane counts
key_files:
  created:
    - backend/src/main/java/com/trafficsimulator/service/OsmPipelineService.java
    - backend/src/main/java/com/trafficsimulator/dto/BboxRequest.java
    - backend/src/main/java/com/trafficsimulator/config/OsmClientConfig.java
    - backend/src/test/java/com/trafficsimulator/service/OsmPipelineServiceTest.java
    - backend/src/test/java/com/trafficsimulator/config/MapLoaderLoadFromConfigTest.java
  modified:
    - backend/src/main/java/com/trafficsimulator/engine/command/SimulationCommand.java
    - backend/src/main/java/com/trafficsimulator/config/MapLoader.java
    - backend/src/main/java/com/trafficsimulator/engine/CommandDispatcher.java
decisions:
  - "Test 10 (missing nodes): service correctly throws IllegalStateException when all ways are skipped due to unresolvable nodes — test updated to assert exception rather than empty roads list"
  - "Signal phases: when a traffic_signals node has only 1 inbound road, 2 identical phases generated — MapValidator requires non-empty signalPhases for SIGNAL type"
metrics:
  duration_minutes: 25
  tasks_completed: 2
  tasks_total: 2
  files_created: 5
  files_modified: 3
  tests_added: 20
  tests_total: 216
  completed_date: "2026-04-12"
---

# Phase 18 Plan 01: OSM Data Pipeline — Backend Summary

**One-liner:** Overpass API client + OSM way/node JSON → MapConfig converter with LoadConfig command chain through sealed interface, all via TDD.

## Tasks Completed

| # | Name | Commit | Files |
|---|------|--------|-------|
| 1 | LoadConfig command chain + MapLoader.loadFromConfig() | 88f9ae8 | SimulationCommand.java, MapLoader.java, CommandDispatcher.java, MapLoaderLoadFromConfigTest.java |
| 2 | OsmPipelineService — Overpass client + converter | c93f9d9 | OsmPipelineService.java, BboxRequest.java, OsmClientConfig.java, OsmPipelineServiceTest.java |

## What Was Built

### Task 1: LoadConfig command chain

- `SimulationCommand.LoadConfig(MapConfig config)` record added to sealed interface `permits` clause with `MapConfig` import
- `MapLoader.loadFromConfig(MapConfig)` — validates via `MapValidator.validate()`, throws `IllegalArgumentException("Map config validation failed: ...")` if errors, then calls existing `buildRoadNetwork()`, returns `LoadedMap`
- `CommandDispatcher.handleLoadConfig()` — same stop/reset/load pattern as `handleLoadMap`, catches `IllegalArgumentException` and sets engine `lastError`
- 6 unit tests in `MapLoaderLoadFromConfigTest` covering: valid config → LoadedMap with road count, invalid config → exception with "validation failed", defaultSpawnRate preservation, LoadConfig record instantiation, network ID, spawn/despawn points

### Task 2: OsmPipelineService

**Overpass API client (`fetchAndConvert`):**
- Constructs OverpassQL query with bbox-filtered highway types
- POSTs to `/api/interpreter` as `application/x-www-form-urlencoded`
- URL-encodes query with `URLEncoder.encode(query, UTF_8)`
- `OsmClientConfig` @Bean with `${osm.overpass.url:https://overpass-api.de}` default

**OSM converter (`convertOsmToMapConfig`):**
- 2-pass parsing: nodes into `Map<Long, OsmNode>`, ways into `List<OsmWay>`
- Node reference counting → intersection detection (refCount ≥ 2 OR `highway=traffic_signals`)
- Terminal node detection (first/last node of way with refCount == 1)
- Road generation per way: skips ways with <2 resolvable nodes; computes Haversine length; oneway=yes/roundabout=fwd-only, oneway=-1=reversed, default=fwd+rev
- Highway-type speed limits (m/s): motorway=36.1, trunk=27.8, primary=19.4, secondary=16.7, tertiary=13.9, unclassified=11.1, residential=8.3, living_street=2.8
- Lane count from `lanes=X` tag clamped to [1,4]; highway defaults: motorway/trunk/primary/secondary=2, others=1
- NodeConfig type: INTERSECTION for shared/signal nodes, ENTRY/EXIT for terminals based on road direction
- IntersectionConfig: SIGNAL with 2-phase 30s default phases for traffic_signals nodes; ROUNDABOUT with capacity=8; PRIORITY for other shared nodes
- SpawnPoint/DespawnPoint for each lane at terminal nodes (position=0.0 for spawn, position=road.length for despawn)
- MapConfig ID: `osm-bbox-{south:.4f}-{west:.4f}-{north:.4f}-{east:.4f}`
- Throws `IllegalStateException("No roads found in selected area")` if no roads after filtering

**14 unit tests** covering all converter behaviors with synthetic Overpass JSON strings.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Test 10 expectation corrected**
- **Found during:** Task 2 GREEN phase
- **Issue:** Test 10 (`wayWithMissingNodes_isSkipped`) expected `config.getRoads().isEmpty()` but the service correctly throws `IllegalStateException("No roads found")` when all ways are skipped — same behavior as the empty-elements test (Test 11)
- **Fix:** Updated test to assert `IllegalStateException` with "No roads found" message — this is the correct behavior; returning an empty-roads MapConfig would fail MapValidator anyway
- **Files modified:** `OsmPipelineServiceTest.java`
- **Commit:** c93f9d9

## Known Stubs

None — all converter logic is fully wired. `fetchAndConvert()` makes real HTTP calls to Overpass API (mocked in tests by testing `convertOsmToMapConfig()` directly).

## Self-Check: PASSED

All created files verified:
- [x] `backend/src/main/java/com/trafficsimulator/service/OsmPipelineService.java` — exists, 340+ lines
- [x] `backend/src/main/java/com/trafficsimulator/dto/BboxRequest.java` — exists
- [x] `backend/src/main/java/com/trafficsimulator/config/OsmClientConfig.java` — exists
- [x] `backend/src/test/java/com/trafficsimulator/service/OsmPipelineServiceTest.java` — exists, 14 tests
- [x] `backend/src/test/java/com/trafficsimulator/config/MapLoaderLoadFromConfigTest.java` — exists, 6 tests

Commits verified:
- [x] 88f9ae8 — Task 1
- [x] c93f9d9 — Task 2

All 216 tests pass (BUILD SUCCESS).
