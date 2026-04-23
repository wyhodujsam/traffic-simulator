---
phase: 24-osm2streets-integration
plan: 05
subsystem: backend-controller
tags: [controller, endpoint, api, webmvc, comparison, exception-handler]

# Dependency graph
requires:
  - "24-04: Osm2StreetsService.fetchAndConvert end-to-end wiring (Overpass fetch -> executeCli -> mapper -> MapConfig)"
  - "24-03: Osm2StreetsCliException + Osm2StreetsCliTimeoutException exception taxonomy"
  - "23-02: OsmConverter interface with converterName() + isAvailable()"
provides:
  - "POST /api/osm/fetch-roads-o2s endpoint — third OSM converter exposed to external callers"
  - "@ExceptionHandler(Osm2StreetsCliTimeoutException) -> 504 Gateway Timeout"
  - "@ExceptionHandler(Osm2StreetsCliException) -> 503 Service Unavailable"
  - "@ExceptionHandler(HttpMessageNotReadableException) -> 400 (Rule 1 auto-fix — broad Exception handler was masking malformed-JSON requests as 503)"
  - "OsmPipelineComparisonTest A/B/C harness via List<OsmConverter>"
affects:
  - "24-06 (frontend button) — /api/osm/fetch-roads-o2s is now callable from the UI"
  - "24-07 (phase docs) — osm-converters.md can promote osm2streets from `service-ready` to `endpoint-live`"

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Per-exception explicit @ExceptionHandler over inheritance tricks — Osm2StreetsCliException does NOT extend RestClientException, both get distinct 503 handlers with different log messages so operators can distinguish `Overpass fault` from `osm2streets CLI fault` in log aggregation"
    - "HttpMessageNotReadableException explicit handler — defends against broad @ExceptionHandler(Exception.class) shadowing Spring MVC's default 400 behaviour; critical for honest HTTP status codes (client fault -> 4xx, backend fault -> 5xx)"
    - "List<OsmConverter> autowired collection — Spring injects every @Service bean that implements the interface, so adding a 4th converter in a future phase requires zero changes to the comparison harness"
    - "Byte-identity regression tests via response body string-search (`doesNotContain(\"\\\"lanes\\\"\")`) — Phase 18/23 MUST not serialise the new optional field introduced by Phase 24-02"

key-files:
  created: []
  modified:
    - backend/src/main/java/com/trafficsimulator/controller/OsmController.java
    - backend/src/test/java/com/trafficsimulator/controller/OsmControllerTest.java
    - backend/src/test/java/com/trafficsimulator/service/OsmPipelineComparisonTest.java

key-decisions:
  - "Kept Osm2StreetsCliException as a plain RuntimeException rather than extending RestClientException; added a dedicated 503 handler instead. Both code paths now return the same HTTP status but with distinct log lines — operators triaging a production incident can tell at a glance whether the problem is the Overpass mirror or the osm2streets subprocess."
  - "Added HttpMessageNotReadableException -> 400 handler (deviation Rule 1 — pre-existing bug). Before this plan, malformed JSON payloads on ANY of the three endpoints returned 503 because the class-level @ExceptionHandler(Exception.class) caught Jackson's parse failure before Spring MVC could return its native 400. The fix benefits Phase 18 and Phase 23 as well as Phase 24 — and is verifiable as a pure behaviour change with zero risk of breaking existing tests (none of them exercised malformed JSON before)."
  - "OsmPipelineComparisonTest now carries TWO tests: one OFFLINE contract test (runs on CI, asserts exactly three converters named Overpass + GraphHopper + osm2streets) and one ONLINE comparison test (gated by -Dosm.online=on). Splitting the gate lets CI catch converter registration regressions without hitting live APIs, while the online smoke test stays available for developer triage."

patterns-established:
  - "When adding a new @ExceptionHandler to an existing controller, first inventory the existing handlers to check whether a broad catch-all is silently masking the new fault signature. In this plan, @ExceptionHandler(Exception.class) was masking Spring MVC's default 400 behaviour — the new malformed-JSON test surfaced it."
  - "Byte-identity regression tests defend against accidental serialisation changes when a new optional field is added to a shared DTO. The body-string-search pattern (`assertThat(body).doesNotContain(\"\\\"lanes\\\"\")`) is cheap, explicit, and survives Jackson config changes."

requirements-completed: []

# Metrics
duration: ~10 min
completed: 2026-04-23
tasks_completed: 2
files_created: 0
files_modified: 3
test_count_delta: "+8 (373 -> 381)"
---

# Phase 24 Plan 05: Wire POST /api/osm/fetch-roads-o2s + A/B/C Comparison Harness Summary

