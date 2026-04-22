# Phase 21: Predefined Map Components — Research

**Researched:** 2026-04-13
**Domain:** Claude vision pipeline v2 — component catalog + deterministic expansion
**Confidence:** HIGH (all claims grounded in the repo; external library lookups not required — this is an internal architecture phase)

## Summary

Phase 20 failed because Claude was asked to invent full graph geometry. Phase 21 splits responsibility: **Claude identifies**, **Java expands**. Four design choices drive success:

1. Model components as a **sealed interface `Component` + record implementations** with a single `expand(ExpansionContext)` method — cheap to add new types, pattern-matchable in the stitcher, trivially testable in isolation.
2. Stitching via **endpoint node merge** (collapse coincident ENTRY/EXIT pairs into a shared INTERSECTION), plus a `STRAIGHT_SEGMENT` component that Claude emits explicitly when two arms are visibly separated by open road. No implicit "invisible connector" logic.
3. Prompt uses a **flat, catalog-gated JSON schema**: `{components: [...], connections: [...]}`. Each component entry is `{type, id, center:{x,y}, rotationDeg}` plus type-specific knobs. Claude cannot invent types; the prompt enumerates the catalog and rejects anything else.
4. New endpoint pair (`analyze-components` + `analyze-components-bbox`) mirrors Phase 20 exactly. Comparison harness ships as a **`@SpringBootTest` dev test** gated on a system property — no new `exec-maven-plugin` dependency.

**Primary recommendation:** Sealed-interface component model, explicit-connector stitching, flat JSON schema, mirrored endpoint pair, comparison harness as opt-in integration test.

## User Constraints (from CONTEXT.md)

### Locked Decisions
- **Additive only.** Phase 20 services/controllers stay byte-for-byte identical.
- New endpoint: `POST /api/vision/analyze-components` (multipart, `MapConfig` response).
- New services: `ComponentVisionService` (prompt + expansion entry), `MapComponentLibrary` (catalog + `expand(List<ComponentSpec>) → MapConfig`).
- MVP catalog: `ROUNDABOUT_4ARM`, `T_INTERSECTION`, `SIGNAL_4WAY`, `STRAIGHT_SEGMENT`. Adding types must be cheap.
- Connection model: components expose named arms (`north`, `east`, …); Claude returns `(componentA.armX) ↔ (componentB.armY)`; backend stitches.
- Frontend: third button "AI Vision (component library)" in `MapSidebar`, reuses bbox-to-PNG flow.
- Comparison harness: dev-only, one fixture → both pipelines → side-by-side diff.
- Testing fixture: `/tmp/roundabout-test.png`.
- Output MUST pass `MapValidator`.
- Component expansion MUST produce `_in`/`_out` road IDs satisfying `IntersectionGeometry.reverseRoadId`.

### Claude's Discretion
- Java package layout under `service/` and/or `vision/components/`.
- Records vs sealed interface vs registry pattern.
- `STRAIGHT_SEGMENT` length: Claude-estimated px / inferred / both.
- Frontend button styling — match `MapSidebar` conventions.
- Prompt phrasing — must enumerate catalog and gate types.

### Deferred Ideas (OUT OF SCOPE)
- Replacing Phase 20 endpoints.
- Auto-selecting between pipelines.
- Component types beyond MVP catalog.
- Persistent (non-hardcoded) component library config.
- Shared-node detection beyond simple endpoint coincidence.

## Phase Requirements

This phase is requirement-free at the atomic level — CONTEXT.md's Success Criteria act as acceptance tests. Planner should derive task-level requirements from Success Criteria (roundabout fixture produces topologically-equivalent map, Phase 20 tests still pass, frontend third button works, comparison harness emits diff).

## Project Constraints (from CLAUDE.md)

- **Stack locked:** Java 17 + Spring Boot 3.3.5, Maven 3.9.x. No database, no Spring Security, no WebFlux.
- **No Hibernate/JPA.** Component catalog stays in-memory / hardcoded Java.
- **GSD workflow mandatory.** All file edits must go through a GSD command.
- **Testing verification via REST**, not screenshots — but that's a runtime concern, not build-time (doesn't constrain this phase's test strategy).
- **Conventions file:** new coding rules learned during this phase → append to `.planning/CONVENTIONS.md`.

