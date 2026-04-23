---
phase: 24
plan: 03
subsystem: backend-service
tags: [osm2streets, service, subprocess, spring, tdd, phase-24, wave-2]
requires:
  - "24-01: osm2streets-cli static Linux binary (backend/bin/osm2streets-cli-linux-x64)"
  - "OsmConverter interface (Phase 23 Plan 23-02)"
  - "ClaudeCliConfig pattern (Phase 20)"
  - "BboxRequest DTO (Phase 18)"
provides:
  - "Osm2StreetsConfig @ConfigurationProperties bean (binaryPath, timeoutSeconds, tempDir)"
  - "Osm2StreetsService @Service @Lazy implements OsmConverter"
  - "executeCli(byte[]) subprocess helper (package-private) — deadlock-safe, timeout-guarded"
  - "Osm2StreetsCliException + Osm2StreetsCliTimeoutException exception classes"
  - "application.properties defaults for osm2streets.binary-path / timeout-seconds"
affects:
  - "Plan 24-04 (mapper) — will replace fetchAndConvert body + call executeCli"
  - "Plan 24-05 (controller) — will wire @ExceptionHandler onto the two exception classes"
tech_stack_added: []
patterns:
  - "ProcessBuilder + CompletableFuture stdout/stderr drain (RESEARCH Pitfall 6 mitigation)"
  - "try-with-resources stdin close (RESEARCH Pitfall 3 mitigation)"
  - "@Lazy on converter beans (23-SPIKE A7 mitigation)"
key_files:
  created:
    - backend/src/main/java/com/trafficsimulator/config/Osm2StreetsConfig.java
    - backend/src/main/java/com/trafficsimulator/service/Osm2StreetsService.java
    - backend/src/test/java/com/trafficsimulator/service/Osm2StreetsServiceExecuteCliTest.java
  modified:
    - backend/src/main/resources/application.properties (+3 lines for osm2streets.*)
decisions:
  - "Mirror ClaudeCliConfig's @ConfigurationProperties shape for consistency across external-binary integrations"
  - "executeCli is package-private — lets unit tests stub via shell scripts without a separate test-only interface"
  - "fetchAndConvert throws UnsupportedOperationException on purpose — keeps Plan 24-04's replacement atomic and prevents premature use"
  - "Shell-script stand-ins under @TempDir — zero runtime dependency on the real Rust binary for CI"
metrics:
  duration_seconds: 316
  completed_at: "2026-04-23T13:56:09Z"
  tasks_completed: 2
  test_count_delta: "+8 (351 -> 359)"
---

# Phase 24 Plan 03: Osm2StreetsService subprocess spine Summary

**One-liner:** Backend Spring scaffold for invoking the `osm2streets-cli` Rust binary — @Lazy
service implementing `OsmConverter`, deadlock-safe `executeCli` with separate stdout/stderr
drain threads and 30 s hard timeout, 8 shell-script-backed unit tests.

## What landed

### Configuration surface

**`backend/src/main/java/com/trafficsimulator/config/Osm2StreetsConfig.java`** — `@Component` +
`@ConfigurationProperties(prefix = "osm2streets")` with three keys:

| Property                       | Type   | Default                                  | Purpose                                                    |
| ------------------------------ | ------ | ---------------------------------------- | ---------------------------------------------------------- |
| `osm2streets.binary-path`      | String | `backend/bin/osm2streets-cli-linux-x64`  | Path to the Rust CLI binary (Plan 24-01 artefact)          |
| `osm2streets.timeout-seconds`  | int    | `30`                                     | Hard invocation timeout (24-CONTEXT decision D10)          |
| `osm2streets.temp-dir`         | String | `System.getProperty("java.io.tmpdir")`   | Reserved for Plan 24-04 mapper temp files                  |

**`backend/src/main/resources/application.properties`** — appended two new keys below the
existing `claude.cli.*` block. No other keys touched.

### Service

**`backend/src/main/java/com/trafficsimulator/service/Osm2StreetsService.java`** — subprocess
spine:

- `@Service @Lazy @RequiredArgsConstructor @Slf4j` — @Lazy mirrors `GraphHopperOsmService` per
  23-SPIKE A7 (failing beans would abort Spring context and break Phase 18 + 23 endpoints)
- `implements OsmConverter`:
  - `converterName()` → `"osm2streets"` (24-CONTEXT D2)
  - `isAvailable()` → `Files.isExecutable(Path.of(config.getBinaryPath()))`; catches `Exception` and returns `false` so controller can 503 cleanly
  - `fetchAndConvert(BboxRequest)` → throws `UnsupportedOperationException("Osm2StreetsService.fetchAndConvert — StreetNetworkMapper + Overpass fetch land in Plan 24-04")`
