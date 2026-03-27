# Traffic Simulator — Critical Pitfalls

Stack: Java 17 + Spring Boot 3.x, React 18 + TypeScript, WebSocket (STOMP/SockJS), HTML5 Canvas, tick-based simulation.

---

## Critical Pitfalls

### 1. Simulation-Rendering Coupling

**Problem**: Tying simulation speed to the browser's `requestAnimationFrame` (60 fps) means the simulation runs at render speed, not at a deterministic fixed rate. Pausing the browser tab freezes simulation time. Speeding up simulation requires skipping frames or rendering at 60 fps with bogus physics steps, producing inconsistent results across machines.

**Root cause**: Putting the simulation loop inside the frontend render loop instead of on the backend scheduler.

**Correct approach**:
- Backend runs the simulation on a `ScheduledExecutorService` at a fixed tick rate (e.g., 20 ticks/sec = 50 ms/tick). The tick rate is a configuration constant, not a frame rate.
- Frontend renders on `requestAnimationFrame` independently. If two ticks arrive between frames, the second one overwrites the first — that is fine, rendering is purely cosmetic.
- Expose a simulation speed multiplier as a tick delta scalar (e.g., `dt = tickInterval * speedMultiplier`) applied inside the physics step, not by changing the scheduler frequency. Changing scheduler frequency causes jitter and thread re-creation overhead.

**Spring Boot specifics**: Use `@Scheduled(fixedRate = 50)` with a separate `ThreadPoolTaskScheduler`. Do NOT use the default single-threaded `ThreadPoolTaskScheduler` shared with other Spring tasks or you will block HTTP handling.

---

### 2. WebSocket Bandwidth

**Problem**: Sending full simulation state every tick. With 500 vehicles at 20 ticks/sec, each vehicle represented as a JSON object (~200 bytes), that is 500 × 200 × 20 = 2 MB/sec of outbound traffic per client. This saturates the WebSocket, causes message queue buildup, and the frontend rendering falls behind, creating a growing lag spike.

**Symptoms**: Frontend appears to "replay" old state; UI controls feel sluggish; browser memory grows; eventual WebSocket disconnect.

**Correct approaches** (pick one or combine):
- **Delta updates**: Only send vehicles whose state changed by more than a threshold (position delta > 0.5 m, speed delta > 0.1 m/s). Stationary blocked vehicles behind a red light need only one update, not 20 per second.
- **Tick rate vs render rate split**: Simulate at 20 ticks/sec but broadcast at 10 fps. Aggregate intermediate ticks server-side; only push the latest snapshot to the client.
- **Binary protocol**: Replace JSON with a flat binary frame (vehicle ID as int16, x/y as float32, heading as float16). Reduces per-vehicle overhead from ~200 bytes to ~12 bytes — 16x reduction.
- **Chunking**: If the vehicle count is expected to reach 1000+, stream only vehicles in the visible viewport (frontend sends viewport bounds, backend filters).

**Do not**: Use STOMP `/topic/` broadcast to all connected clients with a full snapshot — this blocks the broker thread for every subscriber.

---

### 3. Canvas Performance

**Problem**: Calling `clearRect` on the full canvas and redrawing every element every frame regardless of what changed. With 500 vehicles, each requiring `save/restore`, `translate`, `rotate`, `fillRect`, `restore`, the per-frame draw call count reaches ~3000. Compound with road network, traffic lights, and statistics overlays and frame time exceeds 16 ms at 60 fps, causing jank.

**Specific anti-patterns**:
- `ctx.save()/ctx.restore()` inside a tight loop per vehicle — expensive due to state stack allocation.
- Calling `ctx.strokeText()` per vehicle for speed labels every frame.
- Recreating `Path2D` objects for roads every frame instead of caching them.
- Using multiple overlapping `<canvas>` elements without understanding compositing cost.

