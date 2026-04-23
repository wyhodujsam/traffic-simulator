---
phase: 23-graphhopper-based-osm-parser
plan: 02
subsystem: api
tags: [java, spring-boot, osm, interface, strategy-pattern, ab-testing]

# Dependency graph
requires:
  - phase: 18-osm-data-pipeline
    provides: "OsmPipelineService (Overpass-based) concrete converter — now the first OsmConverter implementation"
  - phase: 23-graphhopper-based-osm-parser
    provides: "23-01 OsmConversionUtils shared helpers (reused by future GraphHopper/osm2streets impls via the same interface)"
provides:
  - "OsmConverter interface (fetchAndConvert / converterName / isAvailable) — shared contract for all current and future OSM→MapConfig converters"
  - "OsmPipelineService retrofitted to implement OsmConverter with stable converterName() == \"Overpass\""
  - "Graceful-degradation hook (isAvailable()) ready for Plan 04's @Lazy + ObjectProvider wiring of GraphHopperOsmService"
affects:
  - "23-03 GraphHopperOsmService — implements OsmConverter"
  - "23-04 Controller wiring — injects OsmConverter / ObjectProvider<OsmConverter>, calls isAvailable() to gate 503"
  - "23-05 A/B comparison test — uses converterName() for log labelling"
  - "Phase 24 osm2streets — third OsmConverter implementation slot, no interface change needed"

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Strategy / polymorphic converter — single OsmConverter contract, multiple implementations chosen per endpoint"
    - "Default-method availability gate — idiomatic Java 8+ way to let implementations declare runtime usability without forcing override"

key-files:
  created:
    - backend/src/main/java/com/trafficsimulator/service/OsmConverter.java
  modified:
    - backend/src/main/java/com/trafficsimulator/service/OsmPipelineService.java

key-decisions:
  - "converterName() default returns getClass().getSimpleName(); Phase 18 overrides with literal \"Overpass\" (short, log-friendly, stable across refactors of the class name)"
  - "isAvailable() included as default boolean = true now, not deferred — 23-SPIKE A7 proved that failing @Service beans abort the Spring context, so Plan 04 will need a non-@Service-lifecycle way to report unavailability; adding the hook now is zero-risk additive and avoids a second interface edit later"
  - "Interface lives in the ..service.. package (same as implementations) — ArchitectureTest.layered_architecture does not define a Service layer, so no layer violation; verified by running ArchitectureTest green"
  - "NO @Service / @Component on the interface itself — it is a pure contract; Spring discovers beans via implementations (OsmPipelineService already has @Service)"

patterns-established:
  - "Polymorphic OSM converter contract: future implementations drop a new @Service class implementing OsmConverter; controller-side wiring in Plan 04 selects by converterName() or by ObjectProvider availability"
  - "Converter label vs. class name: override converterName() with a stable short label when the class may be renamed/moved — avoids A/B log breakage when refactoring"

requirements-completed: [GH-IFACE-01]

# Metrics
duration: 4m 34s
completed: 2026-04-23
---

# Phase 23 Plan 02: OsmConverter interface and OsmPipelineService retrofit Summary

**Thin OsmConverter interface (fetchAndConvert + default converterName + default isAvailable) with OsmPipelineService retrofitted to implement it — zero behaviour change on Phase 18, plug point ready for Plans 03/04 and Phase 24 osm2streets.**

## Performance

- **Duration:** 4m 34s
- **Started:** 2026-04-23T06:01:58Z
- **Completed:** 2026-04-23T06:06:32Z
- **Tasks:** 1/1
- **Files modified:** 2 (1 created, 1 modified)

