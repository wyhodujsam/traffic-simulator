---
phase: 24-osm2streets-integration
plan: 01
subsystem: infra
tags: [osm, rust, cargo, musl, osm2streets, streetnetwork, binary]

# Dependency graph
requires:
  - phase: 23-graphhopper-based-osm-parser
    provides: 5 OSM XML fixtures under backend/src/test/resources/osm/
    (straight, t-intersection, roundabout, signal, missing-tags)
provides:
  - "tools/osm2streets-cli Rust wrapper crate pinned to osm2streets @ fc119c47dac567d030c6ce7c24a48896f58ed906"
  - "backend/bin/osm2streets-cli-linux-x64 — 3.5 MB static-pie ELF, x86_64-unknown-linux-musl"
  - "backend/bin/osm2streets-cli-linux-x64.sha256 — 9e65769...1a3a98 (verification sidecar)"
  - "6 canned StreetNetwork JSON fixtures under backend/src/test/resources/osm2streets/"
  - "backend/src/test/resources/osm/bike-lane-boulevard.osm — new cycleway-heavy fixture"
  - "Observed JSON shape documented — answers RESEARCH Q3 / Assumption A1"
affects:
  - 24-02-lane-config-schema        # LaneConfig Java DTO mirrors observed lt/dir/width shape
  - 24-03-osm2streets-service       # ProcessBuilder contracts against this binary's stdin/stdout
  - 24-04-streetnetwork-mapper      # Jackson parsing must handle observed field shape exactly
  - 24-07-phase-docs                # backend/docs/osm-converters.md copy-over

# Tech tracking
tech-stack:
  added:
    - "cargo 1.95.0 (Rust stable) — build-time only, not runtime"
    - "osm2streets + streets_reader + abstutil (pinned git deps, no crates.io release)"
    - "x86_64-unknown-linux-musl rustup target"
  patterns:
    - "Pre-built static binary + ProcessBuilder subprocess (Path B from RESEARCH, mirrors Phase 20 ClaudeVisionService)"
    - "Git SHA pinning for unstable upstream libraries (no semver, no releases)"
    - "Dual-mode CLI: --input/--output flags OR stdin/stdout pipe — same binary handles both"
    - "Canned subprocess-output fixtures committed alongside source fixtures — test pyramid stays unit-level, no subprocess spawn in CI"

key-files:
  created:
    - "tools/osm2streets-cli/Cargo.toml"
    - "tools/osm2streets-cli/Cargo.lock"
    - "tools/osm2streets-cli/src/main.rs"
    - "tools/osm2streets-cli/README.md"
    - "backend/bin/osm2streets-cli-linux-x64"
    - "backend/bin/osm2streets-cli-linux-x64.sha256"
    - "backend/bin/README.md"
    - "backend/src/test/resources/osm/bike-lane-boulevard.osm"
    - "backend/src/test/resources/osm2streets/straight-streetnetwork.json"
    - "backend/src/test/resources/osm2streets/t-intersection-streetnetwork.json"
    - "backend/src/test/resources/osm2streets/roundabout-streetnetwork.json"
    - "backend/src/test/resources/osm2streets/signal-streetnetwork.json"
    - "backend/src/test/resources/osm2streets/missing-tags-streetnetwork.json"
    - "backend/src/test/resources/osm2streets/bike-lane-streetnetwork.json"
    - ".gitignore"

key-decisions:
  - "Pinned osm2streets + streets_reader to git SHA fc119c47dac567d030c6ce7c24a48896f58ed906 (HEAD as of 2026-04-22) for reproducible rebuilds; no crates.io release exists."
  - "Built with x86_64-unknown-linux-musl target (static-pie, 3.5 MB); did NOT need the glibc+crt-static fallback because cargo's bundled linker succeeded without musl-gcc."
  - "Applied Transformation::standard_for_clipped_areas inside the wrapper so consumers see the same simplified network osm2streets-js produces (collapse short roads + degenerate intersections, run twice)."
  - "Binary supports both --input/--output flags AND stdin/stdout pipes — reconciles the two different contracts between plan frontmatter (flag-based) and plan body's <interfaces> block (stdin/stdout for Plan 24-03)."
  - "Committed Cargo.lock for transitive-dep pinning; gitignored tools/osm2streets-cli/target/ build cache only."