## Standard Stack

No new dependencies required. Every capability needed already exists in the backend:

| Capability | Existing Provider | Notes |
|------------|-------------------|-------|
| JSON parse/emit | Jackson 2.17 (Spring-managed) | Already used by `ClaudeVisionService` |
| Validation | `MapValidator` | Must pass same gate as Phase 20 |
| CLI invocation | `ClaudeVisionService.executeCliCommand` | Package-private, spy-friendly; extract to shared helper OR duplicate — see Risks below |
| Image composition | `OsmStaticMapService.composeBboxPng` | Reuse verbatim for `analyze-components-bbox` |
| Unit test framework | JUnit 5 + Mockito + AssertJ (via `spring-boot-starter-test`) | `ClaudeVisionServiceTest` is the template |
| Integration test | `@SpringBootTest` | Use for comparison harness |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Sealed interface `Component` | Registry singleton (`Map<String, ComponentExpander>`) | Registry is more dynamic but loses exhaustiveness checking; sealed gives compile-time coverage of switch statements |
| Sealed interface | Enum with abstract methods | Can't carry type-specific fields (rotation, length) cleanly; records win |
| `exec-maven-plugin` for harness | `@SpringBootTest` with system property gate | No new pom dependency; reuses existing test infra; runnable via `mvn -Pharness test` or `mvn -Dtest=VisionComparisonHarness test` |

## Architecture Patterns

### Recommended Project Structure
```
backend/src/main/java/com/trafficsimulator/
├── service/
│   ├── ComponentVisionService.java         # prompt + orchestration (new)
│   └── MapComponentLibrary.java            # expand(List<ComponentSpec>) → MapConfig (new)
├── vision/components/                      # new package
│   ├── ComponentSpec.java                  # sealed interface
│   ├── RoundaboutFourArm.java              # record
│   ├── TIntersection.java                  # record
│   ├── SignalFourWay.java                  # record
│   ├── StraightSegment.java                # record
│   ├── ArmRef.java                         # record(componentId, armName)
│   ├── Connection.java                     # record(ArmRef a, ArmRef b)
│   └── ExpansionContext.java               # id generator, accumulators (nodes/roads/intersections/spawn/despawn)
└── controller/
    └── VisionController.java               # +2 endpoints (no modifications to existing methods)

backend/src/test/java/com/trafficsimulator/
├── service/
│   ├── ComponentVisionServiceTest.java     # prompt sanity + parse
│   └── MapComponentLibraryTest.java        # expansion → MapValidator passes
├── vision/components/
│   └── per-component expansion tests
└── integration/
    └── VisionComparisonHarness.java        # @SpringBootTest, @EnabledIfSystemProperty
```

### Pattern 1: Sealed Interface + Records + Pattern-Matching Dispatch

**What:** `ComponentSpec` is a Java 17 sealed interface; each component type is a record that implements it. `MapComponentLibrary.expand(...)` uses a switch expression.

```java
public sealed interface ComponentSpec
        permits RoundaboutFourArm, TIntersection, SignalFourWay, StraightSegment {
    String id();
    Point2D center();      // null for StraightSegment (derived from connection)
    double rotationDeg();
    void expand(ExpansionContext ctx);   // mutates ctx
    Map<String, Point2D> armEndpoints(); // armName -> world (x,y)
}

public record RoundaboutFourArm(String id, Point2D center, double rotationDeg, double ringRadiusPx)
        implements ComponentSpec { ... }
```

**Why:** (1) compile-time exhaustiveness — `switch(spec) { case RoundaboutFourArm r -> ... }` errors if a new type is added; (2) records give free equals/hashCode for test assertions; (3) each component owns its geometry in one file. Adding a new type = add record + one switch arm + register in prompt.

### Pattern 2: ExpansionContext (accumulator)

