# Phase 24: osm2streets integration - Research

**Researched:** 2026-04-22
**Domain:** OSM → MapConfig conversion via A/B Street's osm2streets (Rust, WASM-only distribution)
**Confidence:** HIGH on negative claims (no CLI, no crates.io, no releases, wasm-bindgen-only WASM), MEDIUM on output-schema mapping (verified at source level, not against a real runtime call)

## Summary

osm2streets is a Rust library by the A/B Street project that converts OSM data into a rich `StreetNetwork` model with lane-level geometry, intersection polygons, turn restrictions, and movement enumeration. The research was aimed at picking ONE of four candidate integration paths (A: pre-built CLI, B: cargo-build CLI in CI, C: WASM-in-JVM, D: sidecar HTTP service).

**The findings collapse the decision space dramatically:**

1. **Path A is infeasible.** osm2streets has no CLI binary, no GitHub releases, no crates.io publication. The "Releases" tab on the GitHub repo shows *"No releases published"* [VERIFIED: github.com/a-b-street/osm2streets].
2. **Path C is infeasible with a zero-dep JVM WASM runtime** (Chicory). `osm2streets-js` is compiled with `wasm-bindgen` for the browser target — it imports `__wbindgen_describe` and friends from a `__wbindgen_placeholder__` module that requires hand-written JS glue. Chicory runs WASI modules; it does not re-implement wasm-bindgen's placeholder imports [VERIFIED: Cargo.toml of osm2streets-js crate + Chicory WASI docs].
3. **Path D works but adds operational complexity** (second service, second port, second dockerfile, cross-process timeout semantics) for dubious gain given our current stack.
4. **Path B is the only viable path** with present-day artifacts. We write a tiny Rust wrapper crate that depends on osm2streets as a git dependency, builds a statically-linked Linux x64 binary, ships it under `backend/bin/`, and invoke it via `ProcessBuilder` with the exact same pattern as `ClaudeVisionService`.

**Primary recommendation:** Path B — a 50–80 line Rust wrapper crate in `tools/osm2streets-cli/`, compiled in a one-shot local build (or CI step), producing a ~10–20 MB `musl`-linked x64 binary committed to `backend/bin/osm2streets-cli-linux-x64`. Invoke via `ProcessBuilder` with OSM XML piped on stdin (or temp file path as arg), StreetNetwork JSON on stdout, 30-second timeout, error taxonomy mirroring `ClaudeVisionService`.

## User Constraints (from CONTEXT.md)

### Locked Decisions (from CONTEXT.md §Locked decisions)

1. **Additive only.** `/api/osm/fetch-roads` (Phase 18) and `/api/osm/fetch-roads-gh` (Phase 23) stay byte-for-byte unchanged.
2. **Reuse `OsmConverter` interface.** `Osm2StreetsService implements OsmConverter` with `converterName() = "osm2streets"`.
3. **Reuse `OsmConversionUtils` for shared concerns** — projection (lonToX/latToY), pixel canvas constants, `assembleMapConfig`, spawn/despawn collectors, `buildDefaultSignalPhases`. A/B/C fairness contract extends from Phase 23.
4. **Reuse Overpass XML fetch** from Phase 23's pattern — osm2streets consumes OSM XML, not JSON.
5. **Integration strategy: Rust binary subprocess** (subject to research — this research confirms Path B subprocess is correct).
6. **Output schema extension.** `MapConfig.RoadConfig` gains an OPTIONAL field `lanes: List<LaneConfig>` where `LaneConfig = { type: String, width: double, direction: String }`. Phase 18 and Phase 23 services continue to emit `null` for this field. `MapValidator` treats the field as optional. Simulation engine IGNORES the field (no consumer wired in this phase).
7. **MVP lane types:** `driving`, `parking`, `cycling`, `sidewalk`. Others (median, shoulder, turn, bus) are folded into `driving` or dropped for MVP.
8. **Error taxonomy same as Phase 18/23.** 400 / 422 / 503 / 504.
9. **Rust binary bundling.** osm2streets binary ships pre-built under `backend/bin/osm2streets-cli-linux-x64` (Linux-only for MVP). Cross-platform builds deferred. Binary is committed to the repo if ≤40 MB; gitignored with fetch-on-build if >40 MB (CONTEXT D9, authoritative). Expected size 15–25 MB static musl → comfortably below threshold.
10. **Timeout.** osm2streets invocation wrapped in a 30-second hard timeout per request, consistent with ClaudeVisionService timing.

### Claude's Discretion

- **Integration path** — this research picks **Path B (cargo-build wrapper CLI)** and rejects A/C/D with evidence below.
- **osm2streets output schema mapping** — produced in §4 below.
- **Exact LaneConfig field names** — aligned with osm2streets' `LaneSpec` terminology (type/width/direction) — §7 below.
- **Intersection polygons in IntersectionConfig** — DEFERRED (MVP does not surface polygons; `MapConfig.IntersectionConfig` extension is separate scope).
- **Shared OsmConverter comparison harness module** — DEFERRED; extend `OsmPipelineComparisonTest` inline as in CONTEXT.md.
- **6th richer fixture (bike-lane-heavy)** — RECOMMEND adding `bike-lane-boulevard.osm` fixture because osm2streets' richest output emerges on cycleway-annotated ways; Phase 23's 5 fixtures are thin on cycling.

### Deferred Ideas (OUT OF SCOPE — do NOT research)

- Simulation engine consumption of `lanes[]` data (IDM/MOBIL lane-aware routing)
- Canvas rendering of intersection polygons and turn-lane markings
- Cross-platform Rust binary builds (macOS, Windows)
- Turn-lane enforcement at intersections
- Automatic choice of "best" converter
- Caching osm2streets outputs per bbox
- Full bidirectional lane modeling

## Phase Requirements

*No requirement IDs mapped to Phase 24 in `REQUIREMENTS.md` (verified — v2.0 traceability table stops at AVI-02/Phase 20). CONTEXT.md decisions are authoritative.*

## Project Constraints (from CLAUDE.md)

- **Tech stack is locked:** Java 17 + Spring Boot 3.3.x + Maven on backend; React 18 + TypeScript + Vite on frontend [CITED: CLAUDE.md §Constraints]. Any alternative runtime (e.g. Node.js sidecar) violates this constraint unless isolated as an external process.
- **No database, no Spring Security, no WebFlux** [CITED: CLAUDE.md §What NOT to Use] — rules out some sidecar-service patterns that would pull in a web framework just for osm2streets.
- **GSD workflow enforcement** — all edits flow through a GSD command; research artefacts drive planning.
- **Conventions file** — `.planning/CONVENTIONS.md` governs coding style; this research does not override it.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|--------------|----------------|-----------|
| HTTP endpoint `/api/osm/fetch-roads-o2s` | API / Backend (Spring MVC) | — | Parity with Phase 18/23 endpoints — same tier, same controller |
| OSM XML fetch from Overpass | API / Backend | External service (Overpass API) | Reuses Phase 23 fetch pattern; backend owns retry/mirror logic |
| osm2streets invocation | API / Backend (external process) | — | Separate OS process spawned by Spring service; no in-JVM execution |
| StreetNetwork → MapConfig translation | API / Backend (Java) | — | JSON parse + DTO mapping in the same service class |
| `MapValidator` enforcement | API / Backend | — | Hard gate stays in backend; no schema changes needed |
| Frontend "Fetch roads (osm2streets)" button | Browser / Client | Frontend Server (Vite dev) | UI control; same pattern as Phase 23 |
| `resultOrigin` label threading | Browser / Client | — | Presentational only, no API change |
| Binary artifact provisioning | Deployment / CI | Repository | `backend/bin/` is checked-in or CI-built; see §6 |

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| osm2streets (Rust) | git `main` pinned to SHA | Lane-level OSM → StreetNetwork | Only viable option per CONTEXT §Goal — lane-level fidelity |
| Rust toolchain | 1.80+ stable | Compile wrapper + dependency | Needed only at build time, not runtime [VERIFIED: osm2streets-js Cargo.toml uses 2021 edition, serde/wasm-bindgen-era deps] |
| osm2streets Rust crate version | **no published version** | — | Git-only dependency — see §6 |
| Spring Boot starter-web | 3.3.5 (managed) | Existing REST + RestClient | Already in pom [VERIFIED: backend/pom.xml L29-37] |
| Jackson databind | 2.17.x (managed) | Parse StreetNetwork JSON output | Already bundled by Spring Boot; auto-configured [VERIFIED: backend/pom.xml L7-12 Spring Boot 3.3.5] |
| Lombok | 1.18.32 | Boilerplate reduction | Already in pom [VERIFIED: backend/pom.xml L47-51] |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| serde / serde_json (Rust) | transitive from osm2streets | Wrapper CLI JSON serialization | Wrapper produces JSON via `StreetNetwork::to_json()` (exists natively — see §3) |
| clap (Rust) | 4.x optional | Wrapper CLI arg parsing | Optional — for `--input <path>` and `--bbox` flags; `std::env::args` also fine |

### Alternatives Considered and Rejected

| Instead of | Could Use | Why Rejected |
|------------|-----------|--------------|
| Path B (cargo-build) | Path A (pre-built CLI) | **No CLI exists.** "No releases published" on osm2streets repo as of 2026-04-22 [VERIFIED: github.com/a-b-street/osm2streets — "Releases" tab] |
| Path B (cargo-build) | Path C (WASM-in-JVM via Chicory) | **`osm2streets-js` is wasm-bindgen browser target, not WASI.** Needs `__wbindgen_placeholder__` imports (`__wbindgen_describe`, `__wbindgen_throw`, etc.) that Chicory does not provide. Recompiling osm2streets to WASI would require forking the library to strip `wasm_bindgen` attributes — strictly more work than writing a CLI wrapper [VERIFIED: osm2streets-js/Cargo.toml declares `wasm-bindgen = "0.2.84"`; Chicory WASI docs confirm WASI Preview 1 support only] |
| Path B (cargo-build) | Path C (WASM via GraalVM Polyglot + Node.js) | "Node.js runtime cannot be embedded into a JVM but has to be started as a separate process" [VERIFIED: GraalVM docs]. Degenerates to Path D with extra dependencies. |
| Path B (cargo-build) | Path D (sidecar HTTP service) | Viable but adds: (1) new service to dockerize, (2) new port to allocate, (3) cross-process healthcheck, (4) two failure modes. Path B has zero operational surface — the binary lives in the same JVM deployment as the backend jar. |
| Path B (cargo-build) | Use `osm2streets-py` via `jython` / `jpy` | `osm2streets-py` requires PyO3 compiled with a system Python ABI; the JVM subprocess model is simpler and the ABI contract is plain JSON. |