patterns-established:
  - "GIT_SHA-pinned Rust deps: when an upstream crate has no semver release, pin a 40-char SHA + commit Cargo.lock; document the SHA + date in both Cargo.toml and backend/bin/README.md so future bumps are deliberate."
  - "Canned-subprocess-output fixtures: run the CLI once per input fixture, commit the JSON output alongside the input .osm so unit tests skip the subprocess (deterministic, offline, fast)."

requirements-completed: []

# Metrics
duration: ~5min (setup) + 3m50s (cargo build) + ~2min (fixtures + docs)
completed: 2026-04-22
---

# Phase 24 Plan 01: osm2streets Rust Wrapper + Static Binary + 6 Canned Fixtures Summary

**Thin Rust CLI wrapping A/B Street's osm2streets (pinned git SHA), built as a 3.5 MB static-pie musl binary under backend/bin/, paired with 6 canned StreetNetwork JSON fixtures that capture the actual serde output shape — answering RESEARCH Q3 with ground truth for the Phase 24-04 Java mapper.**

## Performance

- **Duration:** ~10 min wall-clock (cargo cold build dominated at 3m50s)
- **Started:** 2026-04-22T09:00:00Z (approx)
- **Completed:** 2026-04-22T09:10:00Z (approx)
- **Tasks:** 2 of 2
- **Files created:** 15 (2 commits)

## Accomplishments

- Rust wrapper crate (`tools/osm2streets-cli`) with `osm2streets` + `streets_reader` + `abstutil` dependencies pinned to 40-char git SHA `fc119c47dac567d030c6ce7c24a48896f58ed906` (HEAD as of 2026-04-22).
- Static `x86_64-unknown-linux-musl` binary at `backend/bin/osm2streets-cli-linux-x64` (3.5 MB stripped), verified `static-pie linked` by `file(1)`, SHA-256 sidecar for integrity checks.
- Six canned StreetNetwork JSON fixtures committed under `backend/src/test/resources/osm2streets/` — one per Phase 23 OSM input plus the new `bike-lane-boulevard.osm`.
- New OSM fixture `backend/src/test/resources/osm/bike-lane-boulevard.osm` — bidirectional tertiary with `cycleway:both=lane`, `sidewalk=both`, `parking:both=lane` — produces a 10-lane cross-section in osm2streets (richest LaneSpec coverage for mapper tests).
- Documented actual StreetNetwork JSON shape (see below) so Plan 24-04 mapper has concrete ground truth, not assumption A1.
- `backend/bin/README.md` captures provenance: git SHA pin, build command, SHA-256 verification, Docker `COPY --chmod=755` note.

## Task Commits

1. **Task 1: Toolchain gate + Rust wrapper crate + binary build** — `dd73134` (feat)
   - Wrapper Cargo.toml/Cargo.lock/main.rs/README + backend/bin/osm2streets-cli-linux-x64 + .sha256 + backend/bin/README + .gitignore
2. **Task 2: Smoke-run + record JSON shape + seed 6 canned fixtures + bike-lane OSM** — `f419aab` (test)
   - backend/src/test/resources/osm/bike-lane-boulevard.osm + 6 JSON fixtures

**Plan metadata commit:** created atomically with this SUMMARY.md (see final commit hash below).

## Observed StreetNetwork JSON Shape — Answer to RESEARCH Q3 / Assumption A1

**Captured by running `osm2streets-cli-linux-x64` on the 6 OSM fixtures at commit SHA `fc119c47dac567d030c6ce7c24a48896f58ed906`.** This section is load-bearing for Plan 24-04 — the Java mapper must match these names exactly.

### Top-level StreetNetwork

