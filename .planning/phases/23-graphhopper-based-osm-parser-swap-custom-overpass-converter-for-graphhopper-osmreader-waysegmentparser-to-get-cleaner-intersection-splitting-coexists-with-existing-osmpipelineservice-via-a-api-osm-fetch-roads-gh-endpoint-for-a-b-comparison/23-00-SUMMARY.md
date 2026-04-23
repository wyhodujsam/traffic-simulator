---
phase: 23-graphhopper-based-osm-parser
plan: 00
subsystem: osm-pipeline
tags: [graphhopper, waysegmentparser, spring-context, spike, osm, research]

# Dependency graph
requires:
  - phase: 18-osm-data-pipeline
    provides: OsmPipelineService baseline (coexists with GraphHopper path, must remain untouched)
provides:
  - GraphHopper 10.2 on backend compile classpath (no Jackson/Logback conflicts)
  - Empirical A1 verdict (WaySegmentParser.EdgeHandler.nodeTags exposes OSM node tags)
  - Empirical A7 verdict (failing @Bean aborts Spring context — assumption invalidated)
  - Design-impact amendments for Wave 2 (@Lazy + ObjectProvider) and Wave 3 (setSplitNodeFilter for signals)
affects: [23-01-graphhopper-service-skeleton, 23-02-controller-wiring, 23-03-graph-to-mapconfig]

# Tech tracking
tech-stack:
  added: [com.graphhopper:graphhopper-core:10.2]
  patterns:
    - Spike subpackage com.trafficsimulator.spike (throwaway, deleted in Plan 07)
    - Non-gating discovery tests that append bullets to phase spike report
    - Programmatic Spring bootstrap via SpringApplicationBuilder in @Test body (avoids @SpringBootTest class-level failure)

key-files:
  created:
    - backend/src/test/java/com/trafficsimulator/spike/GraphHopperSpikeTest.java
    - .planning/phases/23-.../23-SPIKE.md
  modified:
    - backend/pom.xml

key-decisions:
  - "GraphHopper 10.2 pinned (not 11.0) for Jackson 2.17.2 lock-step with Spring Boot 3.3.5"
  - "Dependency scope=compile (not test) — spike test needs it + Plan 01 would move it to compile anyway"
  - "Plan 02 MUST wire GraphHopperOsmService with @Lazy + ObjectProvider — A7 proved eager init failure takes down the whole context"
  - "Plan 03 adds setSplitNodeFilter(highway=traffic_signals) to promote signal pillars to towers — A1 showed signal tags land on pillar nodes, not just towers"

patterns-established:
  - "Wave-0 spike report: non-gating tests write empirical observations to {phase}-SPIKE.md; the human-authored SPIKE.md wraps them with verdict + design-impact sections"
  - "Per-request BaseGraph + RAMDirectory + WaySegmentParser (no shared state, no disk cache) — aligns with RESEARCH.md §6 concurrency model"

requirements-completed: [GH-SPIKE-A1, GH-SPIKE-A7]

# Metrics
duration: 7min
completed: 2026-04-22
---

# Phase 23 Plan 00: Wave-0 Spike Summary

**GraphHopper 10.2 on classpath; A1 (nodeTags API) holds, A7 (graceful context survival) does NOT — Plan 02 must use @Lazy/ObjectProvider to keep Phase 18 endpoints alive when GraphHopper init fails.**

## Performance

- **Duration:** 7 min
- **Started:** 2026-04-23T05:35:34Z
- **Completed:** 2026-04-23T05:42:40Z
- **Tasks:** 3
- **Files modified:** 3 (1 modified: backend/pom.xml; 2 created: spike test, 23-SPIKE.md)

## Accomplishments