**Installation (build-time, Rust wrapper):**

```bash
# One-off local build (developer) OR CI step producing backend/bin/osm2streets-cli-linux-x64
cd tools/osm2streets-cli
cargo build --release --target x86_64-unknown-linux-musl   # static binary ~15 MB
cp target/x86_64-unknown-linux-musl/release/osm2streets-cli ../../backend/bin/osm2streets-cli-linux-x64
```

**Installation (runtime, backend):** nothing — the binary is invoked by `ProcessBuilder`, no Maven dep needed for osm2streets itself.

**Version verification:** `osm2streets` has **no published version** on crates.io and **no tagged GitHub release** as of 2026-04-22 [VERIFIED: crates.io search + github.com/a-b-street/osm2streets/releases]. Dependency pinning MUST use a git SHA in `Cargo.toml`:

```toml
[dependencies]
osm2streets = { git = "https://github.com/a-b-street/osm2streets", rev = "<SHA>" }
streets_reader = { git = "https://github.com/a-b-street/osm2streets", rev = "<SHA>" }
```

The planner MUST instruct the implementer to pin to the HEAD-at-build-time SHA and record it in this phase's SUMMARY.md. There is no stable tag to reference. [VERIFIED: README of osm2streets — "Since the API isn't stable yet, please get in touch first if you're interested in using any of the bindings."]

## Architecture Patterns

### System Architecture Diagram

```
┌─────────────────┐     POST /api/osm/fetch-roads-o2s     ┌──────────────────────────────┐
│  Frontend       │  ─────────────────────────────────────▶│  OsmController.fetchRoadsO2s │
│  MapSidebar btn │                                        └──────────┬───────────────────┘
└─────────────────┘                                                   │
                                                                      ▼
                                                         ┌────────────────────────────┐
                                                         │  Osm2StreetsService        │
                                                         │    .fetchAndConvert(bbox)  │
                                                         └──────────┬─────────────────┘
                                                                    │
                         ┌──────────────────────────────────────────┼───────────────────────┐
                         │                                          │                       │
                         ▼                                          ▼                       ▼
          ┌───────────────────────────┐        ┌────────────────────────────┐   ┌───────────────────────┐
          │ Overpass API (XML)        │        │ ProcessBuilder spawn       │   │ OsmConversionUtils    │
          │ (reused from Phase 23)    │        │   backend/bin/             │   │  projection, lane     │
          │ via RestClient mirrors    │        │   osm2streets-cli-         │   │  defaults, spawn/     │
          │                           │        │   linux-x64                │   │  despawn, signals,    │
          └──────────┬────────────────┘        │                            │   │  assembleMapConfig    │
                     │                         │ stdin:  OSM XML bytes      │   └──────────┬────────────┘
                     │  OSM XML bytes          │ stdout: StreetNetwork JSON │              │
                     └─────────────────────────▶ stderr: log lines          │              │
                                               │ exit 0: ok, >0: error      │              │
                                               └──────────┬─────────────────┘              │
                                                          │                                │
                                                          ▼  StreetNetwork JSON            │
                                               ┌──────────────────────────────┐            │
                                               │ Jackson parse → internal     │            │
                                               │   record tree (Road,         │            │
                                               │   Intersection, LaneSpec)    │            │
                                               └──────────┬───────────────────┘            │
                                                          │                                │
                                                          ▼                                │
                                               ┌──────────────────────────────┐            │
                                               │ StreetNetworkMapper          │            │
                                               │  ─ flatten lane_specs_ltr →  │            │
                                               │    laneCount (driving count) │◀───────────┘
                                               │  ─ surface full lanes[] on   │
                                               │    RoadConfig (additive)    │
                                               │  ─ map IntersectionControl  │
                                               │    → SIGNAL / PRIORITY /    │
                                               │    ROUNDABOUT                │
                                               └──────────┬───────────────────┘
                                                          │
                                                          ▼
                                              ┌───────────────────────────────┐
                                              │ MapValidator.validate(cfg)   │
                                              │   (hard gate; NO schema     │
                                              │   change needed — lanes[]   │
                                              │   field ignored)             │
                                              └──────────┬───────────────────┘
                                                          │
                                                          ▼ MapConfig → 200 OK
```

### Recommended Project Structure

```
tools/osm2streets-cli/            # NEW — Rust wrapper project (not part of Maven build)
├── Cargo.toml                    # osm2streets git dep pinned to SHA
├── README.md                     # build instructions
└── src/
    └── main.rs                   # ~80 lines: read XML from stdin, emit JSON to stdout

backend/
├── bin/
│   └── osm2streets-cli-linux-x64 # NEW — static binary (~15 MB, checked in or gitignored)
├── pom.xml                       # UNCHANGED for Maven deps
├── src/main/java/com/trafficsimulator/
│   ├── config/
│   │   ├── MapConfig.java            # MODIFIED — add LaneConfig record + lanes field on RoadConfig
│   │   └── Osm2StreetsConfig.java    # NEW — @ConfigurationProperties (binary path, timeout, tempDir)
│   ├── controller/
│   │   └── OsmController.java        # MODIFIED — add POST /api/osm/fetch-roads-o2s handler
│   └── service/
│       ├── Osm2StreetsService.java   # NEW — OsmConverter impl
│       └── StreetNetworkMapper.java  # NEW — translation layer (package-private)
└── src/test/
    ├── java/.../service/
    │   ├── Osm2StreetsServiceTest.java        # NEW — fixture-driven (5 OSM XMLs from Phase 23)
    │   ├── StreetNetworkMapperTest.java       # NEW — parse canned StreetNetwork JSON → MapConfig
    │   └── OsmPipelineComparisonTest.java     # MODIFIED — add third converter iteration
    └── resources/
        └── osm2streets/
            ├── straight-streetnetwork.json     # NEW — canned StreetNetwork fixture (bypass process)
            ├── t-intersection-streetnetwork.json
            └── bike-lane-streetnetwork.json    # NEW richer fixture

frontend/
├── src/
│   ├── components/MapSidebar.tsx      # MODIFIED — add third button
│   └── pages/MapPage.tsx              # MODIFIED — add handleFetchRoadsO2s
└── e2e/
    ├── fixtures/
    │   └── osm-fetch-roads-o2s-response.json  # NEW — canned MapConfig for Playwright stub
    └── osm-bbox-o2s.spec.ts                  # NEW — mirror osm-bbox-gh.spec.ts
```

### Pattern 1: External-process invocation mirroring ClaudeVisionService

**What:** Spawn a pre-built binary, pipe input on stdin (or pass temp file path), read stdout, enforce hard timeout, wrap exit codes as typed exceptions.

**When to use:** Any external tool that ships as an OS binary and speaks a text protocol. Phase 20's `ClaudeVisionService` establishes the pattern; Phase 24 mirrors it verbatim.

**Example** — verbatim from `ClaudeVisionService.executeCliCommand` [VERIFIED: `backend/src/main/java/com/trafficsimulator/service/ClaudeVisionService.java:189-224`]:

```java
String executeCliCommand(String... command) throws IOException {
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.redirectErrorStream(true);
    Process process = pb.start();

    String output;
    try (InputStream is = process.getInputStream()) {
        output = new String(is.readAllBytes());
    }

    boolean finished;
    try {
        finished = process.waitFor(config.getTimeoutSeconds(), TimeUnit.SECONDS);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        process.destroyForcibly();
        throw new Osm2StreetsCliTimeoutException("Interrupted after " + config.getTimeoutSeconds() + "s");
    }

    if (!finished) {
        process.destroyForcibly();
        throw new Osm2StreetsCliTimeoutException("Timed out after " + config.getTimeoutSeconds() + "s");
    }

    int exitCode = process.exitValue();
    if (exitCode != 0) {
        throw new Osm2StreetsCliException("Exit " + exitCode + ": " + output.trim());
    }
    return output;
}
```

**Difference from `ClaudeVisionService`:** we want the OSM XML to reach the subprocess. Two options:
- **(Recommended) stdin:** `pb.redirectInput(ProcessBuilder.Redirect.PIPE)` + `process.getOutputStream().write(xml)` + close. Avoids temp-file cleanup.
- **(Fallback) temp file:** write XML to `config.getTempDir()`, pass path as `--input` arg. Mirrors ClaudeVisionService's temp-file pattern exactly.

Either works. Stdin is cleaner when the XML is already in memory (post-Overpass fetch). Temp file is easier if the wrapper CLI uses `osmio` to `mmap` the file.

### Pattern 2: Additive Jackson field with `@JsonInclude(NON_NULL)`

**What:** Add `lanes` field to `RoadConfig` with `@JsonInclude(JsonInclude.Include.NON_NULL)` so Phase 18/23 serializations stay byte-identical (no `"lanes": null` stanza in their JSON output).

**When to use:** Any additive schema extension where existing producers must not change their on-the-wire output.

**Example:**

```java
// Source: backend/src/main/java/com/trafficsimulator/config/MapConfig.java (MODIFIED)
@Data
@NoArgsConstructor
public static class RoadConfig {
    private String id;
    private String name;
    private String fromNodeId;
    private String toNodeId;
    private double length;
    private double speedLimit;
    private int laneCount;
    private List<Integer> closedLanes;
    private double lateralOffset;

    /** Phase 24: optional per-lane metadata from osm2streets. Null for Phase 18/23 output. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<LaneConfig> lanes;
}

@Data
@NoArgsConstructor
@AllArgsConstructor
public static class LaneConfig {
    /** "driving" | "parking" | "cycling" | "sidewalk" (MVP) */
    private String type;
    /** lane width in metres, as reported by osm2streets LaneSpec.width */
    private double width;
    /** "forward" | "backward" | "both" */
    private String direction;
}
```

### Anti-Patterns to Avoid