Single mutable object threaded through expansion. Holds `List<NodeConfig>`, `List<RoadConfig>`, `List<IntersectionConfig>`, spawn/despawn, and a monotonic id counter per component instance. Keeps components from clashing on `n_ring_n` etc. when two roundabouts exist. Prefix all emitted ids with `<componentId>__` (e.g. `rb1__n_ring_n`, `rb1__r_north_in`). Crucially: suffix preservation for `_in`/`_out` is non-negotiable — `IntersectionGeometry.reverseRoadId` does `.replace("_in","_out")` globally on the string, so component-id prefix MUST NOT contain `_in` or `_out` substrings. Use `rb1`, `sig2`, `t3`, `seg4` — safe.

### Pattern 3: Connection Stitching — Explicit-Connector Policy

When Claude returns `Connection(rb1.north, seg1.start)` and `Connection(seg1.end, sig1.south)`:

1. Each component expands independently, producing its own ENTRY/EXIT node pair per arm.
2. For each `Connection`, compare the arm endpoints' (x,y):
   - **If coincident (≤ 5 px tolerance):** delete both components' ENTRY/EXIT nodes for those arms, create one shared `INTERSECTION` node, rewrite the 2 or 4 road endpoints to point at it.
   - **If not coincident:** error. Claude must insert a `STRAIGHT_SEGMENT` between them explicitly.

`STRAIGHT_SEGMENT` is itself a component: it has `start` and `end` arms whose endpoints come from Claude's `startCenter`/`endCenter` fields. It expands to `ENTRY(start) → road → EXIT(end)` (or reverses based on which side is `_in`). The merge step above then fuses its start/end with neighbouring component arms.

**Why explicit connector instead of "auto-insert road between non-coincident arms":** (a) makes Claude's mental model uniform — everything is a named component; (b) connector length is Claude's estimate of visible road length, not a geometric fallback; (c) debugging is easier — you see the connector in the response JSON.

### Anti-Patterns to Avoid
- **Hand-rolling a graph library.** Don't. Components emit `NodeConfig`/`RoadConfig` directly into the shared accumulator. Jackson + MapValidator does the rest.
- **Auto-inserting invisible roads between arms whose (x,y) differ.** Surfaces bugs as "mysterious extra road". Require explicit `STRAIGHT_SEGMENT`.
- **Baking ring geometry into the prompt.** That's what Phase 20 did. Claude says `ROUNDABOUT_4ARM at (400,300) rot 0` and the library handles the rest.
- **Using `Math.hypot` tolerance smaller than ring-node offset (~28 px).** Merge tolerance must be small enough to not fuse distinct ring nodes across components. Recommend 5 px.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| JSON→record binding | Custom parser | Jackson with `@JsonSubTypes` on `ComponentSpec` (or 2-pass: parse to `ComponentSpecDto`, build record) | Jackson already wired; sealed interfaces need `JsonTypeInfo(use = NAME)` + `JsonSubTypes` |
| MapConfig validation | Re-implementing | `MapValidator.validate(config)` | Already tested, covers all constraints |
| CLI invocation | New `ProcessBuilder` wrapper | Either call `claudeVisionService.executeCliCommand` (make it `package-private` accessible via same package, or lift to a shared `ClaudeCliRunner` helper) | Don't duplicate timeout/exit-code handling |
| Bbox → PNG | Rewrite tile compositor | Inject `OsmStaticMapService` into `ComponentVisionService` | Zero-effort reuse |

**Key insight:** This phase is 90% orchestration and prompt design. The only new code that should feel "novel" is the sealed interface + stitching.

## Connection / Stitching Algorithm — Decision

**Chosen:** Option (b) merge endpoint nodes if anchor positions coincide, **combined with** option (a) explicit `STRAIGHT_SEGMENT` emitted by Claude for visible connector roads. Rejected option (c) alone: implicit length estimation leaves Claude's model of "what connects" ambiguous.

**Algorithm:**
```
1. For each ComponentSpec: spec.expand(ctx)  // populates nodes/roads/intersections with prefixed ids
2. Build map: armKey ("rb1.north") -> (entryNodeId, exitNodeId, (x,y))
3. For each Connection(armA, armB):
    pA = armPosition(armA); pB = armPosition(armB)
    if distance(pA,pB) <= 5px:
        fuse: replace both entry+exit node refs with one new INTERSECTION node at midpoint(pA,pB)
        rewrite matching RoadConfig fromNodeId/toNodeId
        remove spawn/despawn that referenced the deleted ENTRY/EXIT (these are now internal)
    else:
        throw ExpansionException("arms " + armA + " and " + armB + " not coincident; insert STRAIGHT_SEGMENT")
4. Drop orphaned ENTRY/EXIT nodes (arms Claude didn't connect — keep them, they're network boundaries)
5. Return MapConfig; MapValidator.validate must pass
```

