# Phase 11: Architecture Refactoring - Context

**Gathered:** 2026-03-29
**Status:** Ready for planning
**Source:** PRD Express Path (/home/sebastian/traffic-simulator/ar-improvement.md)

<domain>
## Phase Boundary

Refactor existing architecture from 6/10 to 9/10 quality rating. 8 refactoring steps targeting encapsulation, separation of concerns, domain model enrichment, API decoupling, performance optimization, and thread safety. No new features — all existing 86 backend + 33 frontend tests must pass after each step.

</domain>

<decisions>
## Implementation Decisions

### Step 1: Lane/RoadNetwork Collection Encapsulation
- Lane: make vehicles/obstacles private, add addVehicle/removeVehicle/getVehiclesView/clearVehicles + analogous obstacle methods
- RoadNetwork: add getAllLanes/getAllVehicles/findRoad/findLane query methods
- Touches: VehicleSpawner, LaneChangeEngine, IntersectionManager, SimulationEngine, TickEmitter, PhysicsEngine

### Step 2: Engine Interfaces + Dependency Inversion
- New interfaces: IPhysicsEngine, ILaneChangeEngine, ITrafficLightController, IIntersectionManager, IVehicleSpawner
- Existing classes implement their interfaces
- TickEmitter/LaneChangeEngine depend on interfaces, not concrete classes

### Step 3: Extract SnapshotBuilder from TickEmitter
- New @Component SnapshotBuilder with buildSnapshot, projectVehicle, projectObstacle, buildTrafficLightDtos
- TickEmitter shrinks from ~260 to ~80 lines
- Add SnapshotBuilderTest

### Step 4: Extract CommandDispatcher from SimulationEngine
- New @Component CommandDispatcher with dispatch() and private handler methods
- SimulationEngine keeps only enqueue/drainCommands/state getters
- Implement LoadMap handler (was missing — bug C2)
- SimulationEngine shrinks from ~250 to ~100 lines

### Step 5: Enrich Vehicle Domain Model
- Add updatePhysics(pos, speed, accel) — single mutation point with validation
- Add startLaneChange/advanceLaneChangeProgress/completeLaneChange/isInLaneChange/canChangeLane
- Remove public setPosition/setSpeed/setAcceleration/setLane setters
- Keep setters for: forceLaneChange, zipperCandidate (external flags)

### Step 6: Move Pixel Projection to Frontend
- Backend: VehicleDto/ObstacleDto send roadId, laneId, position (metres) instead of x,y,angle (pixels)
- Frontend: new projection.ts with projectVehicle/projectObstacle functions
- API contract change — backend and frontend must deploy together

### Step 7: O(n) Leader Lookup Optimization
- Lane: sorted ArrayList instead of unsorted, resort once per tick after physics
- getLeader/findLeaderAt/findFollowerAt become O(log n) via binary search
- PhysicsEngine calls lane.resortVehicles() at end of tick

### Step 8: ReentrantReadWriteLock Synchronization
- SimulationEngine: add networkLock field
- TickEmitter: readLock around entire tick pipeline
- CommandDispatcher: writeLock around each mutation
- Test: 100 threads sending commands during active tick

### Claude's Discretion
- Internal implementation details within each step
- Exact method signatures (follow patterns from ar-improvement.md)
- Test structure and assertions

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Architecture Improvement Plan
- `/home/sebastian/traffic-simulator/ar-improvement.md` — Full 8-step refactoring plan with code examples and dependency graph

### Existing Code (targets of refactoring)
- `backend/src/main/java/com/trafficsimulator/model/Lane.java`
- `backend/src/main/java/com/trafficsimulator/model/Vehicle.java`
- `backend/src/main/java/com/trafficsimulator/model/RoadNetwork.java`
- `backend/src/main/java/com/trafficsimulator/engine/PhysicsEngine.java`
- `backend/src/main/java/com/trafficsimulator/engine/SimulationEngine.java`
- `backend/src/main/java/com/trafficsimulator/engine/LaneChangeEngine.java`
- `backend/src/main/java/com/trafficsimulator/engine/VehicleSpawner.java`
- `backend/src/main/java/com/trafficsimulator/engine/IntersectionManager.java`
- `backend/src/main/java/com/trafficsimulator/engine/TrafficLightController.java`
- `backend/src/main/java/com/trafficsimulator/scheduler/TickEmitter.java`

</canonical_refs>

<specifics>
## Specific Ideas

- Dependency graph from PRD: Steps 1+2+4 parallel, then 3+5+7 after 1, then 6 after 3, then 8 after 4
- Each step must maintain green tests (86 backend + 33 frontend)
- Step 6 is the only one changing the API contract (frontend+backend together)

</specifics>

<deferred>
## Deferred Ideas

None — PRD covers full refactoring scope

</deferred>

---

*Phase: 11-architecture-refactoring*
*Context gathered: 2026-03-29 via PRD Express Path*