Lights up the third OSM converter for external callers. `POST /api/osm/fetch-roads-o2s` is wired to `Osm2StreetsService.fetchAndConvert`, 5 WebMvc tests cover the 200/400/422/503/504 exception taxonomy, 2 byte-identity regression tests prove Phase 18 and Phase 23 still do NOT serialise the new optional `lanes` field, and `OsmPipelineComparisonTest` now iterates `List<OsmConverter>` for three-way A/B/C comparison. Full backend suite 381/0/0 (skipped 1 — the online comparison test).

## Performance

- **Duration:** ~10 min wall-clock
- **Started:** 2026-04-23T14:23:25Z
- **Completed:** 2026-04-23T14:33:53Z
- **Tasks:** 2 of 2
- **Files created:** 0
- **Files modified:** 3 (OsmController, OsmControllerTest, OsmPipelineComparisonTest)

## Accomplishments

- **`POST /api/osm/fetch-roads-o2s`** wired to `Osm2StreetsService.fetchAndConvert` via constructor-injected `@RequiredArgsConstructor` field — mirrors the Phase 18 / Phase 23 endpoint shape exactly.
- **Three new `@ExceptionHandler` methods:**
  - `Osm2StreetsCliTimeoutException` → 504 Gateway Timeout with a bbox-size hint in the error body.
  - `Osm2StreetsCliException` → 503 Service Unavailable with a distinct log message (separate from the Phase 18/23 `RestClientException` 503 path so log triage stays clean).
  - `HttpMessageNotReadableException` → 400 Bad Request (deviation Rule 1 — pre-existing latent bug; see below).
- **7 new WebMvc tests** in `OsmControllerTest`:
  - `fetchRoadsO2s_success_200` — canned MapConfig through mocked service; 200 + `id` field present.
  - `fetchRoadsO2s_invalid_400` — malformed JSON body; 400 returned; `verifyNoInteractions(osm2StreetsService)` proves the request never reached the service layer.
  - `fetchRoadsO2s_empty_422` — service throws `IllegalStateException("No roads…")`; 422 + error message containing "No roads".
  - `fetchRoadsO2s_cliError_503` — service throws `Osm2StreetsCliException("exit 2: boom")`; 503 + generic "unavailable" message (internal error text NOT leaked to clients).
  - `fetchRoadsO2s_timeout_504` — service throws `Osm2StreetsCliTimeoutException("timed out after 30s")`; 504 + "timed out" message.
  - `fetchRoads_phase18_byteIdentity_noLanesKey` — W5 regression: Phase 18 response body does NOT contain `"lanes"`.
  - `fetchRoadsGh_phase23_byteIdentity_noLanesKey` — W6 regression: Phase 23 response body does NOT contain `"lanes"`.
- **`OsmPipelineComparisonTest`** rewritten to autowire `List<OsmConverter>` — Spring injects all three `@Service` implementations (Overpass + GraphHopper + osm2streets). Split into:
  - `allThreeConvertersAreRegistered` — OFFLINE contract test, runs on every CI pass; asserts exactly 3 converters with the expected names.
  - `compareAllConverters_sameBbox_logDiff` — ONLINE comparison, gated by `@EnabledIfSystemProperty(named="osm.online", matches="on|true")`; iterates all converters, validates each output, logs side-by-side counts, never asserts equality (divergence is the point).

## Endpoint Contract

### Request

```
POST /api/osm/fetch-roads-o2s
Content-Type: application/json

{
  "south": 52.2295,
  "west":  21.0122,
  "north": 52.2305,
  "east":  21.0132
}
```

### Response Shapes

| Status | Body                                                                                   | Cause                                                              |
| ------ | -------------------------------------------------------------------------------------- | ------------------------------------------------------------------ |
| 200    | `MapConfig` (id, name, roads[], nodes[], intersections[], spawnPoints[], despawnPoints[]) | osm2streets pipeline succeeded                                     |
| 400    | `{"error":"Malformed request body"}`                                                   | Jackson could not deserialise the body to `BboxRequest`            |
| 422    | `{"error":"<service message>"}`                                                        | `IllegalStateException` from service (empty area / validator fail) |
| 503    | `{"error":"osm2streets unavailable. Please try again later."}`                         | `Osm2StreetsCliException` (subprocess exit ≠ 0, IO, invalid JSON)  |
| 503    | `{"error":"Overpass API unavailable. Please try again later."}`                        | `RestClientException` from Overpass fetch (pre-Phase 24 handler)   |
| 504    | `{"error":"osm2streets timed out. Try a smaller bbox."}`                               | `Osm2StreetsCliTimeoutException` from subprocess timeout           |