**Edge cases the planner must handle:**
- `STRAIGHT_SEGMENT` connected to component on one side and unconnected on the other → its loose arm becomes an ENTRY/EXIT pair (keep the spawn/despawn).
- Two components sharing >1 arm (e.g. adjacent roundabouts). Each connection is handled independently; no combinatorial issue as long as each arm appears in ≤1 connection.

## Prompt Design

### Schema Claude emits
```json
{
  "components": [
    {
      "type": "ROUNDABOUT_4ARM",
      "id": "rb1",
      "centerPx": {"x": 640, "y": 380},
      "rotationDeg": 0,
      "armsPresent": ["north", "east", "south", "west"]
    },
    {
      "type": "STRAIGHT_SEGMENT",
      "id": "seg1",
      "startPx": {"x": 640, "y": 180},
      "endPx":   {"x": 640, "y": 50},
      "lengthPx": 130
    }
  ],
  "connections": [
    {"a": "rb1.north", "b": "seg1.start"}
  ]
}
```

### Prompt skeleton (goes into `ComponentVisionService.ANALYSIS_PROMPT`)
```
You are a traffic-map recogniser. Look at the road image and identify which
PREDEFINED COMPONENTS appear, where they are, and how they connect.

VALID COMPONENT TYPES (you MUST NOT invent new types):
- ROUNDABOUT_4ARM — circular ring with up to 4 arms (north/east/south/west)
  fields: centerPx{x,y}, rotationDeg (0 = north arm points up), armsPresent (subset of [north,east,south,west])
- SIGNAL_4WAY — signalised intersection with 4 approaches
  fields: centerPx{x,y}, rotationDeg, armsPresent
- T_INTERSECTION — 3 arms, PRIORITY control
  fields: centerPx{x,y}, rotationDeg (0 = stem points south), armsPresent (exactly 3 of [north,east,south,west])
- STRAIGHT_SEGMENT — connector road between two component arms
  fields: startPx{x,y}, endPx{x,y}, lengthPx (your estimate of visible road length in px)

OUTPUT FORMAT (JSON only, no fences, no prose):
{ "components": [...], "connections": [{"a":"<id>.<arm>","b":"<id>.<arm>"},...] }

RULES:
- If a component's arm connects directly to another component's arm at the same pixel location, emit a connection entry.
- If two component arms are separated by visible road in the image, insert a STRAIGHT_SEGMENT between them and emit TWO connections.
- An unconnected arm becomes a network boundary (traffic enters/exits there) — fine, just omit it from connections.
- If you cannot identify a region using the valid types above, OMIT IT. Do not invent.
```

**Why this shape:** pixel anchors are what Claude reads off the image anyway; rotation is a single number (less error-prone than cardinal strings); `armsPresent` lets Claude skip absent arms without triggering ring-node omission (backend still emits all 4 ring nodes per CONTEXT Success Criteria).

**Length parameter resolution:** `STRAIGHT_SEGMENT.lengthPx` — Claude gives an estimate; backend reconciles against `distance(startPx, endPx)` and uses `max(claudeEstimate, geometricDistance)` as the road's `length` in MapConfig units. Both signals are kept because Claude is more reliable at "is this a long or short road" than exact pixel math.

## Reuse Strategy for OsmStaticMapService

**Recommendation: mirror Phase 20's pair from day one.** Two endpoints:

- `POST /api/vision/analyze-components` — multipart image (same contract as `/analyze`).
- `POST /api/vision/analyze-components-bbox` — `BboxRequest` body (same contract as `/analyze-bbox`).

Both route through `ComponentVisionService.analyzeImageBytes(byte[])` which does: temp-file → build prompt with the file path → `executeCliCommand` → `extractJson` → parse `{components, connections}` → `MapComponentLibrary.expand(...)` → validate → return `MapConfig`.