```json
{
  "roads":              [ [id, Road], ... ],         // BTreeMap serialized as array of [key, value] pairs
  "intersections":      [ [id, Intersection], ... ], // same tuple-array shape
  "boundary_polygon":   { "rings": [ { "pts": [...], "tessellation": null } ] },
  "gps_bounds":         { "min_lon": …, "min_lat": …, "max_lon": …, "max_lat": … },
  "config":             { /* MapConfig, unrelated to ours */ },
  "intersection_id_counter": <int>,   // private field — still serialized (no skip attribute)
  "road_id_counter":    <int>         // same
}
```

- **`debug_steps` is absent** — `#[serde(skip_serializing)]` in upstream; keeps fixtures lean (RESEARCH §Pitfall 6 mitigated automatically).
- **`roads` / `intersections` are NOT objects keyed by string ID** as the RESEARCH JSON example assumed. They are **arrays of `[integer_id, value]` tuples** because osm2streets uses a custom `serialize_btreemap` serializer. The Java mapper must iterate the array and treat each element as a tuple, not call `.fields()` on a JSON object.

### Road fields

Every Road object contains:

```
id, osm_ids[], src_i, dst_i, highway_type, name,
internal_junction_road, layer, speed_limit, reference_line,
reference_line_placement, center_line, trim_start, trim_end,
turn_restrictions[], complicated_turn_restrictions[],
lane_specs_ltr[], stop_line_start, stop_line_end
```

- `src_i` / `dst_i` are **integers** (intersection IDs indexing the `intersections` tuple-array by `.0`).
- `highway_type` is a plain string (`"residential"`, `"primary"`, `"tertiary"`, `"secondary"`).
- `name` is `string | null`.
- `speed_limit` is `i32 | null` (scaled integer, see "Geom scaling" below) — `null` when OSM `maxspeed` tag is absent. NOT a nested `{inner: <f64>}` object as assumption A1 feared.
- `reference_line.pts` and `center_line.pts` are arrays of `{"x": <i32>, "y": <i32>}` in **internal map units**, NOT `[lat, lon]`. See "Geom scaling" below.
- `reference_line_placement` is a tagged enum: e.g. `{"Consistent": "Center"}` or `{"Varies": [...]}`.
- `stop_line_start` / `stop_line_end` are objects `{vehicle_distance, bike_distance, interruption}` — NOT the `{"kind": "None"}` shape the RESEARCH example showed.
- `trim_start` / `trim_end` are flat integers (scaled).
- `turn_restrictions` and `complicated_turn_restrictions` are arrays (usually empty for our test fixtures).

### LaneSpec (`lane_specs_ltr[]`) — THE PAYLOAD

```json
[
  { "lt": "Driving",  "dir": "Forward",  "width": 30000, "allowed_turns": 0, "lane": { … } },
  { "lt": {"Parking": "Parallel"}, "dir": "Backward", "width": 30000, "allowed_turns": 0, "lane": { … } },
  { "lt": {"Buffer": "Curb"},      "dir": "Backward", "width": 1000,  "allowed_turns": 0, "lane": { … } },
  { "lt": "Biking",   "dir": "Backward", "width": 15000, … },
  { "lt": "Sidewalk", "dir": "Forward",  "width": 15000, … }
]
```

**Field name verdict (Q3 FINAL ANSWER):**

| RESEARCH assumption A1 | OBSERVED | Outcome |
|------------------------|----------|---------|
| `type` or `lt`?        | **`lt`** (bare short form) | A1 option-B confirmed |
| `direction` or `dir`?  | **`dir`** (bare short form) | A1 option-B confirmed |
| `width` flat or nested?| **flat `i32` (scaled, see below)** | A1 "plain float" WRONG — it's a scaled i32 |
| Variant casing?        | **TitleCase** (`"Driving"`, `"Sidewalk"`, `"Biking"`, `"Forward"`, `"Backward"`, `"Both"`) | A1 TitleCase confirmed |
| `Direction` values?    | **`Forward` / `Backward` / `Both`** | RESEARCH-hypothesised `Fwd`/`Back` WRONG |