## Accomplishments
- Created `OsmConverter` interface with three methods: abstract `fetchAndConvert(BboxRequest) -> MapConfig`, default `converterName() -> String`, default `isAvailable() -> boolean`. Full Javadoc on each including the exception taxonomy contract (`IllegalStateException` for no-roads, `RestClientException` for upstream outage).
- Retrofitted `OsmPipelineService` (Phase 18, Overpass-based) as `implements OsmConverter` — one-line class-declaration change, `@Override` added on `fetchAndConvert`, new `@Override public String converterName() { return "Overpass"; }` method.
- Phase 18 public behaviour confirmed unchanged: `OsmControllerTest` (3/3), `OsmPipelineServiceTest` (14/14), full backend suite (338/338) all green. The existing controller still injects `OsmPipelineService` by concrete type and continues to work.
- Graceful-degradation hook `isAvailable()` present as default `true`, ready for Plan 04's `@Lazy` + `ObjectProvider<GraphHopperOsmService>` wiring without any further interface edits.

## Task Commits

Each task was committed atomically:

1. **Task 1: Create OsmConverter interface and retrofit OsmPipelineService** — `ba30915` (feat)

_No metadata commit — this plan runs inside a parallel worktree and is forbidden from touching STATE.md / ROADMAP.md. SUMMARY.md is committed together with the final metadata commit below._

## Files Created/Modified
- `backend/src/main/java/com/trafficsimulator/service/OsmConverter.java` — NEW, 68 lines. Pure interface (no annotations), three methods, full Javadoc referencing 23-SPIKE A7.
- `backend/src/main/java/com/trafficsimulator/service/OsmPipelineService.java` — modified, +13/-1 lines. Added `implements OsmConverter`, `@Override` on `fetchAndConvert`, new `converterName()` override returning `"Overpass"`.

## Decisions Made
- **converterName() default == simple class name, Phase 18 override == literal `"Overpass"`.** Rationale: class names change during refactors; logs like `"Overpass: 42 roads / 11 intersections; GraphHopper: 38 roads / 9 intersections"` must stay stable. Short label wins over FQN for human readability. Matches 23-CONTEXT "Frontend integration" section which specifies the exact "Overpass: …" prefix.
- **`isAvailable()` added as default true, not deferred to Plan 04.** Rationale: 23-SPIKE `## A7` verdict was FAIL — a failing `@Service` bean DOES abort the Spring context. The orchestrator's prompt-level `<spike_impact_note>` explicitly instructed adding `isAvailable()` as a zero-risk additive hook so the Plan 04 `@Lazy` + `ObjectProvider` retrofit has an idiomatic way to report unavailability without re-touching the interface. PLAN.md acceptance-criteria grep allows this addition (it's specified as optional / A7-dependent).
- **Interface placed in `..service..` package.** ArchitectureTest `layered_architecture` does not define a Service layer explicitly; service package is considered "below Controller" in the layered-arch graph. Adding a pure interface there introduces no layer violation. Verified: ArchitectureTest passes 7/7.
- **No annotations on the interface.** Pure contract. Spring discovers implementations via existing `@Service` on `OsmPipelineService`; no `@Component` / `@Service` on `OsmConverter` itself.

## Deviations from Plan

**1. [Optional addition — NOT a Rule 1/2/3 deviation] Added `default boolean isAvailable()` hook to the interface.**
- **Source:** Orchestrator prompt `<spike_impact_note>` + PLAN.md Task 1 "Step 2 (conditional)" branch.
- **Reasoning:** PLAN.md internally reverses the SPIKE's PASS/FAIL semantics (PLAN calls "context-aborts" PASS, SPIKE records the same outcome as FAIL). The orchestrator's spike_impact_note disambiguates in favour of adding `isAvailable()` — "additive, zero-risk" and aligned with Plan 04's future graceful-degradation needs.
- **Files modified:** `backend/src/main/java/com/trafficsimulator/service/OsmConverter.java` (added ~10 lines: Javadoc + 3-line default method).
- **Verification:** Full backend suite green (338/338). `grep -c "default boolean isAvailable" OsmConverter.java` returns 1.
- **Committed in:** `ba30915` (part of Task 1 commit).

---

**Total deviations:** 1 (planned / optional addition per orchestrator instruction, not an auto-fix).
**Impact on plan:** Zero negative impact. Additive-only. Saves one interface edit in Plan 04.

## Issues Encountered