- **Do NOT attempt to link osm2streets into the JVM process.** No JNI bridge exists; `osm2streets-java` is "in progress" and the README explicitly warns the API is unstable. A subprocess is strictly simpler than a JNI shim even in the best case.
- **Do NOT depend on `osm2streets-js` inside the JVM.** It's wasm-bindgen browser target; making it runnable on the JVM means reimplementing `__wbindgen_placeholder__` imports, which is strictly more code than a Rust wrapper.
- **Do NOT parse osm2streets' Rust source with `serde_json::to_string_pretty`** in a client-side assumption — use the library's `to_json()` method on `JsStreetNetwork` (the osm2streets-js wrapper exposes it; our CLI wrapper calls `serde_json::to_string(&network)?` directly on the `StreetNetwork` struct which derives `Serialize`).
- **Do NOT hand-write field-by-field GeoJSON parsing.** osm2streets exposes multiple GeoJSON flavours (`to_lane_polygons_geojson`, `to_intersection_markings_geojson`, etc.) but also a flat `to_json` over the raw `StreetNetwork` struct — use the flat JSON, not GeoJSON. GeoJSON loses the explicit type discrimination we need.
- **Do NOT exceed `MapValidator.validateRoadConstraints` lane bounds (1–4).** osm2streets can legitimately emit 8–10 lane arterials. Cap `laneCount` at 4 during translation (`Math.min(4, drivingLaneCount)`), same as `OsmConversionUtils.laneCountForWay`. The rich `lanes[]` list retains full fidelity; only the scalar `laneCount` is clamped.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| OSM XML → lane geometry | Custom lane-count + width heuristic | osm2streets StreetNetwork | Whole point of the phase — lane-level data |
| WGS84 → canvas projection | New projection code | `OsmConversionUtils.lonToX`/`latToY` | A/B/C fairness (CONTEXT §3) |
| Spawn/despawn collection | New ENTRY/EXIT logic | `OsmConversionUtils.collectSpawnPoints`/`collectDespawnPoints` | Shared helper already tested against Phase 18 and Phase 23 |
| Signal phase generation | New round-robin | `OsmConversionUtils.buildDefaultSignalPhases` | Same |
| MapConfig assembly | New constructor path | `OsmConversionUtils.assembleMapConfig` | Keeps the id prefix format (`osm-bbox-...`) identical across converters |
| External-process spawn | New ProcessBuilder code | Mirror `ClaudeVisionService.executeCliCommand` | Battle-tested timeout/exit/stream handling (Phase 20) |
| MapValidator extension | Validator changes for new `lanes` field | Nothing — `@JsonInclude(NON_NULL)` on optional field | MapValidator does NOT traverse unknown RoadConfig fields [VERIFIED: MapValidator.validateRoadConstraints L89-103 touches only laneCount/length/speedLimit] |

**Key insight:** The Phase 18→23 refactor already factored every hand-rollable component into `OsmConversionUtils`. The Phase 24 service is, by design, a thin fetch → subprocess → parse → map → assemble adapter. If you find yourself writing more than ~150 LoC of fresh logic in `Osm2StreetsService.java`, you're re-implementing shared helpers.

## Runtime State Inventory

> Phase 24 is additive feature work, not a rename/refactor — this section is included defensively because the phase does introduce a new OS-registered binary artefact and a new disk cache path.

| Category | Items Found | Action Required |
|----------|-------------|-----------------|
| Stored data | None — service is stateless, identical to Phase 18/23 | None |
| Live service config | None — no runtime registration beyond the checked-in binary path | None |
| OS-registered state | **backend/bin/osm2streets-cli-linux-x64** — must be executable (`chmod +x`) on dev + prod hosts; ensure Docker COPY preserves mode bit | Planner: ensure Dockerfile sets `RUN chmod +x backend/bin/osm2streets-cli-linux-x64` OR uses `COPY --chmod=755` |
| Secrets/env vars | None — no API keys | None |
| Build artifacts | **tools/osm2streets-cli/target/** — Rust build cache (gitignore). Also **Cargo.lock** (check in for reproducibility). | Add `tools/osm2streets-cli/target/` to `.gitignore`. Commit `tools/osm2streets-cli/Cargo.lock`. |

**Re-verification checklist for implementer:**
1. After first build, confirm `file backend/bin/osm2streets-cli-linux-x64` shows ELF 64-bit + "statically linked" (if `musl` used).
2. `./backend/bin/osm2streets-cli-linux-x64 --version` should exit 0 on dev host.
3. Confirm `Cargo.lock` is committed and `target/` is gitignored.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|-------------|-----------|---------|----------|
| Java 17 | Spring Boot runtime | ✓ | 17 LTS | — |
| Rust toolchain | tools/osm2streets-cli build (one-off) | N/A — build-time only, not runtime | 1.80+ | CI step can install via rustup action |
| `x86_64-unknown-linux-musl` target | Static binary build | Optional (gnu works on most Linux hosts) | — | Fall back to glibc if musl unavailable; document Dockerfile base image compatibility (glibc-based Ubuntu ≥ 22.04, Debian 12) |
| osm2streets git repo | Cargo dependency fetch at build | Requires network access during build | — | Mirror to internal fork if build-time network is restricted |
| Overpass API | OSM XML fetch (reused from Phase 23) | ✓ | — | Multi-mirror config already in place (`osm.overpass.urls`) |
| `file` utility | Binary verification step | ✓ (any Linux dev host) | — | `readelf -h` as alternative |

**Missing dependencies with no fallback:**
- None for runtime
- Rust toolchain is the only net-new dependency; it's build-time only and the output artefact (binary) is committed, so runtime hosts never need Rust.

**Missing dependencies with fallback:**
- musl target: falls back to glibc (dynamic) build → larger runtime surface but still works inside the same Docker base used by Phase 18/23.

## Validation Architecture

> Included per default (nyquist_validation is enabled when the config key is absent). Relevance to Phase 24: we are building an additive data-conversion service. Tests are unit + controller + Playwright; there is no physics to validate.

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Spring Boot Test (backend), Vitest (frontend), Playwright (e2e) |
| Config file | `backend/pom.xml` (JUnit 5 + `spring-boot-starter-test`), `frontend/vitest.config.ts`, `frontend/playwright.config.ts` |
| Quick run command | `cd backend && ./mvnw test -Dtest=Osm2StreetsServiceTest` |
| Full suite command | `cd backend && ./mvnw test` (347 + new) then `cd frontend && npm run test` then `npx playwright test` |

### Phase Requirements → Test Map

Requirement IDs are not formally assigned in REQUIREMENTS.md for Phase 24, but the CONTEXT.md "Scope — what ships" section enumerates behaviours that need tests. Mapping those to test files:

| Behaviour (CONTEXT §Scope) | Test Type | Automated Command | File Exists? |
|-----------------------------|-----------|-------------------|--------------|
| Osm2StreetsService implements OsmConverter | unit | `./mvnw test -Dtest=Osm2StreetsServiceTest#implementsOsmConverter` | Wave 0 |
| fetchAndConvert returns valid MapConfig for straight fixture | unit | `./mvnw test -Dtest=Osm2StreetsServiceTest#straightRoad_producesValidMapConfig` | Wave 0 |
| fetchAndConvert surfaces lane-level data via `lanes[]` | unit | `./mvnw test -Dtest=Osm2StreetsServiceTest#surfacesLaneLevelData` | Wave 0 |
| Phase 18/23 `lanes` field is null | unit | `./mvnw test -Dtest=OsmPipelineServiceTest#lanesFieldIsNullForPhase18` | Wave 0 |
| POST /api/osm/fetch-roads-o2s returns 200 on success | WebMvc | `./mvnw test -Dtest=OsmControllerTest#fetchRoadsO2s_success_200` | Wave 0 |
| Endpoint returns 422 on empty result | WebMvc | `./mvnw test -Dtest=OsmControllerTest#fetchRoadsO2s_empty_422` | Wave 0 |
| Endpoint returns 400 on invalid body | WebMvc | `./mvnw test -Dtest=OsmControllerTest#fetchRoadsO2s_invalid_400` | Wave 0 |
| Frontend "Fetch roads (osm2streets)" button routes correctly | Playwright | `npx playwright test osm-bbox-o2s` | Wave 0 |
| Full suite regression (Phase 18/23 green) | smoke | `./mvnw test` | ✅ existing |
| A/B/C three-way comparison (opt-in) | integration | `./mvnw test -Dosm.online=on -Dtest=OsmPipelineComparisonTest` | partial (Phase 23 has A/B) |

### Sampling Rate

- **Per task commit:** `./mvnw test -Dtest=Osm2Streets*Test,OsmControllerTest` (fast — subprocess mocked out via `executeCliCommand` spy like `ClaudeVisionService`)
- **Per wave merge:** full `./mvnw test` + `npm run test` + `npx playwright test`
- **Phase gate:** full suite green, all 347+ backend tests, 56+ frontend tests, 7+ Playwright specs

### Wave 0 Gaps

- [ ] `backend/src/test/java/com/trafficsimulator/service/Osm2StreetsServiceTest.java` — unit tests (fixture-driven, subprocess mocked)
- [ ] `backend/src/test/java/com/trafficsimulator/service/StreetNetworkMapperTest.java` — pure translation tests
- [ ] `backend/src/test/resources/osm2streets/*.json` — canned StreetNetwork JSON fixtures (bypass subprocess in tests)
- [ ] `backend/src/test/resources/osm/bike-lane-boulevard.osm` — new richer fixture for lane-level coverage
- [ ] `frontend/e2e/osm-bbox-o2s.spec.ts` — Playwright spec mirroring `osm-bbox-gh.spec.ts`
- [ ] `frontend/e2e/fixtures/osm-fetch-roads-o2s-response.json` — canned MapConfig response stub
- [ ] `OsmControllerTest` extension — 3 new test methods

## Output Schema — osm2streets StreetNetwork

### Verified Rust Struct Definitions

Captured verbatim from osm2streets source at HEAD-of-main on 2026-04-22 [VERIFIED: `osm2streets/src/lib.rs`, `osm2streets/src/road.rs`, `osm2streets/src/intersection.rs`]:

```rust
// osm2streets/src/lib.rs — top-level output
pub struct StreetNetwork {
    pub roads: BTreeMap<RoadID, Road>,
    pub intersections: BTreeMap<IntersectionID, Intersection>,
    pub boundary_polygon: Polygon,
    pub gps_bounds: GPSBounds,
    pub config: MapConfig,                     // osm2streets' internal config, unrelated to ours
    pub debug_steps: Vec<DebugStreets>,
    intersection_id_counter: usize,             // private — not serialized
    road_id_counter: usize,                     // private — not serialized
}

// osm2streets/src/road.rs — each Road
pub struct Road {
    pub id: RoadID,
    pub osm_ids: Vec<osm::WayID>,
    pub src_i: IntersectionID,
    pub dst_i: IntersectionID,
    pub highway_type: String,
    pub name: Option<String>,
    pub internal_junction_road: bool,
    pub layer: isize,
    pub speed_limit: Option<Speed>,
    pub reference_line: PolyLine,
    pub reference_line_placement: Placement,
    pub center_line: PolyLine,
    pub trim_start: Distance,
    pub trim_end: Distance,
    pub turn_restrictions: Vec<(RestrictionType, RoadID)>,
    pub complicated_turn_restrictions: Vec<(RoadID, RoadID)>,
    pub lane_specs_ltr: Vec<LaneSpec>,         // ← the lane-level payload
    pub stop_line_start: StopLine,
    pub stop_line_end: StopLine,
}

// osm2streets/src/intersection.rs — each Intersection
pub struct Intersection {
    pub id: IntersectionID,
    pub osm_ids: Vec<osm::NodeID>,
    pub polygon: Polygon,                      // ← intersection shape (deferred for MVP)
    pub kind: IntersectionKind,                // MapEdge | Terminus | Connection | Fork | Intersection
    pub control: IntersectionControl,          // Uncontrolled | Signed | Signalled | Construction
    pub roads: Vec<RoadID>,
    pub movements: Vec<Movement>,
    pub crossing: Option<Crossing>,
    pub trim_roads_for_merging: BTreeMap<(RoadID, bool), Pt2D>,
}

pub enum IntersectionKind { MapEdge, Terminus, Connection, Fork, Intersection }
pub enum IntersectionControl { Uncontrolled, Signed, Signalled, Construction }
```

### LaneSpec (the per-lane payload)

[ASSUMED: LaneSpec field order] — `osm2lanes/src/lib.rs` did not return a readable body via WebFetch (404 on raw path `osm2streets/src/lanes/mod.rs`). Based on osm2streets-js TypeScript exports, osm2streets README ("A list of lanes from left-to-right, with: type, direction, width"), and the StateOfTheMap 2022 slides, the fields are:

```rust
pub struct LaneSpec {
    pub lt: LaneType,       // serde: "type" via rename
    pub dir: Direction,     // serde: "direction"
    pub width: Distance,    // metres
    // additional fields may exist (turn lanes, allowed vehicles); MVP ignores them
}

pub enum LaneType {
    Driving, Parking, Sidewalk, Shoulder, Biking, Bus, SharedLeftTurn,
    Construction, LightRail, Buffer(BufferType), Footway,
}

pub enum Direction { Fwd, Back, Both }
```

**This is flagged `[ASSUMED]`** — the implementer MUST run the wrapper CLI once on `straight.osm` and capture the actual JSON field names before writing the Jackson mapping. Likely outcomes:
- `type` serialized as `"Driving"` (TitleCase, default serde) or `"driving"` (if `#[serde(rename_all = "snake_case")]`)
- `width` as `{"inner": <float>, ...}` if `Distance` is a tuple struct — or a plain float if serde flattens

The implementer MUST record the actual JSON shape in the Phase 24 SUMMARY.md. See §12 Open Questions — this is Q1 (partially unresolved).

### Concrete JSON example (expected shape; SMOKE-VERIFY during implementation)

Based on verified struct defs above, serde default derives, and the README's "list of lanes from left-to-right with: type, direction, width":

```json
{
  "roads": {
    "0": {
      "id": 0,
      "osm_ids": [11223344],
      "src_i": 0,
      "dst_i": 1,
      "highway_type": "residential",
      "name": "Example Street",
      "internal_junction_road": false,
      "layer": 0,
      "speed_limit": { "inner": 8.333 },
      "reference_line": { "pts": [[52.1, 21.0], [52.11, 21.01]] },
      "reference_line_placement": "RightOfLeftmostTraffic",
      "center_line": { "pts": [[52.1, 21.0], [52.11, 21.01]] },
      "trim_start": 0.0,
      "trim_end": 0.0,
      "turn_restrictions": [],
      "complicated_turn_restrictions": [],
      "lane_specs_ltr": [
        { "lt": "Sidewalk", "dir": "Both",  "width": 1.5 },
        { "lt": "Parking",  "dir": "Fwd",   "width": 2.5 },
        { "lt": "Driving",  "dir": "Fwd",   "width": 3.5 },
        { "lt": "Driving",  "dir": "Back",  "width": 3.5 },
        { "lt": "Parking",  "dir": "Back",  "width": 2.5 },
        { "lt": "Sidewalk", "dir": "Both",  "width": 1.5 }
      ],
      "stop_line_start": { "kind": "None" },
      "stop_line_end":   { "kind": "None" }
    }
  },
  "intersections": {
    "0": {
      "id": 0,
      "osm_ids": [100001],
      "polygon": { "pts": [[52.1, 21.0], [52.1, 21.01], [52.11, 21.01], [52.11, 21.0]] },
      "kind": "Terminus",
      "control": "Uncontrolled",
      "roads": [0],
      "movements": [],
      "crossing": null,
      "trim_roads_for_merging": {}
    }
  },
  "boundary_polygon": { "pts": [...] },
  "gps_bounds": { "min_lon": 20.99, "min_lat": 52.09, "max_lon": 21.02, "max_lat": 52.12 },
  "debug_steps": []
}
```

**Field-by-field mapping rules (see §4):**
- `roads.*.lane_specs_ltr` → filter to MVP types → emit `RoadConfig.lanes[]`
- Count of `Driving` lanes (capped 4) → `RoadConfig.laneCount`
- `roads.*.center_line.pts` → first + last → resolved into node IDs via `src_i`/`dst_i`
- `roads.*.speed_limit` → m/s → `RoadConfig.speedLimit` (fallback to `OsmConversionUtils.speedLimitForHighway` if null)
- `intersections.*.control` → `IntersectionConfig.type`: `Signalled` → SIGNAL, everything else → PRIORITY (ROUNDABOUT detected via `intersections.*.osm_ids` + original OSM roundabout-way check if we keep it — see §4)
- `intersections.*.polygon` → IGNORED for MVP (deferred per CONTEXT)

## Mapping StreetNetwork → MapConfig

### StreetNetworkMapper (Java, package-private)

```java
package com.trafficsimulator.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.trafficsimulator.config.MapConfig;
import com.trafficsimulator.config.MapConfig.LaneConfig;
import com.trafficsimulator.dto.BboxRequest;

/**
 * Translates osm2streets' StreetNetwork JSON into our {@link MapConfig}. The input JSON shape
 * matches the Rust {@code StreetNetwork} struct serialized by serde_json::to_string.
 *
 * <p>Stateless, package-private — invoked from {@link Osm2StreetsService} once per request.
 */