**Sharing `executeCliCommand` and `extractJson` with `ClaudeVisionService`:** two options — (1) extract a new `@Service ClaudeCliRunner` with both methods and inject into both services; (2) duplicate (~30 LOC). Option (1) is cleaner but touches `ClaudeVisionService` (which must stay byte-identical to preserve Phase 20 test outputs). **Recommend option 2 (duplicate)** to honour the "additive only" lock. Add a `// TODO: extract ClaudeCliRunner once Phase 21 stabilises` comment — defer the refactor to a follow-up phase.

Frontend: one new button in `MapSidebar`, same handler shape as existing "AI Vision (from bbox)" — POST to the new bbox endpoint when the user drew a bbox, or to `/analyze-components` when they uploaded a file. Reuses existing `BboxRequest` DTO.

## Comparison Harness Placement

**Recommendation: `@SpringBootTest` gated by system property.**

```java
@SpringBootTest
@EnabledIfSystemProperty(named = "vision.harness", matches = "true")
class VisionComparisonHarness {
    @Autowired ClaudeVisionService phase20;
    @Autowired ComponentVisionService phase21;

    @Test
    void compareOnRoundaboutFixture() throws Exception {
        byte[] png = Files.readAllBytes(Path.of("/tmp/roundabout-test.png"));
        MapConfig a = phase20.analyzeImageBytes(png);
        MapConfig b = phase21.analyzeImageBytes(png);
        Path out = Path.of("target/vision-comparison"); Files.createDirectories(out);
        writeDiffReport(out.resolve("diff.txt"), a, b);   // road count, intersection count+types, ring-roads detected, _in/_out coverage
        new ObjectMapper().writerWithDefaultPrettyPrinter()
            .writeValue(out.resolve("phase20.json").toFile(), a);
        new ObjectMapper().writerWithDefaultPrettyPrinter()
            .writeValue(out.resolve("phase21.json").toFile(), b);
    }
}
```

Run: `./mvnw -Dvision.harness=true -Dtest=VisionComparisonHarness test` from `backend/`.

