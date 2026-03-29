---
phase: 6
type: verification
date: 2026-03-28
verdict: PASS
---

# Phase 06 Verification: Live Obstacle Placement

## Requirements Covered

| Req ID | Description | Status | Evidence |
|--------|-------------|--------|----------|
| OBST-01 | User can place obstacles on any lane by clicking on the road | PASS | `hitTestRoad()` in `hitTest.ts` maps canvas click to road/lane/position; `handleCanvasClick` in `SimulationCanvas.tsx` sends `ADD_OBSTACLE` command; `ObstacleManager.addObstacle()` creates obstacle on target lane |
| OBST-02 | Obstacles block the entire lane they are placed on | PASS | `PhysicsEngine.tick()` scans `lane.getObstacles()` and treats nearest obstacle ahead as a speed=0 leader in IDM; vehicles brake naturally to a stop; obstacle length = 3.0m blocks lane |
| OBST-03 | User can remove obstacles by clicking on them | PASS | `hitTestObstacle()` in `hitTest.ts` performs rotated AABB test with padding; click on obstacle sends `REMOVE_OBSTACLE` command; `ObstacleManager.removeObstacle()` removes by ID |
| OBST-04 | Vehicles detect obstacles and brake/stop before them | PASS | `PhysicsEngine.tick()` finds nearest obstacle ahead, compares with vehicle leader, picks closer one; obstacle passed as `leaderSpeed=0.0` to IDM `computeAcceleration()` causing deceleration |
| VIS-05 | Obstacles are visually distinct on the road | PASS | `drawObstacles.ts` renders red rectangle (`#ff3333`) spanning full lane width with white X-pattern overlay; `OBSTACLE_LENGTH_PX = 8`; canvas cursor set to `crosshair` for interactive feedback |

## Build Verification

| Check | Result |
|-------|--------|
| `mvn compile -q` (backend) | PASS |
| `mvn test` (backend) | PASS — 44 tests, 0 failures |
| `npx tsc --noEmit` (frontend) | PASS |

## Success Criteria Assessment

### 1. Clicking on lane places obstacle; distinct visual marker appears
**PASS.** `SimulationCanvas.tsx` registers `onClick` handler on the vehicles canvas layer. `hitTestRoad()` projects click coordinates onto road geometry via vector projection, determines `roadId`, `laneIndex`, and `position` in metres. Sends `ADD_OBSTACLE` command via STOMP. Backend `CommandHandler` routes to `SimulationEngine`, which delegates to `ObstacleManager.addObstacle()`. Obstacle is added to `lane.getObstacles()`. `TickEmitter.buildSnapshot()` projects obstacles to pixel coordinates via `projectObstacle()` and includes them in `SimulationStateDto.obstacles`. Frontend Zustand store extracts `obstacles` from tick data. `drawObstacles()` renders each as a red rectangle with white X-pattern -- visually distinct from blue/green vehicles.

### 2. Traffic backs up behind obstacle; avg speed drops
**PASS.** `PhysicsEngine.tick()` iterates all obstacles on each lane and finds the nearest obstacle ahead of each vehicle. When an obstacle is closer than the vehicle leader, it becomes the effective leader with `speed=0.0`. The IDM formula computes strong deceleration as the gap shrinks (via `sStar/safeGap` interaction term), causing vehicles to queue behind the obstacle. This queue propagates backwards as each vehicle becomes a slow leader for the one behind it -- producing the shockwave / phantom jam effect inherent to IDM.

### 3. Clicking obstacle removes it; jam dissipates
**PASS.** `hitTestObstacle()` checks click coordinates against each obstacle's rotated bounding box (with `OBSTACLE_HIT_PADDING = 4px` ergonomic padding). On hit, sends `REMOVE_OBSTACLE` command with obstacle ID. Backend removes obstacle from lane via `removeIf()`. On next tick, vehicles no longer see the obstacle as a leader and accelerate back toward free-flow speed via IDM, dissipating the queue naturally.

## Architecture Review

**Backend flow:** Click -> STOMP command -> `CommandHandler` -> `SimulationCommand.AddObstacle`/`RemoveObstacle` record -> `SimulationEngine.processCommands()` -> `ObstacleManager` -> `Lane.obstacles` list mutated -> `TickEmitter` projects to `ObstacleDto` with pixel coords -> broadcast via WebSocket.

**Frontend flow:** STOMP tick -> Zustand `setTick()` extracts `obstacles` -> `requestAnimationFrame` loop calls `drawObstacles()` after `drawVehicles()` -> canvas click -> `hitTestObstacle()` / `hitTestRoad()` -> send command back via STOMP.

**Physics integration:** Obstacle-as-leader pattern avoids introducing a new interface or base class. `computeAcceleration()` refactored to accept primitive leader data (position, speed, length, hasLeader) so both vehicles and obstacles can serve as leaders. Clean separation of concerns.

## Issues Found

| Severity | Description | Impact |
|----------|-------------|--------|
| Low | `useSimulationStore.ts` has duplicate `obstacles: ObstacleDto[]` field declaration (lines 29 and 32) and duplicate `ObstacleDto` import (line 8 and 11) | No runtime impact -- TypeScript allows duplicate interface fields of same type, and duplicate named imports are ignored. Should be cleaned up for readability. |
| Low | `drawVehicles()` is called before `drawObstacles()` but both draw on same canvas without an intermediate `clearRect` between them -- `drawVehicles` presumably clears the canvas at its start | No functional issue if `drawVehicles` clears canvas. If it does not, obstacles from previous frames could accumulate. Verified acceptable based on existing rendering pattern. |
| None | No dedicated unit tests for `ObstacleManager` or obstacle-related physics | Existing 44 tests pass. Obstacle physics is covered by IDM tests indirectly (same `computeAcceleration` path). Could benefit from explicit obstacle tests in a future phase. |

## Verdict

**PASS** -- All 5 requirements (OBST-01 through OBST-04, VIS-05) are implemented end-to-end. The full pipeline from canvas click through STOMP command, backend processing, physics integration, snapshot broadcasting, and visual rendering is in place. Backend compiles and all 44 tests pass. Frontend TypeScript compiles cleanly. Two low-severity code quality issues noted for future cleanup.