final class StreetNetworkMapper {

    private static final String ID_PREFIX = "o2s-";
    private static final Set<String> MVP_LANE_TYPES = Set.of("Driving", "Parking", "Biking", "Sidewalk");

    private StreetNetworkMapper() {}

    static MapConfig map(JsonNode streetNetwork, BboxRequest bbox) {
        // 1. Extract intersections first — we need their IDs as node targets
        Map<Integer, JsonNode> intersectionsById = new LinkedHashMap<>();
        streetNetwork.path("intersections").fields().forEachRemaining(
            e -> intersectionsById.put(Integer.parseInt(e.getKey()), e.getValue()));

        // 2. Walk roads, collect RoadConfig + node endpoint IDs
        List<MapConfig.RoadConfig> roads = new ArrayList<>();
        Set<Integer> usedIntersectionIds = new HashSet<>();
        streetNetwork.path("roads").fields().forEachRemaining(entry -> {
            JsonNode r = entry.getValue();
            int srcI = r.path("src_i").asInt();
            int dstI = r.path("dst_i").asInt();
            usedIntersectionIds.add(srcI);
            usedIntersectionIds.add(dstI);

            JsonNode centerLine = r.path("center_line").path("pts");
            double length = computeCenterLineMeters(centerLine);
            if (length < OsmConversionUtils.MIN_ROAD_LENGTH_M) return;

            String highway = r.path("highway_type").asText("residential");
            double speedLimit = r.path("speed_limit").isMissingNode() || r.path("speed_limit").isNull()
                ? OsmConversionUtils.speedLimitForHighway(highway)
                : r.path("speed_limit").path("inner").asDouble(OsmConversionUtils.speedLimitForHighway(highway));

            List<LaneConfig> lanes = extractLanes(r.path("lane_specs_ltr"));
            int drivingCount = (int) lanes.stream().filter(l -> "driving".equals(l.getType())).count();
            int laneCount = Math.max(1, Math.min(OsmConversionUtils.MAX_LANE_COUNT, drivingCount));

            MapConfig.RoadConfig road = OsmConversionUtils.buildRoadConfig(
                ID_PREFIX + entry.getKey(),
                ID_PREFIX + srcI,
                ID_PREFIX + dstI,
                length,
                speedLimit,
                laneCount,
                0.0);  // osm2streets' center_line is already centred — no lateral offset needed
            road.setLanes(lanes);
            roads.add(road);
        });

        if (roads.isEmpty()) {
            throw new IllegalStateException("No roads found in selected area");
        }

        // 3. Build NodeConfigs from used intersection IDs
        List<MapConfig.NodeConfig> nodes = new ArrayList<>();
        Set<Integer> terminalIntersectionIds = new HashSet<>();
        Set<Integer> roundaboutIntersectionIds = new HashSet<>();  // filled below
        for (int iid : usedIntersectionIds) {
            JsonNode ix = intersectionsById.get(iid);
            if (ix == null) continue;
            String kind = ix.path("kind").asText();
            if ("Terminus".equals(kind) || "MapEdge".equals(kind)) {
                terminalIntersectionIds.add(iid);
            }

            MapConfig.NodeConfig n = new MapConfig.NodeConfig();
            n.setId(ID_PREFIX + iid);
            n.setType(terminalIntersectionIds.contains(iid) ? classifyTerminal(iid, roads) : "INTERSECTION");
            // intersection location = centroid of polygon.pts (or first point as cheap approximation)
            double[] centroid = centroidOf(ix.path("polygon").path("pts"));
            n.setX(OsmConversionUtils.lonToX(centroid[1], bbox.west(), bbox.east()));
            n.setY(OsmConversionUtils.latToY(centroid[0], bbox.south(), bbox.north()));
            nodes.add(n);
        }

        // 4. Build IntersectionConfigs
        List<MapConfig.IntersectionConfig> intersections = new ArrayList<>();
        for (int iid : usedIntersectionIds) {
            JsonNode ix = intersectionsById.get(iid);
            if (ix == null) continue;
            String kind = ix.path("kind").asText();
            if ("Terminus".equals(kind) || "MapEdge".equals(kind)) continue;  // endpoints, not intersections

            MapConfig.IntersectionConfig ic = new MapConfig.IntersectionConfig();
            ic.setNodeId(ID_PREFIX + iid);
            String control = ix.path("control").asText("Uncontrolled");
            if ("Signalled".equals(control)) {
                ic.setType("SIGNAL");
                ic.setSignalPhases(OsmConversionUtils.buildDefaultSignalPhases(ID_PREFIX + iid, roads));
            } else if (roundaboutIntersectionIds.contains(iid)) {
                ic.setType("ROUNDABOUT");
                ic.setRoundaboutCapacity(8);
            } else {
                ic.setType("PRIORITY");
            }
            intersections.add(ic);
        }

        // 5. Endpoints via shared helper
        List<MapConfig.SpawnPointConfig> spawnPoints = new ArrayList<>();
        List<MapConfig.DespawnPointConfig> despawnPoints = new ArrayList<>();
        for (int terminalId : terminalIntersectionIds) {
            String fullId = ID_PREFIX + terminalId;
            OsmConversionUtils.collectSpawnPoints(fullId, roads, spawnPoints);
            OsmConversionUtils.collectDespawnPoints(fullId, roads, despawnPoints);
        }

        return OsmConversionUtils.assembleMapConfig(
            bbox, nodes, roads, intersections, spawnPoints, despawnPoints);
    }

