# Phase 23 — Wave 0 Spike Report

**Date:** 2026-04-22
**Spike source:** backend/src/test/java/com/trafficsimulator/spike/GraphHopperSpikeTest.java
**GraphHopper version probed:** 10.2 (compile-scope dep added to backend/pom.xml this plan)
**Spring Boot version:** 3.3.5 (parent POM)
**Full backend suite after spike:** 310 tests passing (308 pre-existing + 2 spike tests; zero regressions)

## Summary

A1 held (PASS): GraphHopper 10.2's `WaySegmentParser.EdgeHandler.nodeTags` callback parameter
surfaces OSM `<node>` tags in full, indexed parallel to the `PointList` geometry. Plan 03 can
proceed with the single-pass design in RESEARCH.md §4 without the two-pass fallback.

A7 did NOT hold (FAIL): a failing `@Bean` aborts the Spring context with
`BeanCreationException`. This invalidates the research assumption that a broken
`GraphHopperOsmService` would degrade gracefully. Plan 02 MUST wire the new service as a
bail-out-safe bean — `@Lazy` + `Optional<GraphHopperOsmService>` / `ObjectProvider` injection is
the recommended mitigation, or `@ConditionalOnClass(WaySegmentParser.class)` if we ever want the
dependency to be absent at runtime.

Net effect on phase scope: +1 task in Plan 02 (~5% plan context). Plans 03–06 unchanged.

## A1 — WaySegmentParser.EdgeHandler exposes OSM node tags

**Research claim (RESEARCH.md §4):** `nodeTags` parameter surfaces
`{"highway": "traffic_signals"}` for signal-tagged OSM nodes in GraphHopper 10.2, matching the
behaviour described in PR #2448.

**Result:** PASS

**Observed EdgeHandler signature (10.2, extracted via `javap`):**

```text
public interface com.graphhopper.reader.osm.WaySegmentParser$EdgeHandler {
  public abstract void handleEdge(
      int,
      int,
      com.graphhopper.util.PointList,
      com.graphhopper.reader.ReaderWay,
      java.util.List<java.util.Map<java.lang.String, java.lang.Object>>);
}
```

Exactly matches the five-arg shape assumed in RESEARCH.md §3/§4: `(from, to, pointList, way,
nodeTags)`.