### Exception → HTTP Mapping

| Exception                             | Handler                          | Status | Note                             |
| ------------------------------------- | -------------------------------- | ------ | -------------------------------- |
| `HttpMessageNotReadableException`     | `handleMalformedPayload`         | 400    | **New in Plan 24-05**            |
| `IllegalStateException`               | `handleNoData`                   | 422    | Existing (shared w/ Phase 18/23) |
| `RestClientException`                 | `handleOverpassError`            | 503    | Existing                         |
| `Osm2StreetsService.Osm2StreetsCliException` | `handleO2sCli`            | 503    | **New in Plan 24-05**            |
| `Osm2StreetsService.Osm2StreetsCliTimeoutException` | `handleO2sTimeout` | 504    | **New in Plan 24-05**            |
| `Exception` (fallthrough)             | `handleUnexpected`               | 503    | Existing                         |

## Phase 18 / Phase 23 Byte-Identity Evidence

Both regression tests W5 and W6 pass. They mock `OsmPipelineService.fetchAndConvert(...)` / `GraphHopperOsmService.fetchAndConvert(...)` to return a `MapConfig` with empty roads+nodes and assert the serialised response body **does not contain the literal string `"lanes"`**. Evidence:

- W5 `fetchRoads_phase18_byteIdentity_noLanesKey` — **PASS** (Phase 18 endpoint response verified)
- W6 `fetchRoadsGh_phase23_byteIdentity_noLanesKey` — **PASS** (Phase 23 endpoint response verified)