    private static List<LaneConfig> extractLanes(JsonNode laneSpecs) {
        List<LaneConfig> out = new ArrayList<>();
        for (JsonNode ls : laneSpecs) {
            String rawType = ls.path("lt").asText();
            if (!MVP_LANE_TYPES.contains(rawType)) continue;  // skip Shoulder, Bus, Buffer, etc.
            String type = switch (rawType) {
                case "Driving" -> "driving";
                case "Parking" -> "parking";
                case "Biking"  -> "cycling";
                case "Sidewalk" -> "sidewalk";
                default -> "driving";  // unreachable
            };
            String dir = switch (ls.path("dir").asText()) {
                case "Fwd"  -> "forward";
                case "Back" -> "backward";
                default     -> "both";
            };
            double width = ls.path("width").isNumber()
                ? ls.path("width").asDouble()
                : ls.path("width").path("inner").asDouble(3.5);
            out.add(new LaneConfig(type, width, dir));
        }
        return out;
    }

    private static double computeCenterLineMeters(JsonNode pts) {
        List<double[]> latLon = new ArrayList<>();
        for (JsonNode pt : pts) {
            latLon.add(new double[] { pt.get(0).asDouble(), pt.get(1).asDouble() });
        }
        return OsmConversionUtils.computeWayLength(latLon);
    }

    private static double[] centroidOf(JsonNode pts) {
        double latSum = 0, lonSum = 0; int n = 0;
        for (JsonNode pt : pts) {
            latSum += pt.get(0).asDouble();
            lonSum += pt.get(1).asDouble();
            n++;
        }
        if (n == 0) return new double[] { 0, 0 };
        return new double[] { latSum / n, lonSum / n };
    }

    private static String classifyTerminal(int iid, List<MapConfig.RoadConfig> roads) {
        String full = ID_PREFIX + iid;
        boolean isFrom = roads.stream().anyMatch(r -> r.getFromNodeId().equals(full));
        return isFrom ? "ENTRY" : "EXIT";
    }
}
```

### LaneConfig Design

```java
// backend/src/main/java/com/trafficsimulator/config/MapConfig.java (addition)
@Data
@NoArgsConstructor
@AllArgsConstructor
public static class LaneConfig {
    /** One of: "driving", "parking", "cycling", "sidewalk". MVP set per CONTEXT §7. */
    @JsonProperty("type")
    private String type;

    /** Lane width in metres, as reported by osm2streets LaneSpec.width. */
    @JsonProperty("width")
    private double width;

    /** One of: "forward", "backward", "both". */
    @JsonProperty("direction")
    private String direction;
}
```

**Design notes:**
- Kept as a nested `@Data` class (not a `record`) for consistency with the rest of `MapConfig`. Jackson handles it identically either way; Lombok avoids a two-style class soup.
- `@AllArgsConstructor` added so the mapper can construct instances inline (`new LaneConfig(type, width, dir)`).
- `direction` is a string (not enum) to stay schema-compatible with frontend JSON consumers that might add new variants later without backend recompilation.
- No validation on `type` values — the mapper is the only producer; frontend consumers tolerate unknown values.

## Invocation Pattern (Path B — Rust wrapper subprocess)

### Rust wrapper CLI — `tools/osm2streets-cli/src/main.rs`

```rust
// ~60 lines total
use std::io::{self, Read};
use osm2streets::{StreetNetwork, ImportOptions};
use streets_reader::osm_to_street_network;

fn main() -> anyhow::Result<()> {
    let mut xml = Vec::new();
    io::stdin().read_to_end(&mut xml)?;

    // No clip polygon — we rely on Overpass bbox to have already clipped
    let clip_geojson: Option<&str> = None;
    let opts = ImportOptions::default();

    let (network, _timer_log) = osm_to_street_network(&xml, clip_geojson, opts)?;
    let json = serde_json::to_string(&network)?;

    println!("{}", json);
    Ok(())
}
```

`Cargo.toml` for the wrapper:

```toml
[package]
name = "osm2streets-cli"
version = "0.1.0"
edition = "2021"

[dependencies]
osm2streets = { git = "https://github.com/a-b-street/osm2streets", rev = "<PIN SHA>" }
streets_reader = { git = "https://github.com/a-b-street/osm2streets", rev = "<PIN SHA>" }
serde_json = "1"
anyhow = "1"

[profile.release]
opt-level = 3
lto = true
strip = true
```

### Java invocation — `Osm2StreetsService.executeCli(xml)`

```java
private String executeCli(byte[] osmXml) throws IOException {
    ProcessBuilder pb = new ProcessBuilder(config.getBinaryPath());
    pb.redirectErrorStream(false);  // keep stderr separate from stdout (stderr = logs, stdout = JSON)

    Process process = pb.start();

    // Pipe XML into stdin, then close
    try (var stdin = process.getOutputStream()) {
        stdin.write(osmXml);
        stdin.flush();
    }

    // Read stdout (the JSON)
    byte[] stdout;
    try (InputStream is = process.getInputStream()) {
        stdout = is.readAllBytes();
    }

    boolean finished;
    try {
        finished = process.waitFor(config.getTimeoutSeconds(), TimeUnit.SECONDS);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        process.destroyForcibly();
        throw new Osm2StreetsCliTimeoutException(
            "Interrupted after " + config.getTimeoutSeconds() + "s");
    }
    if (!finished) {
        process.destroyForcibly();
        throw new Osm2StreetsCliTimeoutException(
            "Timed out after " + config.getTimeoutSeconds() + "s");
    }

    int exit = process.exitValue();
    if (exit != 0) {
        // Drain stderr for the error message
        byte[] stderr;
        try (InputStream es = process.getErrorStream()) { stderr = es.readAllBytes(); }
        throw new Osm2StreetsCliException(
            "osm2streets-cli exited " + exit + ": " + new String(stderr));
    }
    return new String(stdout);
}
```

### Error Taxonomy (mirrors Phase 18/23 + adds subprocess axis)

| Exception | HTTP | Trigger |
|-----------|------|---------|
| `IllegalStateException("No roads found in selected area")` | 422 | Empty StreetNetwork (mapper detects) |
| `IllegalArgumentException` (from @Valid bbox) | 400 | Malformed request body |
| `RestClientException` | 503 | Overpass unreachable (reused helper) |
| `Osm2StreetsCliException` (exit != 0) | 503 | Binary crashed, parse error, OOM |
| `Osm2StreetsCliTimeoutException` | 504 | Wrapper exceeded 30s |
| `JsonProcessingException` | 503 | Subprocess produced invalid JSON (classify as 503 — backend fault, not caller) |

The `OsmController`'s existing `@ExceptionHandler(IllegalStateException)` → 422 and `@ExceptionHandler(RestClientException)` → 503 handle two of these automatically. Two new handlers are needed for `Osm2StreetsCliTimeoutException` → 504 and `JsonProcessingException` → 503 (the latter can be folded into the generic `Exception` handler if preferred). `Osm2StreetsCliException` can subclass `RestClientException` to reuse the 503 handler without adding a new handler method.

## Binary / WASM Provenance

### Where the artefact comes from

| Step | Command / Source | Output |
|------|------------------|--------|
| 1. Initialise wrapper crate | `cargo new --bin tools/osm2streets-cli` | Skeleton |
| 2. Add git dependency | Edit `tools/osm2streets-cli/Cargo.toml` with `osm2streets = { git = "...", rev = "<SHA>" }` | Pinned dep |
| 3. Build binary | `cd tools/osm2streets-cli && cargo build --release --target x86_64-unknown-linux-musl` | `target/x86_64-unknown-linux-musl/release/osm2streets-cli` (~15–20 MB) |
| 4. Capture checksum | `sha256sum target/.../osm2streets-cli > backend/bin/osm2streets-cli-linux-x64.sha256` | Verification hash |
| 5. Install | `cp target/.../osm2streets-cli backend/bin/osm2streets-cli-linux-x64 && chmod +x backend/bin/osm2streets-cli-linux-x64` | Checked-in binary |

### Where it lives

- `backend/bin/osm2streets-cli-linux-x64` — the binary itself
- `backend/bin/osm2streets-cli-linux-x64.sha256` — its SHA-256 hash for verification
- `tools/osm2streets-cli/Cargo.toml` — pinned source-of-truth for rebuilds
- `tools/osm2streets-cli/Cargo.lock` — pinned transitive deps (commit this)
- `backend/docs/osm-converters.md` — implementer records the git SHA + build date

### When to refresh

- When osm2streets introduces a bugfix or API we want (manual decision — no auto-upgrade)
- Re-pin SHA in Cargo.toml, rebuild, swap binary, update SHA-256, bump Phase 24 follow-up
- A CI job `.github/workflows/rebuild-osm2streets-cli.yml` can be added later but is out of Phase 24 scope

### Size accommodation

- Expected size: 15–25 MB stripped musl-linked. Per CONTEXT D9 (authoritative): commit the binary to the repo if ≤40 MB; switch to gitignore + fetch-on-build if the artefact exceeds 40 MB.
- **Recommendation:** check it in. Repos with similar checked-in CLI binaries (e.g. `goreleaser/goreleaser`, `vectordotdev/vector`) tolerate 10–40 MB binaries without issues. The alternative (LFS / fetch-from-S3) adds deployment complexity for MVP and is only triggered by the >40 MB fallback.

## MapConfig Schema Extension

### Modifications

1. **`MapConfig.RoadConfig`** — add `lanes` field:

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
private List<LaneConfig> lanes;
```