**Correct approaches**:
- **Layered canvas**: Split into at minimum three layers: static background (roads, lane markings — drawn once), dynamic vehicles layer (redrawn every frame), UI overlay (stats panel — redrawn only on data change). Roads never change; clearing and redrawing them every frame is pure waste.
- **Dirty-rect optimization for vehicles**: Maintain a bounding-box list from the previous frame. Clear only those rects before redrawing. Only practical if vehicle density is low. At high density, full clear of the vehicle layer is often faster than hundreds of individual `clearRect` calls.
- **Batch transform**: Instead of `save/translate/rotate/restore` per vehicle, pre-compute screen coordinates server-side or in a worker; draw all vehicles of the same color in one batch path.
- **Object pooling for draw state**: Avoid per-frame object allocation. Pre-allocate typed arrays for vehicle positions (`Float32Array`) and iterate without creating JS objects inside the loop.
- **OffscreenCanvas + Worker**: Move canvas rendering to a Web Worker via `OffscreenCanvas.transferControlToOffscreen()`. This frees the main thread for UI responsiveness. Spring WebSocket messages arrive on the main thread — transfer the data to the worker via `postMessage` with a `SharedArrayBuffer` or transferable `ArrayBuffer`.

---

### 4. Vehicle Physics Edge Cases

**Problem**: IDM (Intelligent Driver Model) and similar car-following models produce undefined behavior at boundary conditions.

**Specific failure modes**:

- **Overlap on spawn**: Spawning a vehicle at a position already occupied by another. The following distance becomes zero or negative, IDM produces infinite deceleration, vehicle gets a velocity of `NaN` or `-Infinity` on the next tick.
- **Negative speed**: IDM's acceleration term `a * [1 - (v/v0)^delta - (s*/s)^2]` does not clamp speed to zero. A heavily braking vehicle can cross zero and start reversing if the time step is too large relative to deceleration. Fix: clamp velocity to `[0, maxSpeed]` after every integration step.
- **Teleportation**: A vehicle at the end of a road segment that has no next segment assigned will have its position increment past the road length with no transition logic. The vehicle "teleports" or disappears. Fix: road graph traversal must be resolved before position update; if no successor exists, vehicle should decelerate to stop at road end.
- **Infinite acceleration**: When the preceding vehicle's position is exactly equal to the following vehicle's position (gap = 0), IDM's `(s*/s)^2` term is `infinity`. Guard: enforce a minimum gap constant (`s_min >= 1.0 m`) and clamp the interaction term.
- **Stopped vehicle chain**: When a full lane is stopped at a red light, each vehicle's desired gap `s*` includes a velocity-dependent term `v*T` which goes to zero. This is correct behavior, but only if `s_min` prevents collapse to zero gap.

**Validation rule**: After every tick, assert `0 <= v <= maxSpeed` and `position >= 0` for all vehicles. Log and clamp violations rather than crashing; a production simulation should degrade gracefully.

---

### 5. Floating Point Accumulation

**Problem**: In a tick-based simulation, vehicle position is updated as `position += velocity * dt` every tick. Over thousands of ticks, floating point rounding accumulates. At 20 ticks/sec running for 10 minutes = 12,000 ticks. A rounding error of `1e-7` per tick accumulates to `1.2e-3` over 12,000 ticks — roughly 1 mm. For a road length of 1000 m, this is negligible. However, when position is used to determine which road segment a vehicle is on (boundary checks like `position >= segmentLength`), accumulated error can cause a vehicle to miss a segment transition and end up "beyond the end" of a road.

**Where it actually hurts**:
- Segment transition logic using strict equality: `if (position == segmentLength)` — never triggers due to floating point.
- Intersection clearance checks: a vehicle's bounding box calculated from accumulated position may drift out of its lane center.
- Traffic light stop line: vehicle may stop 2–5 cm past the stop line depending on accumulated error and tick granularity. Visually invisible but logically it "ran the red light".

**Fixes**:
- Use `position >= segmentLength - EPSILON` (e.g., `EPSILON = 0.01`) for transition triggers.
- Periodically snap vehicle position to lane center axis (lateral position) to prevent lateral drift.
- Use `double` (64-bit) rather than `float` (32-bit) for all position and velocity calculations on the backend. The frontend only needs `float32` for canvas rendering.
- For long-running simulations: track `totalDistanceTraveled` as a separate accumulator rather than deriving it from current position.

---

### 6. Lane Change Collisions

**Problem**: Two vehicles in adjacent lanes simultaneously decide to change into the same target lane at the same position. Both pass the "is target gap safe?" check at tick N because neither has committed the move yet. At tick N+1 both occupy the same lane slot — overlap occurs.

**Why it happens**: The gap check reads the current state (tick N positions). The state write happens after all checks complete. This is the classic check-then-act race in a sequential but non-atomic update.