- Added `com.graphhopper:graphhopper-core:10.2` on the backend compile classpath with zero Jackson/Logback conflict warnings; full backend suite remained green.
- Produced empirical evidence that GraphHopper 10.2's `WaySegmentParser.EdgeHandler.nodeTags` parameter surfaces OSM node tags — signal-tagged node's `highway=traffic_signals` appeared verbatim at the expected index. Plan 03 can ship the single-pass RESEARCH.md §4 design.
- Produced empirical evidence that a failing `@Bean` aborts the Spring context with `org.springframework.beans.factory.BeanCreationException`. Plan 02's wiring design must change before it ships — eager `@Service` on `GraphHopperOsmService` would break Phase 18's `/api/osm/fetch-roads` coexistence requirement.
- Captured a secondary behavioural finding not in RESEARCH.md: `nodeTags` can carry tags on *pillar* nodes (degree-2 nodes with an OSM tag), not just towers. This drives the `setSplitNodeFilter` recommendation for Plan 03.

## Task Commits

Each task was committed atomically on branch `worktree-agent-ac48cd72`:

1. **Task 1: Add GraphHopper dependency to pom.xml** — `d29064a` (chore)
2. **Task 2: Write GraphHopperSpikeTest probing A1 and A7** — `c94d476` (test)
3. **Task 3: Write 23-SPIKE.md conclusions and design-impact section** — `e5b95f7` (docs)

## Files Created/Modified

- `backend/pom.xml` — added `com.graphhopper:graphhopper-core:10.2` (compile scope) between `spring-boot-starter-web` and `lombok`.
- `backend/src/test/java/com/trafficsimulator/spike/GraphHopperSpikeTest.java` — two non-gating discovery tests; package is throwaway (Plan 07 deletes it).
- `.planning/phases/23-.../23-SPIKE.md` — structured spike report with Summary, A1 verdict, A7 verdict, Dependency check, Decision for Wave 1+, and Raw Observations sections.

## Decisions Made

- **GraphHopper version 10.2, not 11.0:** RESEARCH.md §1 confirmed 10.2 uses Jackson 2.17.2 (matches Spring Boot 3.3.5 managed version); 11.0 would bring a mismatch. Verified in dependency tree: zero OMITTED-for-conflict warnings.
- **Compile scope, not test scope:** the spike test needs it now and Plan 01 would move it to compile regardless; saving one pom.xml round-trip.
- **No transitive exclusions:** `xmlgraphics-commons`, `kotlin-stdlib`, and `osmosis-osm-binary` come along. Research §1 table documented the tradeoff; we accept ~2.5 MB extra jar size for zero integration risk.
- **Spike test is non-gating:** appends observations via `Files.writeString(..., APPEND, CREATE)` instead of asserting PASS/FAIL. Rationale: A1/A7 are *discovery* questions — the research assumption could be wrong in either direction, and we want to capture the actual outcome rather than fail the build.
- **A7 uses programmatic `SpringApplicationBuilder.run()` inside a plain `@Test`, NOT `@SpringBootTest` class annotation:** plan-specified and necessary — `@SpringBootTest` bootstraps the context at class-load, which would fail before the `@Test` body runs, making observation of the exception type impossible.

## Deviations from Plan

None that required auto-fixing under Rules 1-3 — the plan specified `./mvnw` but the plan also pre-documented the wrapper's absence in `<deviation_notes>`, so using `mvn` was the expected path, not a deviation.

**Secondary finding incorporated into the report (not a deviation from execution, but an addition to design impact):** `nodeTags` carries tags on pillar nodes, not just towers. Signal-tagged OSM nodes that happen to be pillars (degree 2 with only one way through) still expose `highway=traffic_signals` via `nodeTags.get(midIndex)`. This drives the Plan 03 recommendation to use `setSplitNodeFilter` to promote such pillars to towers for cleaner intersection geometry. Documented in `23-SPIKE.md` §Decision for Wave 1+.

## Issues Encountered

- **Duplicate append to 23-SPIKE.md during suite run:** the spike test was executed twice (once scoped via `-Dtest=GraphHopperSpikeTest`, once as part of the full `mvn test`), producing two identical bullet sets at the bottom of the file. Task 3's authored report preserves both under `## Raw Observations` with a note explaining the duplication. No corrective action needed; Plan 07 deletes the file anyway.
- **Bash `mvn -pl backend`** failed with "Could not find the selected project in the reactor" because the repo has no root POM — only `backend/pom.xml`. Worked around by `cd backend && mvn ...` in all verification commands. Already implied by the plan's `<deviation_notes>` mentioning no mvnw wrapper.

