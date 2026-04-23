---
phase: 23
plan: 07
subsystem: docs + spike-cleanup + final gate
requirements: []
key-files:
  created:
    - backend/docs/osm-converters.md
  deleted:
    - backend/src/test/java/com/trafficsimulator/spike/GraphHopperSpikeTest.java
    - backend/src/test/java/com/trafficsimulator/spike/ (directory removed)
execution-mode: inline (orchestrator, not worktree agent — see deviation)
---

# Phase 23 Plan 07: Docs + Spike Cleanup + Final Gate

Authored `backend/docs/osm-converters.md` documenting the two OSM converter implementations (Phase 18 Overpass + Phase 23 GraphHopper), the A/B fairness contract (shared constants via `OsmConversionUtils`), the Wave-0 spike findings (A1 PASS, A7 FAIL with `@Lazy` mitigation), and a step-by-step recipe for adding a third converter in Phase 24 (osm2streets).

Deleted the Wave-0 spike code (`backend/src/test/java/com/trafficsimulator/spike/GraphHopperSpikeTest.java` + the now-empty `spike/` package).

Ran the full test gate: all three suites green.

## Final test gate

```
backend:    mvn test
            → Tests run: 347, Failures: 0, Errors: 0, Skipped: 1 (OsmPipelineComparisonTest, @EnabledIfSystemProperty gated)
            → BUILD SUCCESS

frontend unit:  npm test -- --run
                → Test Files  8 passed | 1 skipped (9)
                → Tests 56 passed | 3 skipped (59)

frontend e2e:   npx playwright test
                → 7 passed (29.3s)
```

Test deltas vs baseline pre-Phase 23:
- Backend: 308 → 347 (+39) including `OsmConversionUtilsTest` (28), `GraphHopperOsmServiceTest` (7), `OsmControllerTest` (+3), `OsmPipelineComparisonTest` (1 skipped); spike `GraphHopperSpikeTest` (2) removed as planned.
- Frontend unit: 51 → 56 (+5) for new `MapSidebar` tests (GraphHopper button + `resultOrigin` heading).
- Frontend e2e: 6 → 7 (+1) for `osm-bbox-gh.spec.ts`.

## Deviations from PLAN.md

- **Execution mode.** The executor agent was instantiated in a stale worktree based on master (pre-Phase 23 commits), not on the required base `e7cde42`. Rather than force-rebuild the worktree, this plan ran inline on the main working tree on `fix/overpass-fallback`. No production code touched, so inline execution is equivalent to worktree execution in outcome.

## Self-check: PASSED

- `backend/docs/osm-converters.md` exists with Architecture, A/B Fairness Contract, Spike Findings, and Extension Recipe sections.
- `backend/src/test/java/com/trafficsimulator/spike/` — removed; `grep -r "spike" backend/src/` returns zero hits in production code.
- All three test suites green.
- No edits to STATE.md frontmatter by this plan (pre-existing Phase 23 begin-phase update from Wave 0 orchestrator is intentional).
- No edits to ROADMAP.md.
