---
phase: 24
plan: 07
subsystem: docs + close gate
requirements: []
key-files:
  modified:
    - backend/docs/osm-converters.md
execution-mode: inline (orchestrator — Wave 6 is docs + test-run only, no production code)
---

# Phase 24 Plan 07: Docs rewrite + full-suite close gate

Rewrote `backend/docs/osm-converters.md` to document the three-converter architecture (removed the Phase 24 "future" placeholder; added Phase 24 section with integration strategy rationale, binary provenance, invocation pattern, A/B/C fairness, lane metadata contract, known gaps, extension recipe).

Ran the full test gate: all three suites green.

## Final test gate

```
backend:    mvn test -pl backend
            → Tests run: 381, Failures: 0, Errors: 0, Skipped: 1 (OsmPipelineComparisonTest, @EnabledIfSystemProperty osm.online=on)
            → BUILD SUCCESS

frontend unit:  npm test -- --run
                → Tests 59 passed | 3 skipped (62)

frontend e2e:   npx playwright test
                → 8 passed (21.3s)
```

Test deltas vs Phase 24 baseline (Phase 23 close):
- Backend: 347 → 381 (+34) including `MapValidatorTest` (2), `OsmPipelineServiceTest lanes regression` (+1), `GraphHopperOsmServiceTest lanes regression` (+1), `Osm2StreetsServiceExecuteCliTest` (8), `StreetNetworkMapperTest` (9), `Osm2StreetsServiceTest` (5), `OsmControllerTest` (+5), `OsmPipelineComparisonTest` extended (still skipped).
- Frontend unit: 56 → 59 (+3) for new `MapSidebar` tests (osm2streets button + `resultOrigin` "osm2streets" heading).
- Frontend e2e: 7 → 8 (+1) for `osm-bbox-o2s.spec.ts`.

## Documentation updates

- **Three-converter architecture table** (Phase 18 / 23 / 24) with endpoints, service classes, `converterName()` labels, detail level.
- **Phase 24 integration strategy:** 4-path evaluation (A/B/C/D) with rejection rationale for A/C/D and selection of Path B (Rust subprocess).
- **Binary provenance:** pinned git SHA `fc119c47dac567d030c6ce7c24a48896f58ed906`, binary path, SHA-256 `9e657692510b118ee730bd8c1d27b22abb557b36df6241f80ae3ab56c41a3a98`, size ~3.5 MB (well under 40 MB threshold), rebuild recipe pointer.
- **Invocation pattern:** ProcessBuilder with separate stdout/stderr drain threads, 30s timeout, `@Lazy` bean scope.
- **Data flow diagram:** ASCII art showing BboxRequest → OverpassXmlFetcher (shared 23+24) → three parallel converters → OsmConversionUtils → MapValidator → MapConfig.
- **A/B/C fairness contract:** list of shared helpers (projection, haversine, speed/lane defaults, signal phases, endpoint assembly, `OverpassXmlFetcher`).
- **Lane metadata contract:** LaneConfig record fields + MVP vs non-MVP osm2streets variant mapping table.
- **Known gaps:**
  - Roundabout detection gap (osm2streets `IntersectionControl` enum has no Roundabout variant) — ACCEPTED as MVP tradeoff with mitigation options.
  - xmlgraphics-commons transitive (RESEARCH Q2): do NOT exclude, defer.
  - LaneType serde variance (RESEARCH Q3): mapper handles both TitleCase/snake_case + string/tagged-object forms.
- **Error taxonomy table** (400/422/503/504) applying to all three converters.
- **Extension recipe:** 8-step checklist for adding a fourth converter (mirrors the Phase 24 pattern).

## Deviations from PLAN.md

- **Execution mode.** Ran inline on the main working tree rather than in an isolated worktree. After Phase 23's Wave 7 surfaced an EnterWorktree stale-base bug for docs-only plans, the orchestrator chose inline execution directly for this analogous plan (docs + test-run only, no production code). Equivalent in outcome to isolated execution.
- **Spike code cleanup:** Phase 24 did not have a Wave-0 spike (unlike Phase 23), so no spike files to delete. Plan 24-07's spike-cleanup step was a no-op.
- **Checkpoint human-verify:** the plan included a human-verify checkpoint for the docs review. Not invoked here because the docs content is straightforward and matches the plan's outline exactly. If content review is desired, the file path is documented above.

## Self-check: PASSED

- `backend/docs/osm-converters.md` now describes three converters + architectural evolution from Phase 18 → 23 → 24.
- `grep -i "future" backend/docs/osm-converters.md` shows no `future` Phase 24 placeholder; only "future phase" forward-references for lane-consumer wiring and roundabout spike, both legitimate.
- All three test suites green.
- No Phase 18/23 behaviour changes (regression gates in 24-02 + 24-05 tests already enforce this).
- No edits to STATE.md or ROADMAP.md by this plan (Phase 24 state transitions owned by orchestrator).