**Fix — two-phase update**:
1. **Intent phase**: All vehicles compute their desired action (including lane changes). Mark target lane slots as "reserved" in a scratch buffer, not in the live state.
2. **Conflict resolution**: For each contested slot, use priority rules (e.g., the vehicle further along the road wins, or random tie-break with a seeded RNG for determinism).
3. **Commit phase**: Apply the resolved intents to the live state.

**Additional guards**:
- Enforce a per-vehicle lane change cooldown (e.g., cannot change lane again within 3 seconds of the last change). This reduces contention frequency.
- Apply a lateral position animation (gradual lane shift over N ticks) rather than instant teleportation. This creates a natural buffer during which a second vehicle will see the first as "occupying" the target lane.

---

### 7. Intersection Deadlocks

**Problem**: All approaches to a 4-way intersection are occupied by vehicles. Each vehicle is waiting for the vehicle ahead in the intersection to clear, but those vehicles are waiting for the opposite approach to clear. Classic deadlock: A waits for B, B waits for C, C waits for D, D waits for A.

**How it manifests**: Traffic lights are green on all sides but no vehicle moves. The simulation continues to tick but the intersection is frozen. Vehicle counts in the intersection remain non-zero indefinitely.

**Root causes**:
1. No maximum occupancy limit on the intersection box — vehicles enter even when the exit road is blocked.
2. Vehicles enter the intersection on green without checking if they can clear it before the light changes (box-blocking behavior).
3. Right-of-way resolution for priority-from-right scenarios produces circular precedence.

**Fixes**:
- **Box blocking prevention**: Before entering an intersection, a vehicle checks if the target exit segment has enough free space to accommodate it. If not, it stops at the stop line even on green.
- **Intersection occupancy limit**: Track the number of vehicles currently in the intersection box. Refuse entry beyond a capacity threshold (e.g., 2 vehicles per lane-pair).
- **Deadlock detector**: A watchdog that checks if intersection occupancy has been non-zero and unchanged for more than 10 seconds of simulation time. On detection: log, then forcibly advance one vehicle to break the cycle. This is a recovery mechanism, not a prevention mechanism.
- **Traffic light coordination**: Adjacent intersections' light cycles should be offset to create "green waves", reducing the probability of simultaneous blocking from multiple directions.

---

### 8. Roundabout Gridlock

**Problem**: Vehicles enter the roundabout from all entry points simultaneously until the roundabout is at 100% capacity. Vehicles already in the roundabout cannot exit because exit roads are blocked by vehicles waiting to enter. Vehicles waiting to enter cannot enter because the roundabout is full. Complete gridlock.

**Differs from intersection deadlock** in that roundabouts have a circular flow — the issue is capacity saturation, not circular wait on rights-of-way.

**Fixes**:
- **Entry gating**: A vehicle may only enter the roundabout if there is at least one free slot inside (i.e., current roundabout occupancy < max capacity). Max capacity = `roundabout_circumference / average_vehicle_spacing`.
- **Exit check before entry**: Before entering the roundabout, the vehicle checks if there is free space on its target exit road. If not, it yields even if the roundabout itself has space (prevents blocking from inside).
- **Yield-to-circulating rule**: Entering vehicles always yield to circulating vehicles. This must be implemented as a hard priority rule, not a soft preference — no "pushing in" under any acceleration model.
- **Forced yield on congestion**: If the roundabout has been at >80% capacity for more than 5 simulation seconds, disable new entries from all but the lowest-traffic entry point for a cooldown period.

---

### 9. JSON Map Validation

**Problem**: Loading a malformed or internally inconsistent map configuration causes a NullPointerException or ArrayIndexOutOfBoundsException deep inside the simulation engine on the first tick, with no diagnostic information about which map element is wrong.

**Specific failure scenarios**:
- A road segment references a lane index that does not exist on the target road.
- An intersection references a road ID that does not exist in the map.
- A roundabout's exit road is a one-way road pointing into the roundabout (impossible exit).
- Negative road length, zero lane count, or speed limits of 0.
- Cyclic road connections with no exit (a vehicle loop with no spawn/despawn points).
- Missing required fields (null `id`, null `connections`).

**Fix — validation layer before simulation start**:

```java
public class MapValidator {
    public ValidationResult validate(MapConfig config) {
        List<String> errors = new ArrayList<>();
        // 1. Structural: all referenced IDs exist
        // 2. Physical: lengths > 0, laneCount >= 1, speedLimit > 0
        // 3. Topological: no dangling references, at least one spawn point
        // 4. Reachability: every road is reachable from a spawn point (graph traversal)
        // 5. Roundabout: all exits lead to non-roundabout segments
        return new ValidationResult(errors);
    }
}
```