**Evidence (captured live from the spike's 3-node fixture):**

```
edgeCount=1
capturedNodeTags (one entry per parsed segment):
  edge 0->1 points=3 wayTags={highway=residential} nodeTags=[{}, {highway=traffic_signals}, {}]
signalTagFound=true
```

**Important behavioural note discovered during the spike:**

- `nodeTags` is parallel to `PointList` — it has one entry per node along the segment
  (towers + pillars), NOT only tower nodes. Length matches `pointList.size()`.
- Index 0 is the `from` tower; last index is the `to` tower; intermediate indices are pillars.
- In our fixture, the signal-tagged node (OSM id 2) is NOT a tower — it has degree 2 and no
  other way touches it, so WaySegmentParser kept it as a pillar node. The signal tag still
  appears at `nodeTags.get(1)` — i.e., GraphHopper preserves the tag on pillar nodes when they
  carry any OSM tag.
- Empty-tag nodes (OSM ids 1 and 3) show up as empty maps `{}`, NOT null. Safe to iterate with
  `for (Map<String,Object> nt : nodeTags)`.

**Design impact on Plan 03:**

- **No two-pass fallback needed.** The single-pass WaySegmentParser design from RESEARCH.md §4
  works as specified.
- **But the tag-to-intersection mapping changes slightly from the research pseudocode:**
  the research shows inspecting only `nodeTags.get(0)` (the `from` tower) and
  `nodeTags.get(last)` (the `to` tower) for signal detection. In reality, a signal node can
  be a pillar (degree-2 node with a signal tag but no other way), in which case it lives at
  some intermediate `nodeTags` index. Plan 03 must iterate the FULL `nodeTags` list,
  collecting signal tags at every index, and either (a) split the segment at that pillar to
  promote it to a tower, or (b) attach signal metadata to the nearest tower endpoint.
- Recommend option (a) — promote signal-carrying pillar nodes to towers via
  `.setSplitNodeFilter(node -> "traffic_signals".equals(node.getTag("highway")))`. This is a
  one-line change vs. the research's edge-handler-inspection approach and produces cleaner
  intersection geometry.

## A7 — Failing @Service bean does not abort Spring context

**Research claim (RESEARCH.md §12 / Assumptions Log):** if `GraphHopperOsmService` fails to
construct, the Spring context still comes up and Phase 18's `/fetch-roads` keeps working.

**Result:** FAIL (the claim did NOT hold — context ABORTED).

NOTE on naming: A "FAIL" here means the research assumption was wrong. The OUTCOME is BAD — we
must add explicit mitigation in Plan 02. See "Design impact" below.

**Evidence (captured from programmatic `SpringApplicationBuilder.run()` with a failing `@Bean`):**

```
outcome=CONTEXT_ABORTED_BeanCreationException
exceptionClass=org.springframework.beans.factory.BeanCreationException
exceptionMessage=Error creating bean with name 'dummyBean' defined in
                 com.trafficsimulator.spike.GraphHopperSpikeTest$FailingServiceBeanProbe:
                 Failed to instantiate [com.trafficsimulator.spike.GraphHopperSpikeTest$DummyBean]:
                 Factory method 'dummyBean' threw exception with message:
                 simulated init failure (A7 spike probe)
```

The wrapping exception type `org.springframework.beans.factory.BeanCreationException` is stable
across Spring Boot 3.x — safe to reference in downstream `@ConditionalOnBean` / exception
handler code.

**Design impact on Plan 02:**

- Plan 02 MUST NOT wire `GraphHopperOsmService` with a plain eager `@Service` whose constructor
  or `@PostConstruct` could throw. As demonstrated, that takes down the ENTIRE Spring context
  and breaks Phase 18's `/api/osm/fetch-roads` endpoint in the process — the opposite of the
  coexistence requirement in 23-CONTEXT.md.
- **Recommended mitigation (in priority order):**
  1. Make `GraphHopperOsmService` a `@Service @Lazy` bean with all expensive work (BaseGraph
     allocation, elevation-provider init) moved OUT of the constructor and INTO the per-request
     `convert(...)` method. The class itself instantiates cheaply.
  2. Inject via `ObjectProvider<GraphHopperOsmService>` in `OsmController` (or the Phase-23 new
     controller). Controller method calls `provider.getIfAvailable()` and returns 503
     "GraphHopper backend unavailable" on null.
  3. Catch `WaySegmentParser`-construction failures inside `convert(...)` and return them as a
     domain exception that maps to HTTP 503 via a `@ControllerAdvice`. Never let them bubble
     to Spring's bean-lifecycle.
- **Alternative (heavier):** `@ConditionalOnClass(WaySegmentParser.class)` on the service bean
  — only useful if we ever want to ship a build without GraphHopper on the classpath. Not
  needed for Phase 23 since the dependency is mandatory; keep this in reserve for a future
  "slim-jar" SKU.
- Phase 18's `OsmPipelineService` stays untouched — Plan 02 only adds the new service and
  controller endpoint. If the new service blows up, Phase 18's `/api/osm/fetch-roads` remains
  served.

## Dependency check

**pom.xml state:** `com.graphhopper:graphhopper-core:10.2` present on compile classpath
(commit d29064a).

**Dependency tree verification:**

```
cd backend && mvn dependency:tree -Dincludes=com.graphhopper:graphhopper-core
  → \- com.graphhopper:graphhopper-core:jar:10.2:compile
  → BUILD SUCCESS
```

**Jackson conflict check:** `mvn dependency:tree | grep "OMITTED for conflict" |
grep -E "jackson-core|jackson-databind|jackson-dataformat-xml"` returned zero lines. No
Jackson version clashes.

**Compile check:** `mvn compile` exits BUILD SUCCESS unchanged from pre-Phase-23 state.

## Decision for Wave 1+

- **Wave 1 (Plan 01 — proper `GraphHopperOsmService` skeleton, dep stays on compile classpath):**
  proceeds as specified. Dep already in place; Plan 01 just turns it from "added" to "used".
- **Wave 2 (Plan 02 — controller + service wiring):** proceeds WITH AMENDMENT.
  - AMENDMENT: add `@Lazy` to the `@Service` annotation on `GraphHopperOsmService`, OR inject
    it via `ObjectProvider<GraphHopperOsmService>` in the controller so construction failure
    does not propagate. Add a `@ControllerAdvice` or per-endpoint `try/catch` mapping
    `BeanCreationException` / `RuntimeException` to HTTP 503 with body
    `{"error": "GraphHopper backend unavailable", "fallback": "use /api/osm/fetch-roads"}`.
  - File impact: +1 small class or +5 lines in existing controller advice; no new tasks.
- **Wave 3 (Plan 03 — Graph → MapConfig conversion):** proceeds WITH AMENDMENT.
  - AMENDMENT: use `setSplitNodeFilter` to promote signal-tagged pillar nodes to tower nodes,
    so `highway=traffic_signals` always appears at `nodeTags.get(0)` or `nodeTags.get(last)` —
    rather than sifting through the entire `nodeTags` list in the EdgeHandler callback. This
    is a cleanliness improvement, not a correctness requirement; the research's
    `nodeTags.get(0) / nodeTags.get(last)` approach also works if we additionally scan
    intermediate indices.
  - File impact: 1 extra line in the parser-builder chain. No new tasks.
- **Waves 4–6 (Plans 04–06 — A/B compare, fixtures, docs):** UNCHANGED. Spike findings do not
  touch plans past Wave 3.
- **Wave 7 (Plan 07 — spike cleanup):** UNCHANGED. Still deletes
  `backend/src/test/java/com/trafficsimulator/spike/` and this 23-SPIKE.md (or archives it,
  per Plan 07's disposition).

## Raw Observations

The spike test appended these bullets live during the `mvn test` run (may appear twice because
the test suite ran once scoped to the spike class and once as part of the full suite —
harmless duplication, both runs produced identical results):

- **A7:** FAIL
```
outcome=CONTEXT_ABORTED_BeanCreationException
exceptionClass=org.springframework.beans.factory.BeanCreationException
exceptionMessage=Error creating bean with name 'dummyBean' defined in com.trafficsimulator.spike.GraphHopperSpikeTest$FailingServiceBeanProbe: Failed to instantiate [com.trafficsimulator.spike.GraphHopperSpikeTest$DummyBean]: Factory method 'dummyBean' threw exception with message: simulated init failure (A7 spike probe)
```
- **A1:** PASS
```
edgeCount=1
capturedNodeTags (one entry per parsed segment):
  edge 0->1 points=3 wayTags={highway=residential} nodeTags=[{}, {highway=traffic_signals}, {}]
signalTagFound=true
```