2. **`MapConfig.LaneConfig`** — new nested class (shown in §4 above).

### Jackson Behaviour Verification

- With `@JsonInclude(NON_NULL)` on a field (not the class), Jackson omits the `"lanes"` key entirely when the value is null. Phase 18 and Phase 23 services will continue producing MapConfig JSON bit-identical to today's output [VERIFIED: Jackson databind 2.17 docs — `@JsonInclude` on field-level overrides class-level default].
- Existing frontend consumers (`MapPage`, `MapSidebar`, any Playwright fixture) read `laneCount` and never `lanes`, so absence is transparent.
- Existing MapLoader (`backend/src/main/java/com/trafficsimulator/config/MapLoader.java`) ignores unknown fields per Jackson default; no change needed.

### MapValidator Behaviour

**No changes required.** Verified by reading `MapValidator.validateRoadConstraints` [VERIFIED: `backend/src/main/java/com/trafficsimulator/config/MapValidator.java:89-103`]:

```java
private void validateRoadConstraints(MapConfig.RoadConfig road, List<String> errors) {
    if (road.getLaneCount() < 1 || road.getLaneCount() > 4) { ... }
    if (road.getLength() <= 0) { ... }
    if (road.getSpeedLimit() <= 0) { ... }
}
```

The validator touches only `laneCount`, `length`, `speedLimit`. No reference to the new `lanes` field means no validation runs on it — exactly the design intent. No tests need updating in `MapValidatorTest` beyond adding a positive test that asserts a MapConfig with `lanes[]` populated still passes validation.

### Builder Updates

`MapConfig.RoadConfig` uses Lombok `@Data` + `@NoArgsConstructor`, which generates getters/setters but no builder. The setter `setLanes(List<LaneConfig>)` is auto-generated. The mapper code above uses `road.setLanes(lanes)` directly. No builder changes needed.

## Testing Strategy

### Fixture Reuse from Phase 23

All 5 Phase 23 OSM XML fixtures are reusable as subprocess input:
- `backend/src/test/resources/osm/straight.osm` [VERIFIED: 269 B, exists]
- `backend/src/test/resources/osm/t-intersection.osm` [VERIFIED: 525 B]
- `backend/src/test/resources/osm/roundabout.osm` [VERIFIED: 1.5 KB]
- `backend/src/test/resources/osm/signal.osm` [VERIFIED: 624 B]
- `backend/src/test/resources/osm/missing-tags.osm` [VERIFIED: 267 B]

**New fixture needed:** `backend/src/test/resources/osm/bike-lane-boulevard.osm` — a 2-way road with explicit `cycleway=lane`, `cycleway:both=lane`, sidewalks, and parking, to exercise the `lanes[]` extraction path. Extract from OSM around a known dense-cycling city (Amsterdam, Copenhagen, Portland Hawthorne Blvd). Keep under 4 KB.

### Canned StreetNetwork JSON Fixtures (new)

To avoid invoking the Rust binary in unit tests, we cache the subprocess output as JSON:

- `backend/src/test/resources/osm2streets/straight-streetnetwork.json` — produced by running `cat backend/src/test/resources/osm/straight.osm | backend/bin/osm2streets-cli-linux-x64 > ...` once and committing the result.
- Same for the 5 other fixtures.

The test then feeds the canned JSON into `StreetNetworkMapper.map(...)` directly — no subprocess spawned. This keeps unit tests offline and deterministic.

### Test Pattern (mirrors `GraphHopperOsmServiceTest`)

```java
@SpringBootTest
class Osm2StreetsServiceTest {
    @Autowired Osm2StreetsService svc;
    @Autowired ObjectMapper om;
    @Autowired MapValidator validator;

    @Test
    void straightRoad_producesValidMapConfig() throws IOException {
        String canned = Files.readString(Path.of(
            "src/test/resources/osm2streets/straight-streetnetwork.json"));
        MapConfig cfg = StreetNetworkMapper.map(om.readTree(canned), bbox(52.1, 21.0, 52.11, 21.01));
        assertThat(validator.validate(cfg)).isEmpty();
        assertThat(cfg.getRoads()).isNotEmpty();
        assertThat(cfg.getRoads().get(0).getLanes()).isNotNull();
    }

    @Test
    void surfacesLaneLevelData() throws IOException {
        MapConfig cfg = /* load bike-lane fixture */;
        List<String> laneTypes = cfg.getRoads().get(0).getLanes().stream()
            .map(MapConfig.LaneConfig::getType).toList();
        assertThat(laneTypes).contains("cycling", "driving", "sidewalk");
    }

    @Test
    void emptyStreetNetwork_throws422() {
        JsonNode empty = om.readTree("{\"roads\":{},\"intersections\":{}}");
        assertThatThrownBy(() -> StreetNetworkMapper.map(empty, bbox))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No roads");
    }
}
```

### Subprocess-spawning smoke test (gated)

Annotated `@EnabledIfSystemProperty(named = "osm2streets.online", matches = "on|true")` so CI stays offline. When set, spawns the actual binary on the straight fixture and asserts non-empty output.

### A/B/C Extension of `OsmPipelineComparisonTest`

```java
@SpringBootTest
@EnabledIfSystemProperty(named = "osm.online", matches = "on|true")
class OsmPipelineComparisonTest {
    @Autowired List<OsmConverter> converters;  // Spring injects all 3

    @Test
    void compareThreeConverters() {
        BboxRequest bbox = warsawBbox();
        for (OsmConverter c : converters) {
            if (!c.isAvailable()) continue;
            MapConfig cfg = c.fetchAndConvert(bbox);
            log.info("{}: {} roads, {} intersections",
                c.converterName(), cfg.getRoads().size(),
                cfg.getIntersections() == null ? 0 : cfg.getIntersections().size());
        }
    }
}
```

Spring autowires `List<OsmConverter>` to pick up all three implementations (Phase 18, 23, 24) — this is standard Spring behaviour and requires no extra annotation once all three are `@Service` beans.

### Playwright Spec (`frontend/e2e/osm-bbox-o2s.spec.ts`)

Mirror `osm-bbox-gh.spec.ts` verbatim; change:
- Button text selector: `'Fetch roads (osm2streets)'`
- Stubbed URL pattern: `**/api/osm/fetch-roads-o2s`
- Fixture path: `fixtures/osm-fetch-roads-o2s-response.json`
- Expected heading: `osm2streets: N roads, M intersections`

The fixture JSON can be the same MapConfig shape as `osm-fetch-roads-response.json` optionally enriched with `lanes[]` on one road to exercise the additive field end-to-end.

## Known Pitfalls

### Pitfall 1: Binary not runnable in Alpine / musl mismatch

**What goes wrong:** Built with musl target on Alpine-based Docker host but backend runs on glibc Ubuntu base → "Exec format error" on `ProcessBuilder.start()`.

**Why it happens:** Different libc ABIs. musl-linked binaries run on both libc flavours; glibc-linked do NOT run on Alpine.

**How to avoid:** Build with `--target x86_64-unknown-linux-musl` so the binary is self-contained. Verify with `file backend/bin/osm2streets-cli-linux-x64` — look for `statically linked`.