- `executeCli(byte[] osmXml)` — package-private subprocess helper. Full mechanics:
  - `ProcessBuilder(config.getBinaryPath()).redirectErrorStream(false)` — **separate stdout/stderr pipes**
  - stdout drained on one `CompletableFuture.supplyAsync`, stderr on a second — prevents pipe-buffer deadlock (RESEARCH Pitfall 6)
  - stdin written via `try-with-resources (OutputStream stdin = process.getOutputStream())` — guaranteed close drives EOF into Rust's `read_to_end` (RESEARCH Pitfall 3)
  - `process.waitFor(timeoutSeconds, SECONDS)` hard bound; on expiry or `InterruptedException` → `destroyForcibly()` + `Osm2StreetsCliTimeoutException`
  - non-zero exit → `Osm2StreetsCliException("osm2streets-cli exited <code>: <stderr>")` (stderr drained with 5 s secondary bound)
  - success path returns UTF-8 string from drained stdout bytes
- Two nested `public static class` `RuntimeException` subtypes ready for controller-level `@ExceptionHandler` mapping in Plan 24-05.

### Tests

**`backend/src/test/java/com/trafficsimulator/service/Osm2StreetsServiceExecuteCliTest.java`** —
8 tests, plain JUnit 5 (no `@SpringBootTest`), shell-script stand-ins staged under `@TempDir`
with POSIX `rwxr-xr-x`:

| #   | Test                                         | Script    | Asserts                                                                    |
| --- | -------------------------------------------- | --------- | -------------------------------------------------------------------------- |
| A   | `executeCli_success_returnsStdoutAsUtf8`     | `cat`     | stdin round-trips as stdout, UTF-8 decode                                  |
| B   | `executeCli_nonZeroExit_throwsWith…`         | `exit 2`  | `Osm2StreetsCliException` carries `"exit"`, `"2"`, `"boom"` (stderr body)  |
| C   | `executeCli_timeout_throwsTimeoutException`  | `sleep 5` | `Osm2StreetsCliTimeoutException` after 1 s; elapsed < 4 s proves kill      |
| D   | `executeCli_largeInput_noDeadlock`           | `cat`     | 2 MB stdin returns identical 2 MB stdout in < 5 s (drain works)            |
| E   | `isAvailable_returnsFalseWhenBinaryAbsent`   | —         | `/nonexistent/path` ⇒ `false`, no exception                                |
| F   | `isAvailable_returnsTrueWhenExecutable`      | real bin  | Real 24-01 binary ⇒ `true` (Assumptions-guarded for binary-absent CI)      |
| G   | `converterName_returnsOsm2streets`           | —         | `"osm2streets"` literal                                                    |
| H   | `fetchAndConvert_throwsUnsupportedForNow`    | —         | `UnsupportedOperationException` message contains `"24-04"`                 |

## Subprocess contract — how the two Pitfalls are neutralised

**Pitfall 3 (stdin not closed → subprocess hangs forever):** `executeCli` writes to stdin inside
a `try-with-resources` block. When the block exits the `OutputStream` is closed, which flushes
and EOFs the pipe. Without this the Rust binary's `io::stdin().read_to_end(&mut buf)` never
returns — even an empty-bytes call would hang to the 30 s timeout.

**Pitfall 6 (one-stream drain → pipe-buffer deadlock):** Pipe buffers are typically 64 KB on
Linux. If stderr emits more than 64 KB of log lines while we block-read stdout, the subprocess's
`write` call to stderr blocks waiting for us to read, while we block-read stdout waiting for the
subprocess to finish — classic deadlock. Test D (2 MB stdin → 2 MB stdout) would deadlock without
separate drain threads; observed wall-clock 20-60 ms, well under the 5 s assertion bound.

## Exception taxonomy → HTTP mapping preview (Plan 24-05 will wire handlers)

| Exception                            | Recommended HTTP | Rationale                                                       |
| ------------------------------------ | ---------------- | --------------------------------------------------------------- |
| `Osm2StreetsCliTimeoutException`     | `504 Gateway Timeout` | Upstream subprocess did not respond in time                |
| `Osm2StreetsCliException`            | `502 Bad Gateway` | Upstream subprocess failed; stderr carries cause               |
| `IOException` from `ProcessBuilder.start()` | `503 Service Unavailable` | Binary missing / not executable; isAvailable() should catch beforehand |
| `UnsupportedOperationException` (transient, goes away after Plan 24-04) | `501 Not Implemented` | Placeholder, never seen in prod |