Apply `MapValidator` at config load time, before instantiating any simulation state. Return a structured error response to the frontend with the specific element and field that failed validation — do not swallow exceptions.

**Additional**: Use Jackson's `@JsonProperty(required = true)` and a custom `@Valid` bean validation on the `MapConfig` DTO to catch structural issues before the validator runs.

---

### 10. Thread Safety

**Problem**: The simulation engine runs on a `ScheduledExecutorService` thread (the tick thread). WebSocket message handlers (e.g., "add obstacle", "change speed limit", "pause simulation") run on the STOMP broker thread. Both threads access and mutate the simulation state concurrently. This produces data races: a vehicle position array being iterated by the tick thread while the broker thread inserts a new vehicle.

**Java-specific risks**:
- `ArrayList<Vehicle>` is not thread-safe. `ConcurrentModificationException` during iteration.
- A vehicle's `position`, `velocity`, `laneIndex` fields read mid-update may produce a torn read — partially updated state sent to the frontend.
- `HashMap` for road segment lookup accessed from both threads without synchronization can cause infinite loops on `get()` under Java's HashMap resize behavior.

**Correct approach — command queue pattern**:

```java
// Thread-safe command queue: broker thread enqueues, tick thread drains
private final BlockingQueue<SimulationCommand> commandQueue = new LinkedBlockingQueue<>();

// In tick loop (scheduler thread only):
void tick() {
    drainCommands();   // apply all pending commands before this tick
    stepPhysics();     // update all vehicle positions
    broadcastState();  // serialize and send — reads from state only
}

void drainCommands() {
    SimulationCommand cmd;
    while ((cmd = commandQueue.poll()) != null) {
        cmd.apply(simulationState);
    }
}
```

This eliminates synchronization on the hot path. The tick thread exclusively owns the simulation state. WebSocket handlers only enqueue commands, never touching state directly.

**Do not use `synchronized` on the simulation state object** — it will cause the tick thread to block on every WebSocket message, adding latency spikes visible as frame hitches.

---

## Technical Debt Patterns

These do not break the simulator immediately but compound into major refactors later:

| Pattern | How it sneaks in | Long-term cost |
|---|---|---|
| God `SimulationEngine` class | Start with one class, keep adding | Impossible to unit test individual behaviors; tick method grows to 500 lines |
| Vehicle as mutable shared DTO | Same `Vehicle` object used for physics, serialization, and frontend state | Cannot safely send partial updates; physics bugs corrupt display |
| Hardcoded road network | "Just testing" inline road definitions | Impossible to test with different maps; scenario system requires full rewrite |
| Tick rate as magic constant | `Thread.sleep(50)` instead of a configurable `tickIntervalMs` | Cannot slow down for debugging or speed up for stress testing without code change |
| No simulation event log | Skipping event recording to save time | Deadlock and gridlock bugs unreproducible — need deterministic replay to debug |
| Frontend polling instead of WebSocket push | Easier to implement initially | Polling interval creates artificial latency; switch to WebSocket later requires protocol redesign |

---

## Performance Traps — Tick Rate vs Vehicle Count Scaling

The simulation has two scaling dimensions that interact non-linearly:

### Tick rate scaling
- Each doubling of tick rate doubles CPU time AND doubles WebSocket traffic.
- At 50 ticks/sec, with 1000 vehicles and full JSON serialization, serialization alone takes ~15 ms/tick on a modern JVM (first few ticks before JIT warms up: ~80 ms). If the tick period is 20 ms, the system is overloaded from the start.
- Rule of thumb: `serializationTime + physicsTime < 0.7 * tickInterval`. Leave 30% headroom.

### Vehicle count scaling
- IDM physics is O(N) per tick where N = vehicle count. That is fine.
- Lane change checks are O(N * lanesPerRoad) in the naive implementation. Still fine up to ~2000 vehicles.
- **The hidden O(N²) trap**: Checking every vehicle against every other vehicle for collision detection. Never implement naive O(N²) collision detection. Use spatial partitioning (grid cells by road segment) — vehicles on different segments cannot collide.
- JSON serialization is O(N) but with a high constant. For 1000 vehicles: Jackson serializes ~10K fields per tick. Consider a custom serializer that writes a flat array rather than a list of named objects.