**Tagged-enum variants (NEW finding not in RESEARCH):**

Some `LaneType` variants carry a payload and serialize as `{VariantName: payload}` objects:

- Plain string: `"Driving"`, `"Biking"`, `"Sidewalk"`, `"Shoulder"`, `"SharedLeftTurn"`, `"Construction"`, `"LightRail"`, `"Footway"`, `"Bus"` (unit variants)
- Tagged object: `{"Parking": "Parallel"}` (also `"Diagonal"`, `"Perpendicular"`), `{"Buffer": "Curb"}` (also `"Stripes"`, `"Grass"`, ...)

**Mapper impact:** the Java `StreetNetworkMapper` (Plan 24-04) must test for both:

```java
JsonNode lt = lane.path("lt");
String rawType = lt.isTextual() ? lt.asText()
                : lt.fieldNames().hasNext() ? lt.fieldNames().next() : "";
```

Everything else in the mapper template in `24-RESEARCH.md` §4 (the `switch` on rawType mapping `Driving/Parking/Biking/Sidewalk`) still works once `rawType` is normalised.

### Geom scaling — the i32 convention

**CRITICAL.** `geom::Distance` and all coordinate fields use a custom serializer:

- Rust `f64` in meters → serialized as `i32 = (value * 10_000).round()`
- Deserializer does the inverse: `f64 = i32 / 10_000.0`
- Source: `geom/src/lib.rs` `serialize_f64` — https://github.com/a-b-street/geom

Every distance / coordinate / speed in the JSON is therefore a scaled integer:

- `width: 30000` → 3.0000 m (standard driving lane)
- `width: 15000` → 1.5000 m (cycle / sidewalk)
- `width: 1000`  → 0.1000 m (curb buffer)
- `speed_limit: 83333` → 8.3333 m/s ≈ 30 km/h (from `maxspeed=30`)
- `reference_line.length: 1304026` → 130.4026 m
- `pts[].x` / `pts[].y` → distance in meters × 10 000 from the NW corner of `gps_bounds`

**Mapper impact:** the Plan 24-04 mapper must divide by 10 000 before comparing to MapValidator limits (speed in m/s, lane width in m).

### Intersection fields

```json
{
  "id":      <int>,
  "osm_ids": [ <int>, ... ],
  "polygon": { "rings": [ { "pts": [ {x, y}, ... ], "tessellation": null } ] },
  "kind":    "Terminus" | "MapEdge" | "Connection" | "Fork" | "Intersection",
  "control": "Uncontrolled" | "Signed" | "Signalled" | "Construction",
  "roads":   [ <road_id>, ... ],
  "movements": [ [ <road_id>, <road_id> ], ... ],
  "crossing":    null | { ... },
  "trim_roads_for_merging": [ [ [<road_id>, <bool>], {x, y} ], ... ]
}
```

Notable findings:

- `control` for our signal fixture emits `"Signalled"` on the signal-lit node (traffic_signals tag was honoured).
- **`Signed` is the default**, not `Uncontrolled`. Every intersection from a road end in the `signal.osm` and `roundabout.osm` fixtures ships with `control: "Signed"`. Plan 24-04 mapper should treat `Signalled → SIGNAL` and everything else → `PRIORITY` (roundabout detection uses the original OSM `junction=roundabout` tag at Overpass time, not the osm2streets control field — matches RESEARCH §4 accepted gap).
- `kind == "Terminus"` marks dead-end / bbox-edge nodes; the mapper converts these to ENTRY/EXIT spawn/despawn points, not IntersectionConfigs (consistent with RESEARCH §4).
- `polygon` is **always present** (even for degenerate Terminus nodes) — cannot be treated as optional.

### Per-fixture regression baseline

Exact road / intersection counts for every fixture, captured by running the binary once and committing the JSON. Future Rust SHA bumps must preserve these counts (or the Plan 24-04 tests break):