**Why this over `mvn exec:java`:**
- No new Maven plugin dependency (exec-maven-plugin is not currently in the pom — confirmed).
- Full Spring context already wired — `@Autowired` both services, no manual construction.
- `@EnabledIfSystemProperty` keeps it out of the default build (CI won't call the real Claude CLI).
- Matches the existing test idiom in the repo (all other tests use `spring-boot-starter-test`).
- CONTEXT.md Open Question suggested `mvn exec:java` — rejected in favour of integration test because (a) no pom change, (b) reuses test classpath.

**Diff report contents (minimum viable):**
```
road count:          phase20=12   phase21=12   ✓ match
intersection count:  phase20=4    phase21=4    ✓ match
intersection types:  phase20={ROUNDABOUT:4} phase21={ROUNDABOUT:4} ✓ match
ring roads (ids matching r_ring_*): phase20=4 phase21=4 ✓
_in/_out coverage:   phase20=8/8  phase21=8/8  ✓
validator errors:    phase20=[]   phase21=[]
```

## Runtime State Inventory

Not applicable — this phase is pure additive Java code + one new prompt + one new frontend button. No stored data, no live service config, no OS-level state, no existing env vars, no build artifacts affected. Phase 20 artifacts (endpoints, tests) remain untouched by construction.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Java 17 | Backend compile | ✓ (project enforces) | 17 | — |
| Spring Boot 3.3.5 | Backend framework | ✓ | 3.3.5 | — |
| Claude CLI | `executeCliCommand` | ✓ (Phase 20 already uses it) | same binary | Endpoint returns 503 (existing `@ExceptionHandler`) |
| `/tmp/roundabout-test.png` | Comparison harness fixture | User to confirm (referenced in CONTEXT) | — | Harness skipped if missing — use `@Assumptions.assumeTrue(Files.exists(...))` |
| `osmStaticMapService` | bbox endpoint variant | ✓ | in repo | — |
| Node 20 / Vite 5 | Frontend button | ✓ | — | — |

**Missing with fallback:** fixture image — harness must `assumeTrue(exists)` rather than fail.

## Common Pitfalls

### Pitfall 1: Component-id prefix collides with `_in`/`_out` heuristic
**What goes wrong:** `IntersectionGeometry.reverseRoadId` does naive `.replace("_in","_out")`. If a component id contains `_in` (e.g. `ring_inside`), ALL occurrences flip.
**Prevention:** Enforce component id regex `^[a-z][a-z0-9]*$` in `MapComponentLibrary`. Reject at expand time.
**Warning signs:** Vehicles U-turn at roundabout → check road ids for accidental `_in` substrings in prefix.

### Pitfall 2: Claude emits unknown component type
**What goes wrong:** Despite prompt enumeration, Claude hallucinates `"CLOVERLEAF_INTERCHANGE"`.
**Prevention:** Jackson's `@JsonTypeInfo(use=NAME) + @JsonSubTypes` throws on unknown subtype — catch and return 422 with clear error message listing valid types.
**Warning signs:** 422 with "Unknown type id" in logs.

### Pitfall 3: Two components occupy overlapping pixel regions
**What goes wrong:** Claude sees a signal-controlled roundabout and emits both `ROUNDABOUT_4ARM` and `SIGNAL_4WAY` at same centerPx → ring nodes collide with signal center → `MapValidator` fails (duplicate node ids? or structurally wrong).
**Prevention:** Prompt rule: "at most one non-connector component per pixel location". Document in prompt. Post-expansion sanity check: no two INTERSECTION nodes within 5 px.
**Warning signs:** `MapValidator` returns "Duplicate node IDs" or "Intersection n_... has no roads".

### Pitfall 4: `STRAIGHT_SEGMENT` length mismatch (Claude estimate vs geometric)
**What goes wrong:** Claude says `lengthPx=80`, but `distance(startPx,endPx)=200`. Engine renders short road between far nodes — visual disconnect.
**Prevention:** Use `max(claudeLength, geometricDistance)` AND log when they diverge by >50%. Favour geometric for rendering fidelity.

### Pitfall 5: Harness runs real Claude CLI in CI
**What goes wrong:** `mvn test` in CI calls `claude` binary, which either isn't installed (CI fail) or is (cost + flakiness).
**Prevention:** `@EnabledIfSystemProperty(named="vision.harness", matches="true")` — default off. Document in phase PLAN.

### Pitfall 6: Jackson polymorphic deserialisation of sealed interface
**What goes wrong:** Jackson pre-2.17 had rough edges with sealed interfaces + records. Annotations must be on the sealed interface, not the records.
**Prevention:** Spring Boot 3.3.5 pulls Jackson 2.17 which handles this fine, BUT — simpler to parse into a mutable `ComponentSpecDto` with a `String type` field, then `switch(dto.type)` into the record. Two-pass but bullet-proof.
**Warning signs:** `InvalidTypeIdException` at parse time.

## Code Examples

### Sealed interface + expand
```java
public sealed interface ComponentSpec permits RoundaboutFourArm, SignalFourWay, TIntersection, StraightSegment {
    String id();
    void expand(ExpansionContext ctx);
    Map<String, Point2D> armEndpoints();  // "north" -> world px
}

public record RoundaboutFourArm(String id, Point2D center, double rotationDeg, List<String> armsPresent)
        implements ComponentSpec {
    private static final double RING_R = 28.0;
    private static final double APPROACH_LEN = 200.0;

    @Override public void expand(ExpansionContext ctx) {
        // 1. Emit 4 ring nodes (always — per CONTEXT rule).
        // 2. Emit ENTRY + EXIT per arm in armsPresent at center + rotated(approach).
        // 3. Emit r_<arm>_in and r_<arm>_out with component-prefixed ids.
        // 4. Emit 4 ring roads (CCW) and 4 ROUNDABOUT intersections.
    }

    @Override public Map<String, Point2D> armEndpoints() {
        // Return world (x,y) for "north","east","south","west" based on rotation.
    }
}
```

### Jackson polymorphic JSON (two-pass approach — pragmatic)
```java
// DTO layer — mutable, Jackson-friendly
@Data static class ComponentSpecDto {
    String type;
    String id;
    Point2D centerPx;
    Point2D startPx;
    Point2D endPx;
    double rotationDeg;
    List<String> armsPresent;
    Double lengthPx;
}

ComponentSpec toSpec(ComponentSpecDto d) {
    return switch (d.type) {
        case "ROUNDABOUT_4ARM" -> new RoundaboutFourArm(d.id, d.centerPx, d.rotationDeg, d.armsPresent);
        case "SIGNAL_4WAY"     -> new SignalFourWay(d.id, d.centerPx, d.rotationDeg, d.armsPresent);
        case "T_INTERSECTION"  -> new TIntersection(d.id, d.centerPx, d.rotationDeg, d.armsPresent);
        case "STRAIGHT_SEGMENT"-> new StraightSegment(d.id, d.startPx, d.endPx, d.lengthPx);
        default -> throw new ClaudeCliParseException("Unknown component type: " + d.type);
    };
}
```

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Mockito + AssertJ (via `spring-boot-starter-test` 3.3.5) |
| Config file | `backend/pom.xml` |
| Quick run command | `./mvnw -pl backend -Dtest='ComponentVisionServiceTest,MapComponentLibraryTest,*ExpansionTest' test` |
| Full suite command | `./mvnw -pl backend test` |

### Phase Requirements → Test Map
| Req (from Success Criteria) | Behaviour | Test Type | Automated Command | File Exists? |
|------|----------|-----------|-------------------|-------------|
| Component expansion produces valid MapConfig | Each record's `expand()` emits nodes/roads/intersections that pass `MapValidator` | unit | `./mvnw -pl backend -Dtest='RoundaboutFourArmExpansionTest' test` | ❌ Wave 0 |
| `_in`/`_out` naming preserved | Reverse-road heuristic flips correctly for every component | unit | `./mvnw -pl backend -Dtest='ReverseRoadIdComponentTest' test` | ❌ Wave 0 |
| Stitching merges coincident arms | Two components with arms at same (x,y) fuse into single INTERSECTION | unit | `./mvnw -pl backend -Dtest='MapComponentLibraryTest#stitch_mergesCoincidentArms' test` | ❌ Wave 0 |
| STRAIGHT_SEGMENT fills gap | Non-coincident arms without STRAIGHT_SEGMENT throw | unit | same class, different method | ❌ Wave 0 |
| New endpoint wiring | Controller returns 200 MapConfig, 422 on bad JSON, 503 on CLI failure | MVC test | `./mvnw -pl backend -Dtest='VisionControllerTest' test` (exists — extend) | partial (extend existing) |
| Phase 20 untouched | All `ClaudeVisionServiceTest` + `OsmPipelineServiceTest` tests pass unchanged | unit | existing | ✓ |
| Fixture roundtrip | `/tmp/roundabout-test.png` → analyze-components → topology matches `roundabout.json` | integration | `./mvnw -pl backend -Dvision.harness=true -Dtest='VisionComparisonHarness' test` | ❌ Wave 0 |

### Sampling Rate
- **Per task commit:** quick run command above (component tests only, < 10s).
- **Per wave merge:** full `./mvnw -pl backend test` (no system property — harness skipped).
- **Phase gate:** full suite green + manual harness run producing a favourable diff report.

### Wave 0 Gaps
- [ ] `backend/src/test/java/com/trafficsimulator/vision/components/RoundaboutFourArmExpansionTest.java`
- [ ] `backend/src/test/java/com/trafficsimulator/vision/components/SignalFourWayExpansionTest.java`
- [ ] `backend/src/test/java/com/trafficsimulator/vision/components/TIntersectionExpansionTest.java`
- [ ] `backend/src/test/java/com/trafficsimulator/vision/components/StraightSegmentExpansionTest.java`
- [ ] `backend/src/test/java/com/trafficsimulator/service/MapComponentLibraryTest.java` (stitching: merge-coincident, reject-non-coincident, orphan-arm-boundary)
- [ ] `backend/src/test/java/com/trafficsimulator/service/ComponentVisionServiceTest.java` (prompt sanity + JSON parse with mocked CLI)
- [ ] `backend/src/test/java/com/trafficsimulator/integration/VisionComparisonHarness.java`
- [ ] Extend `VisionControllerTest` (if it exists — otherwise create) to cover the 2 new endpoints.

## Risks & Gotchas

1. **Claude CLI output stability.** Phase 20 saw 3 distinct regressions on the same image. The component pipeline shrinks Claude's output surface massively (dozens of nodes → handful of components) — this is the primary risk reduction. Still, expect at least one iteration on prompt wording. **Mitigation:** ship harness and diff report early; iterate on prompt with fixture in the loop.

2. **`reverseRoadId` string-replace trap.** Already covered (Pitfall 1). Any component prefix containing `_in` silently breaks U-turn prevention across the whole map. Regex-gate at expansion time, assert in tests.

3. **MapValidator: intersection must have ≥1 road.** If stitching deletes an arm's ENTRY/EXIT but leaves an orphaned intersection node, validator fails with "has no roads connecting to it". **Mitigation:** after stitching, scan intersections and drop any whose nodeId is no longer in any road's from/to.

4. **Engine routing assumption: ring orientation is CCW.** Matches `roundabout.json` (`r_ring_nw`, `r_ring_ws`, `r_ring_se`, `r_ring_en`). Rotation parameter must NOT flip ring direction — rotate arm placement only. Test: rotationDeg=90 still emits CCW ring.

5. **Component id collisions with user-drawn maps.** Not an issue for MVP (output goes straight to sim, not merged with other maps). Note for future persistence phases.

6. **Frontend button duplication.** `MapSidebar` will have 3 similar buttons. Risk of user confusion. Out of scope to redesign — match existing wording exactly and add a tiny "(experimental)" tag per CONTEXT discretion allowance.

7. **Jackson sealed-interface deserialisation.** Two-pass DTO → record mapping sidesteps the whole `@JsonTypeInfo` dance. Strongly recommended over polymorphic Jackson config.

8. **Harness fixture path hardcoded to `/tmp`.** Fine for local dev (Linux/mobile workflow per user MEMORY), but fragile. Guard with `assumeTrue(Files.exists(...))`.

## Sources

### Primary (HIGH confidence) — all in-repo
- `backend/src/main/java/com/trafficsimulator/service/ClaudeVisionService.java` — existing pipeline, prompt template, CLI runner patterns.
- `backend/src/main/java/com/trafficsimulator/service/OsmStaticMapService.java` — bbox composer for reuse.
- `backend/src/main/java/com/trafficsimulator/controller/VisionController.java` — endpoint conventions, exception handlers.
- `backend/src/main/java/com/trafficsimulator/config/MapConfig.java` — output target structure.
- `backend/src/main/java/com/trafficsimulator/config/MapValidator.java` — validation contract (lane 1-4, positive length/speed, connected intersections, signal phases).
- `backend/src/main/java/com/trafficsimulator/engine/IntersectionGeometry.java` — `reverseRoadId` naming convention.
- `backend/src/main/resources/maps/roundabout.json`, `four-way-signal.json` — expansion templates (verbatim geometry constants).
- `backend/src/test/java/com/trafficsimulator/service/ClaudeVisionServiceTest.java` — test idiom template.
- `backend/pom.xml` — confirmed: Spring Boot 3.3.5, JUnit 5, Cucumber, ArchUnit available; **no** `exec-maven-plugin`.
- `/home/sebastian/traffic-simulator/CLAUDE.md` — project constraints.
- `.planning/phases/21-.../21-CONTEXT.md` — locked decisions.

## Metadata

**Confidence breakdown:**
- Component model (sealed interface + records): HIGH — idiomatic Java 17, Spring Boot 3.3.5 friendly.
- Stitching algorithm: HIGH — derives directly from `MapValidator` + `reverseRoadId` constraints.
- Prompt design: MEDIUM — Claude's adherence to enumerated types is empirically good but Phase 20 taught us to verify.
- Harness placement: HIGH — confirmed no `exec-maven-plugin` in pom; `@SpringBootTest` pattern matches repo conventions.
- Risks: HIGH — each grounded in existing code or Phase 20 regression evidence.

**Research date:** 2026-04-13
**Valid until:** 2026-05-13 (stable — no fast-moving external dependencies).