## Tasks executed

| # | Task | Commit | Files |
|---|------|--------|-------|
| 1 | Osm2StreetsConfig @ConfigurationProperties + application.properties defaults | `d6c283e` | `Osm2StreetsConfig.java` (new), `application.properties` (+3 lines) |
| 2 | Osm2StreetsService skeleton + executeCli + exception classes + unit tests (TDD) | `287a287` (RED), `46cd148` (GREEN) | `Osm2StreetsService.java` (new), `Osm2StreetsServiceExecuteCliTest.java` (new) |

TDD gates: RED (`test(24-03):`) followed by GREEN (`feat(24-03):`) — both present. No REFACTOR
needed; first GREEN run was green.

## Verification evidence

- `mvn compile -pl backend` — BUILD SUCCESS
- `mvn test -pl backend -Dtest=Osm2StreetsServiceExecuteCliTest` — **Tests run: 8, Failures: 0, Errors: 0, Skipped: 0**
- `mvn test -pl backend` — **Tests run: 359, Failures: 0, Errors: 0, Skipped: 1** (baseline 351 + 8 = 359; 1 pre-existing skip)
- `mvn test -pl backend -Dtest=ArchitectureTest` — 7 tests, 0 failures (no new ArchUnit violations)
- `Files.isExecutable(Path.of("backend/bin/osm2streets-cli-linux-x64"))` returns true (Test F was executed, not skipped)

## Deviations from Plan

None — plan executed exactly as written except for two minor clarity tweaks that stay
within the letter of the acceptance criteria:

1. **executeCli — empty-input guard.** The plan's reference code does `stdin.write(osmXml)`
   unconditionally. I gate on `osmXml.length > 0` to avoid a redundant zero-byte write. Behaviour
   is identical (zero-byte write is a no-op on an `OutputStream`); this keeps the call trace
   cleaner when debugging. Still passes Test A (stdin round-trip) and Test C (empty-input
   timeout path).
2. **Test F dual path resolution.** The plan specified `Paths.get("backend/bin/osm2streets-cli-linux-x64")`
   only. Maven Surefire runs from `backend/` (so `bin/...` is correct), but running from repo
   root uses `backend/bin/...`. Test F probes both and `Assumptions.assumeTrue` skips gracefully
   if neither is executable — preserves the plan's CI-safe guarantee.

No architectural changes. No new dependencies. No schema changes. No Spring context surprises.

## Authentication gates

None — subprocess integration does not touch auth.

## Known stubs

One intentional stub, documented in-code:

- `Osm2StreetsService.fetchAndConvert(BboxRequest)` throws `UnsupportedOperationException` with
  the explicit message `"StreetNetworkMapper + Overpass fetch land in Plan 24-04"`. This is the
  contract agreed by the plan (Task 2 acceptance criterion: "`fetchAndConvert()` throws
  UnsupportedOperationException with a Plan 24-04 marker — temporary — Plan 24-04 replaces the
  body"). Controller in Plan 24-05 is wired only after Plan 24-04 lands the real body, so no
  user-visible stub surface exists.

## Deferred issues

One pre-existing repo hygiene issue recorded in `deferred-items.md`:

- `backend/target/` is tracked in git (~700 `.class` and `surefire-reports/` files) but should
  be gitignored. Surfaced by running `mvn test` during this plan — all modifications are
  regenerated build artefacts, unrelated to osm2streets work. Recommend a separate cleanup plan;
  out of scope per GSD executor scope-boundary rule.

## Threat flags

None introduced. The new service surface is subprocess-local (stdin/stdout over a pipe) and
inherits the existing `ClaudeVisionService` threat profile (external binary invocation, hard
timeout, no network exposure at this layer). Plan 24-05 will introduce the HTTP endpoint and
should revisit the threat model there.

## Self-Check

- `backend/src/main/java/com/trafficsimulator/config/Osm2StreetsConfig.java` — FOUND
- `backend/src/main/java/com/trafficsimulator/service/Osm2StreetsService.java` — FOUND
- `backend/src/test/java/com/trafficsimulator/service/Osm2StreetsServiceExecuteCliTest.java` — FOUND
- `backend/src/main/resources/application.properties` — MODIFIED (osm2streets.* appended)
- Commit `d6c283e` (Task 1) — FOUND in git log
- Commit `287a287` (RED) — FOUND in git log
- Commit `46cd148` (GREEN) — FOUND in git log
- Backend full test count 359 (baseline 351 + 8 new) — VERIFIED via `mvn test`
- ArchitectureTest clean — VERIFIED

## Self-Check: PASSED