| Fixture                    | Roads | Intersections | Size  | Notes                                                     |
|----------------------------|------:|--------------:|------:|-----------------------------------------------------------|
| `straight.osm`             |     1 |             2 | 2.5 KB | 1 way, 2 endpoints (both Terminus)                       |
| `t-intersection.osm`       |     3 |             4 | 5.5 KB | 3 ways, 3 Terminus + 1 Intersection                      |
| `roundabout.osm`           |     8 |             8 | 12.6 KB | 4 ring + 4 arm ways; 4 Intersection + 4 Terminus         |
| `signal.osm`               |     4 |             5 | 7.4 KB | 4 ways; 4 Terminus + 1 Intersection(Signalled)           |
| `missing-tags.osm`         |     0 |             0 | 620 B  | **Empty case** — no drivable way tag; mapper throws 422 |
| `bike-lane-boulevard.osm`  |     1 |             2 | 5.1 KB | 10-lane cross-section (`Sidewalk/Buffer/Parking/Biking/Driving` x 2) |

**Empty case note:** `missing-tags.osm` produces `{"roads":[],"intersections":[], …}`. This is the fixture that exercises `StreetNetworkMapper.map` → `IllegalStateException("No roads found in selected area")` → HTTP 422 in Plan 24-04.

## Files Created/Modified

- `tools/osm2streets-cli/Cargo.toml` — pinned git-SHA deps + release profile (LTO + strip + opt-level=3)
- `tools/osm2streets-cli/Cargo.lock` — 2 045 lines, full transitive-dep pinning
- `tools/osm2streets-cli/src/main.rs` — ~100 lines; dual-mode (--input/--output + stdin/stdout) + `osm_to_street_network` call + `Transformation::standard_for_clipped_areas` + JSON emit
- `tools/osm2streets-cli/README.md` — build recipes (musl preferred, glibc+crt-static fallback), protocol, SHA pin
- `backend/bin/osm2streets-cli-linux-x64` — 3.5 MB static-pie ELF, musl libc
- `backend/bin/osm2streets-cli-linux-x64.sha256` — `9e657692510b118ee730bd8c1d27b22abb557b36df6241f80ae3ab56c41a3a98`
- `backend/bin/README.md` — provenance, SHA pin, rebuild recipe, Docker COPY note
- `backend/src/test/resources/osm/bike-lane-boulevard.osm` — 10-lane cross-section fixture (702 B)
- `backend/src/test/resources/osm2streets/{straight,t-intersection,roundabout,signal,missing-tags,bike-lane}-streetnetwork.json` — 6 canned subprocess outputs
- `.gitignore` — new at repo root; excludes `tools/osm2streets-cli/target/` only

## Decisions Made

1. **Git SHA pin `fc119c47dac567d030c6ce7c24a48896f58ed906`** chosen as current HEAD of `main` on 2026-04-22. Alternatives (a tagged release, a branch ref) were rejected: osm2streets publishes neither tags nor stable branch names (RESEARCH §Standard Stack).
2. **Build target `x86_64-unknown-linux-musl`** chosen despite `musl-gcc` not being available on the build host — cargo's bundled linker accepted the target without the external tool. The glibc + `+crt-static` fallback documented in the toolchain_environment block was **not needed**. Musl output works on both glibc and musl runtime hosts (RESEARCH §Pitfall 1).
3. **Apply `Transformation::standard_for_clipped_areas` inside the wrapper.** This runs `CollapseShortRoads → CollapseDegenerateIntersections → CollapseShortRoads`, matching what `osm2streets-js` produces for small bboxes. Without this, the Java mapper would see every trivial OSM node as a distinct intersection — fine for the smallest fixtures but problematic for real Overpass payloads.
4. **Dual-mode CLI** (both `--input/--output` flags AND stdin/stdout). The plan frontmatter lists the `--input/--output` success criterion (top-level prompt), while the plan body's `<interfaces>` block specifies stdin/stdout is what Plan 24-03 will contract against. Supporting both in one binary satisfies both contracts; the implementation cost is ~30 LoC of arg-parsing.
5. **Commit `Cargo.lock` but gitignore `target/`**. Standard Rust convention for binary crates (not libraries). Reproducibility without the 4 GB build cache in git.
6. **No `jq` strict validation in the CLI itself.** The binary emits whatever `serde_json::to_string(&network)` produces; downstream `jq` validation in tests catches malformed JSON if serde ever regresses.
7. **Fixture naming:** plan frontmatter says `bike-lane-boulevard.osm` → `bike-lane-streetnetwork.json` (drop the `-boulevard` suffix on the JSON side per `files_modified` list). The top-level prompt's success_criteria mentions `bike-lane.osm` — interpreted as an abbreviation for the same file; followed the plan frontmatter which is the authoritative execution spec.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 — Blocking] osm2streets API drift vs RESEARCH §5 template**