**Warning signs:** Exit code 126/127, error stream contains `"cannot execute binary file"` or `"No such file or directory"` (a real libc-mismatch gotcha — the file is there; the dynamic linker isn't).

### Pitfall 2: `@Lazy` + `@Service` loss-of-startup-check

**What goes wrong:** `Osm2StreetsService` constructor fails silently because `@Lazy` defers resolution — first request gets 500 at an awkward code path.

**Why it happens:** Same Phase 23 spike finding A7 — failing `@Service` beans take down the Spring context, so Phase 23 used `@Lazy`.

**How to avoid:** Annotate `Osm2StreetsService` with `@Lazy` (mirrors `GraphHopperOsmService`). Override `isAvailable()` to check `Files.isExecutable(Path.of(config.getBinaryPath()))` so the controller can return 503 gracefully instead of crashing on first request.

**Warning signs:** `/api/osm/fetch-roads` (Phase 18) stops responding after adding Phase 24 → `@Lazy` was forgotten.

### Pitfall 3: Subprocess stdin not closed → parser waits forever

**What goes wrong:** The Rust wrapper reads from stdin with `read_to_end`. If the Java side writes XML but never closes the output stream, the subprocess hangs. The 30s timeout fires but we burn 30 seconds every request.

**Why it happens:** `ProcessBuilder`'s `process.getOutputStream()` returns a stream that must be explicitly closed; leaving it open keeps the subprocess's stdin open.

**How to avoid:** Use `try (var stdin = process.getOutputStream())` so the stream auto-closes. Verified in the invocation snippet in §5.

**Warning signs:** Every request takes exactly 30s and errors with timeout regardless of input size.

### Pitfall 4: `LaneType` / `Direction` JSON field name drift

**What goes wrong:** osm2streets' serde derives produce field names different from our assumption (e.g. `"type": "driving"` vs `"lt": "Driving"`).

**Why it happens:** Rust structs with `#[serde(rename = "...")]` or `#[serde(rename_all = "snake_case")]` attributes change the wire format; the source for these attributes was NOT verified in this research because `osm2lanes/src/lib.rs` responded with 404 / truncated content via WebFetch.

**How to avoid:** In the FIRST implementation task, run the wrapper on `straight.osm` and record the exact JSON shape in the phase SUMMARY.md. The `StreetNetworkMapper` snippet handles BOTH the "Driving" (TitleCase) and "driving" (snake_case) cases defensively via exact-match switch — but the field names `lt`, `dir`, `width` are only confirmed via inference from Rust naming conventions + README text.

**Warning signs:** Every `LaneConfig` in output has `type == "driving"` (fallback case) because the mapper's switch didn't match any variant.

### Pitfall 5: Roundabout classification — osm2streets does NOT expose `junction=roundabout` directly

**What goes wrong:** `IntersectionControl` enum has only Uncontrolled/Signed/Signalled/Construction — no `Roundabout` variant. A roundabout centre node in osm2streets appears as an ordinary `Connection` or `Intersection` kind with uncontrolled control.

**Why it happens:** osm2streets models a roundabout as a loop of road segments, not as a distinct intersection type. The `junction=roundabout` tag lives on the way, not on the intersection.

**How to avoid:** In the mapper, track a `Set<Integer> roundaboutIntersectionIds` by scanning roads that originated from a `junction=roundabout` way (the `highway_type` + original OSM way tags are available via `get_osm_tags_for_way` — but our CLI wrapper doesn't re-expose that). Alternative: retain the original Overpass XML in-memory and run the Phase 18 roundabout detection logic on it, then cross-reference node IDs by WGS84 position. **Simplest MVP:** treat all intersections as PRIORITY / SIGNAL and document that Phase 24 roundabout detection is deferred. This is a visible regression vs Phase 18/23 — flag in SUMMARY.md.

**Warning signs:** Roundabout fixture produces zero ROUNDABOUT intersections in Phase 24 output, while Phase 18 and Phase 23 produce N roundabouts.

### Pitfall 6: StreetNetwork JSON can be multi-MB for small bboxes

**What goes wrong:** osm2streets serializes `debug_steps` and detailed geometry (`PolyLine.pts` arrays) which can balloon JSON to 5–20 MB for a single city-block bbox. Jackson's default buffer is fine but the subprocess → Java pipe can stall on small pipe buffers (64 KB on Linux) if the reader is too slow.

**Why it happens:** Pipe buffer fills up while subprocess is still writing; subprocess blocks on write.

**How to avoid:** Use separate threads for read and write (standard anti-deadlock idiom) OR use `ByteArrayOutputStream` with a dedicated reader thread like the Apache Commons `executor` helper would do. Easier: read stdout in a separate thread, write stdin in a separate thread. Even easier: switch to temp-file input so only stdout is streamed; then the simple `readAllBytes()` pattern is safe.

**Warning signs:** Requests hang forever; thread dump shows threads blocked in `UNIXProcess.waitFor` and `FileInputStream.readBytes`.

### Pitfall 7: osm2streets git dep recompiles from source every `cargo build`

**What goes wrong:** `cargo build` of the wrapper downloads and compiles ~400 transitive crates — takes 3–8 minutes cold. Slows the build loop.

**Why it happens:** osm2streets has deep geo-math dependencies (`geo`, `geom`, `georender-pack`, `polylabel-cmd`, etc.).

**How to avoid:** Warm the Cargo cache in CI with `actions/cache@v4` keyed on `Cargo.lock` hash. For local dev, accept the one-time cost — the binary is then checked in and no one rebuilds until the SHA changes.

**Warning signs:** CI job takes 10+ minutes, mostly spent in "Compiling geo v0.28.0".

### Pitfall 8: osm2streets API drift between SHA pins

**What goes wrong:** Between phase close and a later refresh, the `ImportOptions` struct gains a field or `osm_to_street_network` changes signature. Build fails.

**Why it happens:** osm2streets README explicitly says API is unstable.

**How to avoid:** Pin to a specific SHA. NEVER use `branch = "main"` in Cargo.toml. Record the SHA + commit date in SUMMARY.md.

**Warning signs:** `cargo build` after a year produces compile errors in our wrapper's `main.rs`.

## Code Examples

### Verified externally

osm2streets JS API surface, from `osm2streets-js/src/lib.rs` [VERIFIED: raw.githubusercontent.com]:

```rust
pub fn new(osm_input: &[u8], clip_pts_geojson: &str, input: JsValue)
    -> Result<JsStreetNetwork, JsValue>
pub fn to_json(&self) -> String
pub fn to_geojson_plain(&self) -> String
pub fn to_lane_polygons_geojson(&self) -> String
pub fn to_lane_markings_geojson(&self) -> String
pub fn to_intersection_markings_geojson(&self) -> String
// ... + debug and mutation methods
```

We rely on the equivalent Rust-native path: `streets_reader::osm_to_street_network(&xml, clip, opts)` returns `(StreetNetwork, TimerLog)`; `serde_json::to_string(&network)` produces equivalent JSON to `to_json()`.

### Chicory Example (REJECTED PATH — documented for completeness)

For reference only. NOT the chosen path:

```java
// Source: chicory.dev/docs/usage/wasi/  — hypothetical if osm2streets.wasm were WASI
var wasiOpts = WasiOptions.builder()
    .withStdin(new ByteArrayInputStream(osmXml))
    .withStdout(stdoutSink)
    .withArguments(List.of("osm2streets"))
    .build();
var wasi = WasiPreview1.builder().withOptions(wasiOpts).build();
var store = new Store().addFunction(wasi.toHostFunctions());
store.instantiate("module", Parser.parse(wasmFile));
```

**This does not work for osm2streets-js** because the WASM imports `__wbindgen_describe` etc. from `__wbindgen_placeholder__` — not WASI. Using Chicory here would require reimplementing wasm-bindgen's placeholder imports, which is strictly more work than the Rust wrapper.

### Spring service skeleton

```java
@Service
@Lazy
@RequiredArgsConstructor
@Slf4j
public class Osm2StreetsService implements OsmConverter {
    private final Osm2StreetsConfig config;           // @ConfigurationProperties: binary path, timeout
    private final OverpassXmlFetcher overpassFetcher;  // extracted helper (or reuse GraphHopper's pattern)
    private final ObjectMapper objectMapper;
    private final MapValidator mapValidator;

    @Override public String converterName() { return "osm2streets"; }

    @Override
    public boolean isAvailable() {
        return Files.isExecutable(Path.of(config.getBinaryPath()));
    }

    @Override
    public MapConfig fetchAndConvert(BboxRequest bbox) {
        byte[] xml = overpassFetcher.fetchXml(bbox);
        String json = executeCli(xml);
        JsonNode network = objectMapper.readTree(json);
        MapConfig cfg = StreetNetworkMapper.map(network, bbox);
        List<String> errors = mapValidator.validate(cfg);
        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                "osm2streets output failed validation: " + String.join(", ", errors));
        }
        return cfg;
    }

    // ... executeCli as shown in §5
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Hand-roll Rust → Java FFI via JNI | Subprocess with JSON on pipes | 2020s — Go/Rust CLI norm | Much simpler; portable; zero JVM risk |
| WASM in JVM considered exotic | Chicory 1.7.5 is production-ready for WASI modules | 2025 | Valid for WASI CLI tools, NOT for wasm-bindgen browser modules — critical distinction |
| Require library to publish CLI | Accept writing a thin Rust wrapper | Today | Pragmatic path when upstream hasn't released a CLI |

**Deprecated / outdated:**
- asmble (WASM-in-JVM) — replaced by Chicory
- wasmer-java — older API, less Maven-friendly; Chicory is the 2025 recommendation
- Training-data claims of "osm2streets-cli published binary" — FALSE as of 2026-04-22 [VERIFIED]

## Reuse Strategy — helpers that apply UNCHANGED

From `OsmConversionUtils`, reuse verbatim:

| Helper | Signature | Reuse Justification |
|--------|-----------|---------------------|
| `lonToX(double, double, double)` | projection to canvas X | A/B/C fairness (CONTEXT §3) |
| `latToY(double, double, double)` | projection to canvas Y | Same |
| `haversineMeters(...)` | distance | Used inside `computeWayLength` |
| `computeWayLength(List<double[]>)` | length sum | Needed to length-check each road; mapper converts `center_line.pts` to `List<double[]>` then calls this |
| `speedLimitForHighway(String)` | m/s default | Fallback when osm2streets' `speed_limit` is null |
| `laneCountForWay(Map, String)` | lane count | Not needed — osm2streets provides explicit lane count via `lane_specs_ltr`; skip. Keep the MAX_LANE_COUNT constant for clamping. |
| `buildRoadConfig(...)` | `RoadConfig` assembly | Reuse directly — produces the same shape; mapper calls `road.setLanes(...)` after |
| `buildDefaultSignalPhases(String, List<RoadConfig>)` | phase round-robin | Reuse when `IntersectionControl == Signalled` |
| `collectSpawnPoints`, `collectDespawnPoints` | ENTRY/EXIT generation | Reuse |
| `assembleMapConfig` | final MapConfig construction | Reuse — keeps bbox id format consistent across all 3 converters |
| Constants: `CANVAS_W`, `CANVAS_H`, `CANVAS_PADDING`, `MIN_LANE_COUNT`, `MAX_LANE_COUNT`, `MIN_ROAD_LENGTH_M`, `DEFAULT_SIGNAL_PHASE_MS`, `LANE_WIDTH_BACKEND` | — | Must not drift — A/B/C fairness |

**From `OsmPipelineService` / `GraphHopperOsmService`:**
- **Overpass XML fetch logic** — extract the `fetchFromMirrors` method into a new `OverpassXmlFetcher` helper (~40 LoC) so all three services share it. Or: leave as-is and have `Osm2StreetsService` receive a `GraphHopperOsmService` reference *just* to call a package-private `fetchOverpassXmlToTempFile`. Former is cleaner.
- **`@Lazy` + constructor injection** pattern from `GraphHopperOsmService` class header — copy verbatim.
- **`converterName()` label** — the only new value: `"osm2streets"`.
- **Error taxonomy `@ExceptionHandler` reuse** — `OsmController` already maps `IllegalStateException` → 422 and `RestClientException` → 503. Add one handler for timeout → 504.

## What "Done" Looks Like

1. **Three converters side-by-side**
   - `OsmPipelineService` ("Overpass") — unchanged
   - `GraphHopperOsmService` ("GraphHopper") — unchanged
   - `Osm2StreetsService` ("osm2streets") — new, `@Service`, `@Lazy`, implements `OsmConverter`

2. **Three endpoints working**
   - `POST /api/osm/fetch-roads` — unchanged (byte-for-byte response parity vs pre-Phase-24)
   - `POST /api/osm/fetch-roads-gh` — unchanged
   - `POST /api/osm/fetch-roads-o2s` — new, returns 200 on success, 400 on bad body, 422 on empty, 503 on upstream fail, 504 on timeout

3. **All MapValidator-clean**
   - All three services output MapConfig that passes `MapValidator.validate()` with zero errors
   - `lanes[]` field populated ONLY by osm2streets converter; null elsewhere (Jackson omits via `@JsonInclude(NON_NULL)`)

4. **Test suite green**
   - Backend: 347 (existing) + new tests for `Osm2StreetsServiceTest`, `StreetNetworkMapperTest`, 3 new `OsmControllerTest` methods, 1 new `MapValidatorTest` positive assertion → total ~355–360
   - Frontend: 56 (existing) + maybe 1–2 new vitest unit tests for button state
   - Playwright: 7 (existing) + 1 new → 8 total, all green

5. **Frontend integration**
   - Third button "Fetch roads (osm2streets)" visible in idle sidebar state, ordered after Phase 23's GraphHopper button
   - Click → `POST /api/osm/fetch-roads-o2s` → success path updates sidebar heading with `resultOrigin="osm2streets"` label and `"osm2streets: N roads, M intersections"`
   - Error paths mirror Phase 18/23 exactly

6. **Documentation updated**
   - `backend/docs/osm-converters.md` — remove "(future)" marker on Phase 24 row; document the 3-way architecture; record osm2streets git SHA used to build the binary; document the `lanes[]` field contract
   - `backend/bin/README.md` (new, optional) — how to rebuild the binary, SHA-256 verification steps

7. **Zero regressions**
   - Phase 18 endpoint response is byte-identical before/after Phase 24 (diffable via saved Postman collection)
   - Phase 23 endpoint response is byte-identical before/after
   - Existing test count does not decrease (only grows with new additions)

## Open Questions (3 RESOLVED + 1 DEFERRED-TO-IMPL + 1 ACCEPTED-GAP)

**Q1 [RESOLVED]: Does osm2streets publish a pre-built CLI?**
Answer: **No.** "No releases published" on github.com/a-b-street/osm2streets. No binary on crates.io either. Path A is dead.

**Q2 [RESOLVED]: Can we run osm2streets WASM inside the JVM?**
Answer: **Not with Chicory alone.** `osm2streets-js` is a wasm-bindgen browser-target module requiring `__wbindgen_placeholder__` imports that Chicory does not provide. Using GraalVM polyglot to embed Node.js would require running Node as a subprocess (GraalVM limitation), which degenerates to Path D. Path C is dead.

**Q3 [DEFERRED-TO-IMPL]: What is the exact JSON field naming (`lt` vs `type`, `dir` vs `direction`, `"Driving"` vs `"driving"`) in serialized `LaneSpec`?**
Research could not retrieve `osm2lanes/src/lib.rs` fully (403 response). The mapper is defensively written against multiple shapes; verification is a one-shot print during task 1 of implementation. Record the shape in SUMMARY.md.

**DEFERRED-TO-IMPL:** answer recorded in `24-01-SUMMARY.md` per Plan 24-01 Task 1 (one-shot diagnostic print of `StreetNetwork` JSON during CLI smoke-test).

**Q4 [ACCEPTED-GAP]: Roundabout detection without direct `junction=roundabout` access.**
osm2streets' `IntersectionControl` enum has no Roundabout variant. MVP answer: treat all as PRIORITY/SIGNAL and document as a known gap. If the gap is intolerable, add a spike subtask to either (a) parse `junction` tag from retained Overpass XML in Java and cross-reference by WGS84 position, or (b) extend the Rust wrapper to emit a separate `roundabout_intersection_ids` field by inspecting `highway_type` on adjacent roads.

**ACCEPTED-GAP:** documented in `backend/docs/osm-converters.md` §"Known gap: roundabout detection" per Plan 24-07 Task 1 Step 5. Phase 18/23 still detect ROUNDABOUT; Phase 24 classifies as PRIORITY. Recorded in A/B/C comparison output.

**Q5 [RESOLVED]: Binary size — check in or gitignore?**
Answer: **Check in** up to ~40 MB. `file backend/bin/osm2streets-cli-linux-x64` + SHA-256 verification is cheap; Git LFS and object-storage fetch are more complex for MVP. Add to gitignore + document fetch step only if binary breaches 40 MB.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `LaneSpec` JSON field names are `lt`, `dir`, `width` with variants `"Driving"`, `"Fwd"` etc. | §3 Output Schema, §4 Mapper | Mapper returns empty `lanes[]` for every road. Mitigation: verify in task 1 of implementation; mapper already has defensive fallback to `width.inner` float-or-nested. |
| A2 | `StreetNetwork` derives `Serialize` with default serde config (no `rename_all`) | §3 Output Schema | Roads/intersections JSON keys differ (e.g. `"Roads"` not `"roads"`). Mitigation: lowercase walker in mapper; recognize both cases. |
| A3 | osm2streets compiles cleanly as a library (via `streets_reader::osm_to_street_network`) without the browser-specific wasm-bindgen shim | §5 Rust wrapper | Wrapper build fails. Mitigation: if it fails, depend on an intermediate osm2streets fork that removes wasm-bindgen — but spike task should confirm. |
| A4 | `osm_to_street_network` function exists and returns `(StreetNetwork, TimerLog)` as shown | §5 Rust wrapper | Wrapper fails to build. Mitigation: inspect `streets_reader/src/lib.rs` at pinned SHA before writing wrapper. |
| A5 | Static musl binary built on modern Rust will run on Docker glibc-based images | §9 Pitfall 1 | Runtime exec failure. Mitigation: smoke test `./backend/bin/osm2streets-cli-linux-x64 --help` in the deployment container during CI. |
| A6 | ROUNDABOUT detection via cross-reference from retained Overpass XML is acceptable gap for MVP | §9 Pitfall 5, §12 Q4 | A/B/C comparison shows Phase 24 has 0 roundabouts where Phases 18/23 have N. Mitigation: document in SUMMARY.md; spike for Q4 if gap is bigger than expected. |
| A7 | Jackson's `@JsonInclude(NON_NULL)` on a field-level annotation omits the key entirely, not just value | §7 Jackson Behaviour | Phase 18/23 output includes `"lanes": null` byte-for-byte change. Mitigation: this is well-documented Jackson behaviour [CITED: fasterxml/jackson-databind docs]. |
| A8 | `osm2streets-js` npm version 0.1.4 is the latest as of April 2026 | §Alternatives Considered | None — we're not using osm2streets-js. [CITED: libraries.io search result] |
| A9 | Chicory 1.7.5 is the current version on Maven Central as of April 2026 | §Tool Strategy | None — we're not using Chicory. [VERIFIED: central.sonatype.com/artifact/com.dylibso.chicory/runtime — "29 days ago" → ~March 2026] |
| A10 | The osm2streets demo at a-b-street.github.io actually uses the WASM build of osm2streets-js | §Tool Strategy | None — context only. |

## Sources

### Primary (HIGH confidence)

- **osm2streets GitHub repository** — https://github.com/a-b-street/osm2streets [VERIFIED: README + Releases tab + README subsections + issue navigation]
- **osm2streets-js Cargo.toml** — https://raw.githubusercontent.com/a-b-street/osm2streets/main/osm2streets-js/Cargo.toml [VERIFIED: crate-type cdylib, wasm-bindgen 0.2.84]
- **osm2streets-js lib.rs** — https://raw.githubusercontent.com/a-b-street/osm2streets/main/osm2streets-js/src/lib.rs [VERIFIED: wasm_bindgen browser target, exported methods including to_json()]
- **osm2streets lib.rs** — https://raw.githubusercontent.com/a-b-street/osm2streets/main/osm2streets/src/lib.rs [VERIFIED: StreetNetwork struct definition]
- **osm2streets road.rs** — https://raw.githubusercontent.com/a-b-street/osm2streets/main/osm2streets/src/road.rs [VERIFIED: Road struct definition]
- **osm2streets intersection.rs** — https://raw.githubusercontent.com/a-b-street/osm2streets/main/osm2streets/src/intersection.rs [VERIFIED: Intersection + IntersectionKind + IntersectionControl]
- **Chicory Maven Central** — https://central.sonatype.com/artifact/com.dylibso.chicory/runtime [VERIFIED: v1.7.5, 29 days ago]
- **Chicory WASI docs** — https://chicory.dev/docs/usage/wasi/ [CITED: API usage + stdin/stdout pattern]
- **Project source files** — verified via Read tool:
  - `backend/pom.xml` — Spring Boot 3.3.5, Java 17
  - `backend/src/main/java/com/trafficsimulator/config/MapValidator.java` — confirms `lanes` field is outside validated surface
  - `backend/src/main/java/com/trafficsimulator/service/ClaudeVisionService.java` — ProcessBuilder pattern template
  - `backend/src/main/java/com/trafficsimulator/service/GraphHopperOsmService.java` — `@Lazy` + `isAvailable()` pattern
  - `backend/src/main/java/com/trafficsimulator/service/OsmConversionUtils.java` — shared helpers
  - `backend/docs/osm-converters.md` — architectural context

### Secondary (MEDIUM confidence)

- **Jake Coppinger on osm2streets WASM tileserver** — https://jakecoppinger.com/2023/01/lane-accurate-street-maps-with-openstreetmap-writing-a-vector-tileserver-for-osm2streets/ [CITED: confirms lane-level output, GeoJSON flavour]
- **State of the Map 2022 osm2streets talk PDF** — https://2022.stateofthemap.org/attachments/9NHQQM_osm2streets_SK6LTdl.pdf [CITED: schema overview]
- **InfoQ on Chicory** — https://www.infoq.com/news/2024/05/chicory-wasm-java-interpreter/ [CITED: WASI + native JVM runtime positioning]
- **libraries.io osm2streets-js entry** — https://libraries.io/npm/osm2streets-js [CITED: npm version 0.1.2→0.1.4]

### Tertiary (LOW confidence — single-source findings)

- **LaneSpec field names and enum variants** — inferred from README text + Rust naming convention; direct source file (`osm2streets/src/lanes/mod.rs`) returned 404. Flagged as assumption A1.
- **musl binary runs on Alpine + glibc Docker images identically** — general Rust community knowledge; not verified against our specific deployment base image in this research. Pitfall 1 mitigates with smoke-test guidance.

## Metadata

**Confidence breakdown:**
- Standard stack (Path B subprocess wrapper): **HIGH** — pattern validated by Phase 20 `ClaudeVisionService`; the only novel piece is a 60-line Rust wrapper
- Architecture (service + mapper + controller + config): **HIGH** — identical shape to Phase 23, with substitutions
- Output schema → MapConfig mapping: **MEDIUM** — Rust struct fields VERIFIED; serde-derived JSON key names ASSUMED (needs task-1 verification)
- Pitfalls: **HIGH** for pitfalls 1–4 (process mechanics, well-understood); **MEDIUM** for pitfalls 5–8 (osm2streets-specific behaviour needs one-shot validation)
- Path A/C/D rejection: **HIGH** — all three rejections are supported by verified sources (GitHub releases, Cargo.toml wasm-bindgen deps, GraalVM docs)

**Research date:** 2026-04-22
**Valid until:** 2026-05-22 (osm2streets is pre-1.0 and actively developed; re-verify before any refresh)