- **PLAN.md vs SPIKE.md PASS/FAIL semantics inversion.** The PLAN labels "context survives" as A7=FAIL (desirable); the SPIKE labels "context aborts" as A7=FAIL (undesirable, actual observed outcome). Resolved by the orchestrator's prompt-level `<spike_impact_note>` which explicitly instructed including `isAvailable()`. Both the PLAN's acceptance criteria and the spike outcome are satisfied with the additive approach.
- **No `mvnw` wrapper in repo.** PLAN's verify commands invoke `./mvnw`; repo uses system `mvn`. Substituted `mvn -q test -Dtest=…` — same Surefire output, same exit codes. Not a deviation, just a local-toolchain adjustment.

## User Setup Required

None — this is a pure-Java additive change. No new dependencies, no config, no environment variables.

## Next Phase Readiness

- **Plan 03 (GraphHopperOsmService skeleton):** READY. The interface to implement is frozen; the class will be `public class GraphHopperOsmService implements OsmConverter { … }` with `@Override public String converterName() { return "GraphHopper"; }` and (per 23-SPIKE A7) `@Override public boolean isAvailable() { return <engine-initialised-check>; }`.
- **Plan 04 (controller + wiring):** READY. Controller can inject `ObjectProvider<GraphHopperOsmService>` (or the broader `List<OsmConverter>` if we later add a dispatch-by-name pattern) and gate 503 responses on `isAvailable()` → false.
- **Phase 24 (osm2streets):** READY. Drop-in slot — implement `OsmConverter`, override `converterName()` with `"osm2streets"`.
- **No blockers.** Phase 18's `/api/osm/fetch-roads` endpoint and all 338 pre-existing backend tests remain green.

## Verification Evidence

Acceptance-criteria grep results (all PASS):

```
$ grep -c "interface OsmConverter" backend/.../OsmConverter.java        → 1
$ grep -c "MapConfig fetchAndConvert(BboxRequest" .../OsmConverter.java → 1
$ grep -c "default String converterName" .../OsmConverter.java          → 1
$ grep -c "default boolean isAvailable" .../OsmConverter.java           → 1
$ grep "implements OsmConverter" .../OsmPipelineService.java            → MATCH
$ grep -cE "@Override" .../OsmPipelineService.java                      → 2
$ grep 'return "Overpass"' .../OsmPipelineService.java                  → MATCH
$ grep -rn "implements OsmConverter" backend/src/main/java/             → 1 hit (OsmPipelineService only)
```

Test results:

```
$ mvn test -Dtest=ArchitectureTest,OsmPipelineServiceTest,OsmControllerTest
  ArchitectureTest:          7 tests, 0 failures
  OsmPipelineServiceTest:   14 tests, 0 failures
  OsmControllerTest:         3 tests, 0 failures
  Cucumber (as side-load):  24 tests, 0 failures
  Total:                    48 tests, 0 failures  → BUILD SUCCESS

$ mvn test                  # full backend suite
  Tests run: 338, Failures: 0, Errors: 0, Skipped: 0 → BUILD SUCCESS
```

Matches pre-Phase-23 baseline exactly: 338 tests, 0 failures.

## Known Stubs

None. The interface adds only pure-contract and default-true methods; no placeholder data, no hardcoded empty collections flow to UI rendering, no TODO/FIXME markers introduced.

## Self-Check: PASSED

- Artifact `backend/src/main/java/com/trafficsimulator/service/OsmConverter.java` — FOUND
- Artifact `backend/src/main/java/com/trafficsimulator/service/OsmPipelineService.java` — FOUND (retrofitted)
- Artifact `.planning/phases/23-…/23-02-SUMMARY.md` — FOUND
- Commit `ba30915` (feat 23-02: add OsmConverter interface and retrofit OsmPipelineService) — FOUND in git log

---
*Phase: 23-graphhopper-based-osm-parser*
*Plan: 02 (Wave 2) — OsmConverter interface + OsmPipelineService retrofit*
*Completed: 2026-04-23*