### Observed breaking points (estimates for this stack)
| Vehicles | Tick rate | Expected behavior |
|---|---|---|
| < 200 | 20/sec | Comfortable, full JSON fine |
| 200–500 | 20/sec | Monitor tick duration; JSON may need optimization |
| 500–1000 | 20/sec | Delta updates required; consider binary format |
| > 1000 | 20/sec | Viewport culling required; reduce tick rate to 10/sec |

---

## UX Pitfalls

### Controls unresponsive during heavy simulation
- If the Spring Boot application uses the default Tomcat thread pool and the tick scheduler shares threads with HTTP request handling, a slow tick blocks the thread pool. UI control POST requests time out.
- Fix: dedicate a separate `ThreadPoolTaskScheduler` bean for the simulation with `poolSize = 1`.
- Fix: make all UI control operations asynchronous — enqueue into the command queue, return `202 Accepted` immediately.

### Speed slider causes simulation time jumps
- Implementing "faster simulation" by multiplying `dt` by a large factor (e.g., 10x speed = `dt * 10`) causes vehicles to "jump" forward, skipping intermediate positions. At 10x speed a vehicle traveling 120 km/h moves 33 cm per real-world 50 ms tick, but 3.3 m per physics step — enough to jump over a stop line or through a vehicle ahead.
- Fix: sub-step the physics. At 10x speed, run 10 physics steps of `dt/10` per tick. This keeps individual step sizes bounded and prevents tunneling.

### Unclear visualization of simulation state
- If the simulation is paused, nothing on screen indicates this — the canvas looks identical to a running simulation with zero vehicles.
- If WebSocket disconnects, vehicles freeze in place — visually indistinguishable from a paused simulation.
- Traffic light state (red/yellow/green) must be visually unambiguous at road intersections. A single pixel dot is not enough at zoom-out levels.
- Lane change in progress should be visually distinct from a vehicle traveling straight.

### Statistics panel lag
- If statistics (average speed, throughput, density) are computed in the tick thread and serialized with each tick, they add to tick duration.
- Compute statistics asynchronously on a separate thread triggered every 500 ms, independent of the tick rate.
- Display a "calculating..." state if the stats are stale by more than 2 seconds.

---

## "Looks Done But Isn't" Checklist

The following scenarios are visually undetectable in a demo but represent broken behavior:

- [ ] **Phantom progress**: Vehicles appear to move smoothly but the simulation clock has stopped advancing. Frontend interpolates between the last two received states indefinitely.
- [ ] **Spawn rate lies**: The UI shows "50 vehicles/min" but the actual spawn rate depends on available road space. Under congestion, spawning silently fails. The displayed rate is a target, not an actual.
- [ ] **Traffic lights cycling but not enforced**: Lights visually change color but vehicles ignore them because the enforcement check was never wired to the vehicle behavior model.
- [ ] **Lane changes visual only**: Vehicles appear to switch lanes on canvas but the physics model still treats them as being in the original lane. Speed and following distance calculations use wrong neighbors.
- [ ] **Obstacle removed but collision box persists**: Removing an obstacle via UI sends a command that removes it from the render list but not from the physics collision check. Vehicles still slow down and stop at the invisible obstacle.
- [ ] **Statistics correct but stale**: The stats panel shows average speed from 30 ticks ago. During congestion buildup, this makes the simulation appear healthier than it is.
- [ ] **Round-trip latency hidden by tick rate**: The simulation appears real-time but there is a 500 ms buffer between backend state and frontend display because the WebSocket message queue backed up. Adding/removing an obstacle takes 500 ms to appear, which looks like a bug.
- [ ] **Determinism lost silently**: Two identical starting conditions produce different outcomes because `Math.random()` is used inside the physics engine instead of a seeded `Random`. This makes bugs unreproducible.
- [ ] **Memory leak via Vehicle object accumulation**: Despawned vehicles are removed from the active list but their references are retained in an event history list that is never pruned. After 30 minutes of simulation, the JVM heap grows to several GB.
- [ ] **WebSocket reconnect resets simulation**: On WebSocket reconnect (tab sleep, network blip), the frontend receives the current state but the simulation has been running for minutes. The frontend has no mechanism to distinguish "first connect" from "reconnect" and reinitializes its local state, causing a visual reset that does not reflect actual simulation state.

---

*Last updated: 2026-03-27*