- **Found during:** Task 1, before the first cargo build, while reading the upstream source at the pinned SHA.
- **Issue:** RESEARCH §5 template calls `osm_to_street_network(&xml, clip_geojson, opts)` with `opts: ImportOptions` and expects a `(StreetNetwork, TimerLog)` return. At the pinned SHA (`fc119c47dac567d030c6ce7c24a48896f58ed906`) the actual signature is:
  ```rust
  pub fn osm_to_street_network(
      input_bytes: &[u8],
      clip_pts: Option<Vec<LonLat>>,
      cfg: MapConfig,
      timer: &mut Timer,
  ) -> Result<(StreetNetwork, Document)>
  ```
  Neither `ImportOptions` nor the `(StreetNetwork, _timer_log)` tuple exist. RESEARCH §5 tagged this block [ASSUMED] and the plan's §Contingency (A3/A4) explicitly authorised minimal call-site adjustment.
- **Fix:** Wrapper passes `MapConfig::default()` + a `Timer::throwaway()`; destructures the result as `(mut network, _doc)`. Also added `abstutil = { git = "..." }` to Cargo.toml for the `Timer` type.
- **Files modified:** `tools/osm2streets-cli/Cargo.toml`, `tools/osm2streets-cli/src/main.rs`.
- **Verification:** cargo build succeeded; binary emits valid JSON on all 6 fixtures.
- **Committed in:** `dd73134` (Task 1 commit).

**2. [Rule 2 — Missing Critical] Transformation pass added to wrapper**

- **Found during:** Task 1, while reading osm2streets upstream docs on the pinned SHA.
- **Issue:** The RESEARCH §5 template calls `osm_to_street_network` and immediately serializes the result. The upstream docstring says: *"You probably want to do `StreetNetwork::apply_transformations` on the result to get a useful result."* Without it, trivial OSM graphs produce one tiny intersection per node (fine for `straight.osm`, bad for real-world bbox payloads).
- **Fix:** Call `network.apply_transformations(Transformation::standard_for_clipped_areas(), &mut timer)` before serializing. Matches what `osm2streets-js` does for small bboxes.
- **Files modified:** `tools/osm2streets-cli/src/main.rs`.
- **Verification:** Counts in fixtures match expected collapsed graph (e.g. `roundabout.osm` collapses to 8 roads / 8 intersections rather than 12/12).
- **Committed in:** `dd73134` (Task 1 commit).

**3. [Rule 3 — Blocking] Added `abstutil` dep for `Timer`**

- **Found during:** Task 1, compile.
- **Issue:** `abstutil::Timer` is not re-exported by osm2streets or streets_reader; cargo needs a direct dep.
- **Fix:** Added `abstutil = { git = "https://github.com/a-b-street/abstreet" }` to Cargo.toml. Consistent with how the osm2streets workspace itself declares it.
- **Files modified:** `tools/osm2streets-cli/Cargo.toml`.
- **Verification:** Compile succeeded.
- **Committed in:** `dd73134`.

**4. [Rule 2 — Missing Critical] Root `.gitignore` created**