## User Setup Required

None — no external service configuration required. GraphHopper is a pure-library dependency, no network/API keys needed.

## Spike Verdict (primary output)

- **A1 (WaySegmentParser.EdgeHandler.nodeTags surfaces OSM node tags):** PASS. Evidence: `nodeTags=[{}, {highway=traffic_signals}, {}]` for a 3-node way with the middle node tagged. Plan 03 proceeds without two-pass fallback.
- **A7 (failing @Service bean does NOT abort Spring context):** FAIL (claim did not hold). Evidence: `CONTEXT_ABORTED_BeanCreationException` — Spring wraps constructor failures in `BeanCreationException` and propagates up through `SpringApplicationBuilder.run()`. Plan 02 requires the `@Lazy` + `ObjectProvider` + 503 fallback mitigation.

Full design-impact details in `23-SPIKE.md` at this phase dir.

## Verification Results

- `grep "graphhopper-core" backend/pom.xml` → 1 matching line with version 10.2. PASS.
- `cd backend && mvn dependency:tree -Dincludes=com.graphhopper:graphhopper-core` → `\- com.graphhopper:graphhopper-core:jar:10.2:compile`, BUILD SUCCESS. PASS.
- `mvn dependency:tree | grep "OMITTED for conflict" | grep -E "jackson-core|jackson-databind|jackson-dataformat-xml"` → 0 lines. PASS.
- `cd backend && mvn compile` → BUILD SUCCESS. PASS.
- `cd backend && mvn test -Dtest=GraphHopperSpikeTest` → 26 tests (24 Cucumber + 2 spike), BUILD SUCCESS. PASS.
- `cd backend && mvn test` → 310 tests total (308 pre-existing + 2 new), 0 failures, 0 errors, 0 skipped. PASS.
- `23-SPIKE.md` contains 6 H2 sections (Summary, A1, A7, Dependency check, Decision for Wave 1+, Raw Observations) and both `**Result:** PASS` + `**Result:** FAIL` lines. PASS.

## Next Phase Readiness

- Plan 01 (GraphHopperOsmService skeleton) — ready as specified; dep already present.
- Plan 02 (service + controller wiring) — proceed WITH AMENDMENT: use `@Lazy` on `@Service` and inject via `ObjectProvider<GraphHopperOsmService>` in the controller with 503 fallback mapping. Rationale captured in `23-SPIKE.md` §A7 Design impact.
- Plan 03 (graph → MapConfig conversion) — proceed WITH AMENDMENT: add `setSplitNodeFilter(node -> "traffic_signals".equals(node.getTag("highway")))` to the WaySegmentParser.Builder chain so signal-carrying pillar nodes become tower nodes. Cleaner geometry; `nodeTags.get(0)` / `nodeTags.get(last)` inspection then captures all signals without scanning intermediate indices.
- Plans 04–06 — unchanged.
- Plan 07 — unchanged; will delete `backend/src/test/java/com/trafficsimulator/spike/` and archive/delete `23-SPIKE.md` per its disposition.

## Self-Check: PASSED

- `backend/pom.xml` contains graphhopper-core 10.2 — FOUND.
- `backend/src/test/java/com/trafficsimulator/spike/GraphHopperSpikeTest.java` — FOUND.
- `.planning/phases/23-.../23-SPIKE.md` — FOUND (197 lines, 6 H2 sections).
- Commit `d29064a` (Task 1) — FOUND in git log.
- Commit `c94d476` (Task 2) — FOUND in git log.
- Commit `e5b95f7` (Task 3) — FOUND in git log.
- Full backend suite 310 tests passing — VERIFIED via `mvn test` BUILD SUCCESS.
- No edits to STATE.md or ROADMAP.md — VERIFIED via `git status` (neither file appears in commit diff stats).

---
*Phase: 23-graphhopper-based-osm-parser*
*Plan: 00*
*Completed: 2026-04-22*