This confirms the optional `lanes` field added to `MapConfig.RoadConfig` in Plan 24-02 is NOT serialised when `null` (Jackson's default behaviour for `null` properties + our `MapConfig` not overriding that default). Phase 18 and Phase 23 converters never populate `lanes`, so their responses stay byte-identical to pre-Phase-24 clients.

Constraint greps (from plan `<success_criteria>`):

```
grep -c 'PostMapping("/fetch-roads")'     OsmController.java  =  1  ✅
grep -c 'PostMapping("/fetch-roads-gh")'  OsmController.java  =  1  ✅
grep -c 'PostMapping("/fetch-roads-o2s")' OsmController.java  =  1  ✅
```

Phase 18 endpoint diff: **0 lines changed** (only additions above/below).
Phase 23 endpoint diff: **0 lines changed**.

## A/B/C Comparison Harness

`OsmPipelineComparisonTest` now iterates `List<OsmConverter>`. `grep -c "List<OsmConverter>"` = **2** (javadoc + field declaration). `grep -c "osm2streets"` = **5** (javadoc + assertion).

Offline test output (captured during the full suite run):

```
Tests run: 2, Failures: 0, Errors: 0, Skipped: 1
— allThreeConvertersAreRegistered: PASS (3 converters wired)
— compareAllConverters_sameBbox_logDiff: SKIPPED (osm.online system property not set)
```

Running the online comparison manually:

```bash
mvn test -pl backend -Dtest=OsmPipelineComparisonTest -Dosm.online=on
```

On a live run it will iterate all three converters over the Warsaw-Centrum bbox and log lines like:

```
INFO c.t.service.OsmPipelineComparisonTest : Overpass:    42 roads, 11 intersections
INFO c.t.service.OsmPipelineComparisonTest : GraphHopper: 38 roads,  9 intersections
INFO c.t.service.OsmPipelineComparisonTest : osm2streets: 40 roads, 10 intersections
```

(Per-converter counts will differ — that's the point. The test never asserts equality.)

## Test Count Change

| Suite                          | Before Plan 24-05 | After Plan 24-05 | Delta                  |
| ------------------------------ | ----------------- | ---------------- | ---------------------- |
| `OsmControllerTest`            | 6                 | 13               | +7 (W0-W6)             |
| `OsmPipelineComparisonTest`    | 1 (gated)         | 2 (1 offline + 1 gated) | +1 offline      |
| **Backend suite total**        | **373 + 1 skip**  | **381 + 1 skip** | **+8**                 |

Plan prediction was `+7 → 380`; actual `+8 → 381` due to the split offline/online tests in Task 2. The extra offline test is pure net benefit (contract regression gate, no runtime cost).

## Task Commits

| #   | Task                                                                    | Commit    | Type    |
| --- | ----------------------------------------------------------------------- | --------- | ------- |
| 1a  | WebMvc tests for /api/osm/fetch-roads-o2s (RED — 5 failing, 2 regression) | `108d842` | test    |
| 1b  | Wire endpoint + 504/503/400 handlers (GREEN)                            | `8de13c3` | feat    |
| 2   | Extend OsmPipelineComparisonTest to A/B/C via List<OsmConverter>        | `576785d` | test    |

Plan metadata commit (this SUMMARY.md) will be the final commit.

## Decisions Made

1. **Two 503 paths kept distinct.** `Osm2StreetsCliException` gets its own `@ExceptionHandler` rather than extending `RestClientException`. Same HTTP status (503 is correct for both — backend fault), but different log lines. An operator tailing logs during a production incident can immediately tell whether Overpass is flaky or the osm2streets subprocess is the problem.
2. **`HttpMessageNotReadableException` handler covers ALL endpoints.** Class-level `@ExceptionHandler` in Spring MVC applies to every method in the controller. The fix I added for `/api/osm/fetch-roads-o2s`'s 400 contract also retroactively fixes `/api/osm/fetch-roads` and `/api/osm/fetch-roads-gh` — a free win for Phase 18 / Phase 23 clients that was never tested before.
3. **Byte-identity regression tests use response body string-search, not structural assertions.** `assertThat(body).doesNotContain("\"lanes\"")` is one line, verifies the whole serialised shape, and will catch even accidental Jackson config drift (e.g. someone switching `Include.NON_NULL` to `Include.ALWAYS`). Structural assertions on parsed trees would miss that class of regression.
4. **Online comparison test gate moved from class-level to method-level.** Previously `@EnabledIfSystemProperty` was on the class — both tests skipped without `-Dosm.online=on`. Moving the annotation to the method lets the contract test run on every CI pass (catching converter-registration regressions) while the online comparison stays gated.
5. **Offline contract test uses `containsExactlyInAnyOrder`.** Order of `List<OsmConverter>` injection is not guaranteed by Spring; asserting in any order future-proofs the test.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 — Bug] Malformed JSON returned 503 instead of 400 due to broad catch-all handler**

- **Found during:** Task 1 GREEN, after the initial endpoint wiring. The `fetchRoadsO2s_invalid_400` test returned 503 instead of 400.
- **Root cause:** The pre-existing `@ExceptionHandler(Exception.class) handleUnexpected(...)` caught Spring's `HttpMessageNotReadableException` (thrown by Jackson on malformed JSON) before Spring MVC could emit its default 400 response. This was a latent bug on the Phase 18 and Phase 23 endpoints too — no prior test exercised malformed JSON, so it went unnoticed.
- **Fix:** Added an explicit `@ExceptionHandler(HttpMessageNotReadableException.class)` returning 400 with a generic "Malformed request body" message. Handler sits above `handleNoData` and `handleUnexpected` so it wins the Spring handler lookup.
- **Files modified:** `backend/src/main/java/com/trafficsimulator/controller/OsmController.java` (new import + new handler method).
- **Verification:** `OsmControllerTest.fetchRoadsO2s_invalid_400` → PASS. All existing Phase 18 + Phase 23 tests → PASS (no regression because they never sent malformed JSON). Full suite 381/0/0.
- **Committed in:** `8de13c3` (Task 1 GREEN commit).

**2. [Rule 2 — Critical functionality] Plan 24-05 requested an offline contract test for Task 2; added one that was slightly broader than spec**

- **Found during:** Task 2 refactor.
- **Issue:** The plan's suggested offline test (`allThreeConvertersAreRegistered`) uses `assertThat(converters).hasSize(3)` + `containsExactlyInAnyOrder("Overpass", "GraphHopper", "osm2streets")`. I kept both assertions (good defensive depth — `hasSize(3)` catches a *missing* converter with a clearer message, while `containsExactlyInAnyOrder` catches a *renamed* converter).
- **Fix:** Neither assertion was removed; both kept, each with its own `.as(...)` description for readable failure messages.
- **Files modified:** `backend/src/test/java/com/trafficsimulator/service/OsmPipelineComparisonTest.java`.
- **Verification:** Offline test passes; test count +1 beyond plan prediction (`+8` actual vs `+7` predicted); net benefit.
- **Committed in:** `576785d` (Task 2 commit).

---

**Total deviations:** 2 (1 Rule 1 bug fix, 1 Rule 2 minor expansion). No Rule 4 architectural questions; no scope creep; both fixes were mechanical improvements strictly within the task boundary.

## Issues Encountered

- **None beyond the deviation above.** The RED phase gave the expected 5 × 404 failures; GREEN fixed 4 of 5; the 5th (400) surfaced the latent bug which Rule 1 auto-fix cleared. Full suite green on first post-fix run.

## User Setup Required

None — pure backend change, all tests run offline. The online A/B/C comparison in `OsmPipelineComparisonTest` requires `-Dosm.online=on` and network access to Overpass + a local osm2streets-cli binary (already staged under `backend/bin/` from Plan 24-01).

## Known Stubs

None. Every exception path has both a handler and a test; `Osm2StreetsService` is fully wired to a production endpoint; the comparison harness iterates the full converter list with no placeholder entries.

## Deferred Issues

- Pre-existing `backend/target/` tracked in git (noted in Plan 24-03 `deferred-items.md`) — out of scope here.
- Roundabout-detection gap in `StreetNetworkMapper` (Plan 24-04) still stands; Plan 24-05 does not regress it.
- No new deferred items introduced.

## Threat Flags

None introduced. `HttpMessageNotReadableException` handler REMOVES an information-leak surface (pre-Plan-24-05, malformed JSON exceptions flowed through the catch-all `handleUnexpected` which logged at ERROR level — now they log at WARN with a bounded message). The new 400 response body is a fixed string `"Malformed request body"` (no internal details leaked to clients). Exception-to-HTTP mapping is the primary security-relevant change in this plan and each new handler was designed with information-disclosure minimisation in mind (no stack traces, no internal error text, just the HTTP status + a user-safe hint).

## Next Phase Readiness

- **Plan 24-06 (frontend button)** unblocked — `/api/osm/fetch-roads-o2s` returns real MapConfigs; the UI can add a "Fetch (osm2streets)" button alongside the Phase 18 and Phase 23 buttons.
- **Plan 24-07 (phase docs)** unblocked — `backend/docs/osm-converters.md` can update its status column for osm2streets to "endpoint-live" and list the new 504 status code in the exception taxonomy table.

## Self-Check

### Modified files
- `backend/src/main/java/com/trafficsimulator/controller/OsmController.java` — **FOUND** (contains `fetchRoadsO2s` + `handleO2sTimeout` + `handleO2sCli` + `handleMalformedPayload`)
- `backend/src/test/java/com/trafficsimulator/controller/OsmControllerTest.java` — **FOUND** (13 tests — was 6, +7 new)
- `backend/src/test/java/com/trafficsimulator/service/OsmPipelineComparisonTest.java` — **FOUND** (2 tests, 1 gated; autowires `List<OsmConverter>`)

### Commit hashes present in git log
- `108d842` — **FOUND** (Task 1 RED — failing WebMvc tests)
- `8de13c3` — **FOUND** (Task 1 GREEN — endpoint + handlers, includes Rule 1 malformed-JSON fix)
- `576785d` — **FOUND** (Task 2 — List<OsmConverter> refactor)

### Constraint checks
- `grep -c 'PostMapping("/fetch-roads")'    OsmController.java` — **1** (Phase 18 untouched)
- `grep -c 'PostMapping("/fetch-roads-gh")' OsmController.java` — **1** (Phase 23 untouched)
- `grep -c 'PostMapping("/fetch-roads-o2s")' OsmController.java` — **1** (new)
- `grep -q 'fetchRoadsO2s' OsmController.java` — **FOUND**
- `grep -q 'Osm2StreetsCliTimeoutException.class' OsmController.java` — **FOUND**
- `grep -c 'List<OsmConverter>' OsmPipelineComparisonTest.java` — **2** (javadoc + field)
- `grep -c 'osm2streets' OsmPipelineComparisonTest.java` — **5** (javadoc + assertion)
- `mvn test -pl backend -Dtest=OsmControllerTest` — **13 passed, 0 failures, 0 errors**
- `mvn test -pl backend -Dtest=OsmPipelineComparisonTest` — **2 run, 0 failures, 1 skipped** (online test gated)
- `mvn test -pl backend -Dtest='OsmControllerTest,OsmPipelineServiceTest,GraphHopperOsmServiceTest'` — **36 passed, 0 failures** (regression gates: Phase 18 + Phase 23 tests unchanged, Phase 24 new tests green)
- `mvn test -pl backend -Dtest=ArchitectureTest` — **7 passed, 0 failures** (no new ArchUnit violations)
- `mvn test -pl backend` — **381 passed, 0 failures, 0 errors, 1 skipped** (expected ≥380; exceeded by 1 due to split offline/online comparison tests)
- No edits to `STATE.md`, `ROADMAP.md`, `tools/`, `backend/bin/`, `frontend/` — confirmed (only the 3 backend files above)

## Self-Check: PASSED

---
*Phase: 24-osm2streets-integration*
*Plan: 05 (Wave 4, the API-surface plan)*
*Completed: 2026-04-23*
