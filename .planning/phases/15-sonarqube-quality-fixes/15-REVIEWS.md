---
phase: 15
reviewers: [claude]
reviewed_at: 2026-04-03T12:00:00Z
plans_reviewed: [15-01-PLAN.md, 15-02-PLAN.md, 15-03-PLAN.md]
---

# Cross-AI Plan Review — Phase 15

## Claude Review

# Phase 15 Plan Review: SonarQube Code Quality Fixes

## 1. Summary

Solid, well-scoped refactoring phase. The plans correctly decompose 20 SonarQube violations into three independent work streams targeting test assertions, cognitive complexity, and duplicated literals. The approach is conservative — extract-method refactoring with no behavioral changes — which is the right call for a 140-test codebase. The main risk is subtle behavior changes sneaking in during complex method extractions (especially IntersectionManager and LaneChangeEngine), not architectural missteps.

## 2. Strengths

- **All three plans are Wave 1 with no cross-dependencies** — they can execute in parallel or any order, reducing coordination risk
- **"Pure refactoring, no behavior changes"** constraint is explicitly stated and enforced via `mvn test` after each file change — good safety net
- **Test assertion fixes (SQ-01) are meaningful**, not `assertTrue(true)` stubs — `assertDoesNotThrow`, latch assertions, and collection checks actually validate behavior
- **MapValidator decomposition is clean**: dispatcher pattern with `List<String> errors` accumulator is idiomatic Java and naturally drops complexity below 15
- **SnapshotBuilder introduces a `VehicleObstacleCollection` record** — appropriate use of Java 17 records for an intermediate data structure without over-engineering
- **LaneChangeEngine extraction names are domain-aligned** (`isSafeLaneChange`, `computeIncentive`, `findBestGap`) — they'll be readable without comments
- **Granularity is right**: each task targets 1-2 files, each method extraction is named and scoped

## 3. Concerns

- **HIGH — No explicit verification that complexity targets are met.** Plans say "complexity 103->max 15" but don't specify how to verify post-refactor. SonarQube re-scan? Manual count? If you refactor and land at complexity 16, you've done the work but haven't fixed the violation. **Recommendation:** Add a verification step — either run SonarQube locally or use a complexity-counting plugin after each task.

- **HIGH — IntersectionManager has 5 methods to refactor, touching core traffic logic.** Methods like `canEnterIntersection()`, `hasVehicleFromRight()`, and `checkDeadlocks()` contain subtle priority/deadlock resolution logic. Extract-method refactoring here has the highest risk of accidentally changing evaluation order, short-circuit behavior, or early-return semantics. The 140 existing tests may not cover every branch. **Recommendation:** Before refactoring IntersectionManager, verify test coverage of those 5 methods specifically (line + branch coverage). Add targeted tests if gaps exist.

- **MEDIUM — SnapshotBuilder: `VehicleObstacleCollection` record adds a new class.** The constraint says "no new dependencies" but doesn't explicitly address new classes. A record is fine architecturally, but confirm it doesn't trigger other SonarQube rules (naming, placement). Also clarify: is this an inner record or a separate file?

- **MEDIUM — LaneChangeEngine `evaluateMOBIL()` extraction.** MOBIL is a well-known lane-change model with specific mathematical formulas. Extracting `isSafeLaneChange()` and `checkGapSafety()` from it risks splitting a cohesive algorithm across methods in a way that obscures the model's logic. **Recommendation:** Keep extracted methods co-located (private methods in same class, grouped together).

- **LOW — Plan 15-02 bundles 4 files (SnapshotBuilder, PhysicsEngine, TickEmitter, SimulationController).** SnapshotBuilder alone (complexity 77) is substantial. If something goes wrong mid-task, the blast radius is larger than Plans 15-01 or 15-03. Consider committing after each file, not after each task.

- **LOW — Constants extraction (SQ-07) is trivial but lives in Plan 15-01 alongside the most complex refactoring (MapValidator).** If MapValidator refactoring stalls, the constants fix — a 2-minute change — is blocked. Minor, since they're in the same file.

## 4. Suggestions

- **Add a complexity verification gate after each task.** Even a simple heuristic gives a rough check. Better: use `mvn sonar:sonar` or an IDE inspection.

- **Commit atomically per file, not per task.** Plan 15-02 touches 4 files — if PhysicsEngine refactoring introduces a test failure, you want to isolate it without reverting SnapshotBuilder work too.

- **For IntersectionManager, write down the current behavior contract before refactoring.** A 3-line comment per method ("returns true when X, false when Y, throws never") acts as a refactoring safety spec.

- **Consider running tests with coverage reporting (`jacoco:report`) once before the phase starts** to identify any under-tested methods in the refactoring targets.

- **SnapshotBuilder's `VehicleObstacleCollection`** — make it a `private static` inner record to avoid file proliferation.

- **Plan ordering suggestion:** Execute 15-01 first (lowest risk — tests + constants), then 15-02, then 15-03 (highest risk — intersection/lane-change logic).

## 5. Risk Assessment

**Overall: LOW-MEDIUM**

**Justification:** The phase is pure refactoring with a strong safety net (140 existing tests, `mvn test` after each change). The plans are well-decomposed, correctly scoped, and use standard extract-method patterns. The elevated risk comes from two sources: (1) IntersectionManager and LaneChangeEngine contain the most complex domain logic in the simulator, where subtle evaluation-order changes could alter traffic behavior without failing tests; (2) there's no explicit complexity verification step to confirm violations are actually resolved. Both risks are manageable with the mitigations suggested above.

---

## Consensus Summary

Single reviewer (Claude CLI). Key takeaways:

### Top Concerns
1. **No complexity verification gate** — need to confirm refactored methods are actually <=15
2. **IntersectionManager refactoring risk** — core traffic logic, verify test coverage first
3. **Atomic commits per file** — don't bundle 4 files in one commit

### Agreed Strengths
- Clean decomposition into 3 independent plans
- Meaningful test assertions, not stubs
- Domain-aligned method names