- **Found during:** Task 1, Step 6.
- **Issue:** Repo had no root `.gitignore` (only `frontend/.gitignore` and `.idea/.gitignore`). Without one, `tools/osm2streets-cli/target/` (a multi-GB build cache) would be committed.
- **Fix:** Created a minimal root `.gitignore` with the single `tools/osm2streets-cli/target/` rule — scope-limited, does not touch any other ignore pattern.
- **Files modified:** `.gitignore` (new).
- **Verification:** `git status` shows `target/` as ignored; `Cargo.lock` and the binary are not ignored.
- **Committed in:** `dd73134`.

---

**Total deviations:** 4 auto-fixed (1 × Rule 2 critical-functionality, 1 × Rule 2 critical-functionality, 2 × Rule 3 blocking). No Rule 4 architectural questions raised; no scope creep.
**Impact on plan:** All four deviations were strictly necessary to compile and produce correct output. Documented fully above so future SHA bumps can compare.

## Issues Encountered

1. **`file(1)` output says `static-pie linked`, not `statically linked`.** The plan's `<automated>` verify regex in Task 1 was `grep -q "statically linked"`. Modern musl targets produce position-independent static executables, so the string differs. Manually verified with `grep -qE "static-pie linked|statically linked"` — both forms indicate a self-contained binary. Fixture counts and binary execution confirm the target semantics (no dynamic libc dep).
2. **`rtk proxy curl` needed for GitHub raw file reads.** The RTK token killer truncated long files silently; switching to `rtk proxy curl` bypassed the filter and gave the full upstream source for API verification.
3. **rtk hook modified some git output unexpectedly.** For instance `git status --short` sometimes prefixed "ok" text; the intent is still discernible from file listings. No functional impact.

## User Setup Required

None — build is fully self-contained on a host with:
- `cargo` (Rust stable 1.80+) on `PATH`
- `rustup target add x86_64-unknown-linux-musl` (one-off, ~30 s)
- Network egress to `github.com` during `cargo build` (osm2streets deps are fetched from git)

At runtime (when Plan 24-03 wires `ProcessBuilder`) the backend only needs the committed binary — no Rust toolchain required.

## Next Phase Readiness

- **Plan 24-02** (LaneConfig schema): can now commit the Java `LaneConfig(type, width, direction)` record matching the observed lane shape — every field name is known exactly.
- **Plan 24-03** (Osm2StreetsService scaffold): binary is in place under `backend/bin/`; `ProcessBuilder` pattern can contract against the stdin/stdout protocol verified in §Smoke test.
- **Plan 24-04** (StreetNetworkMapper): the 6 canned JSONs are the test inputs; see §Observed StreetNetwork JSON Shape for ground-truth field names, i32 scaling convention, and tagged-enum shape that the Jackson parser must handle.

No blockers. No STATE.md or ROADMAP.md edits per parallel-execution contract.

## Self-Check: PASSED

Automated verification after writing this SUMMARY:

- `test -x backend/bin/osm2streets-cli-linux-x64` — OK (executable)
- `file` reports `ELF 64-bit … static-pie linked, stripped` — OK
- `sha256sum -c backend/bin/osm2streets-cli-linux-x64.sha256` — OK
- `tools/osm2streets-cli/Cargo.toml` grep for `osm2streets\s*=\s*\{\s*git\s*=\s*"…",\s*rev\s*=\s*"[0-9a-f]{40}"` — OK (matches `fc119c47dac567d030c6ce7c24a48896f58ed906`)
- `tools/osm2streets-cli/Cargo.lock` present, 2 045 lines — OK
- `backend/bin/README.md` present — OK
- `.gitignore` contains `tools/osm2streets-cli/target/` — OK
- All 6 fixtures under `backend/src/test/resources/osm2streets/` jq-valid (`has("roads") and has("intersections")` returns `true`) — OK
- `backend/src/test/resources/osm/bike-lane-boulevard.osm` exists (702 B) — OK
- Commit `dd73134` (Task 1) present in git log — OK
- Commit `f419aab` (Task 2) present in git log — OK

---
*Phase: 24-osm2streets-integration*
*Plan: 01 (Wave 1, parallel to 24-02)*
*Completed: 2026-04-22*
