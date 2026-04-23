# osm2streets-cli

Thin Rust wrapper around [A/B Street's `osm2streets`](https://github.com/a-b-street/osm2streets) —
produces lane-level `StreetNetwork` JSON from OSM XML. Invoked by the Java backend via
`ProcessBuilder` (Phase 24 — see `.planning/phases/24-*/24-RESEARCH.md`).

## Protocol

Dual-mode invocation (same binary handles both):

| Mode | Input | Output |
|------|-------|--------|
| Flag-driven (plan 24-01) | `--input <path>` to an `.osm` file | `--output <path>` for the JSON |
| Pipe (plan 24-03 default) | OSM XML on stdin | StreetNetwork JSON on stdout |

Any mixture works (e.g. `--input straight.osm` on stdin-less invocation + stdout). `stderr`
is reserved for log lines; Java ignores it unless the process exits non-zero.

Exit codes:

- `0` — success, output is a valid StreetNetwork JSON
- non-zero — failure; reason printed on stderr

## Pinned dependency

`osm2streets` has no crates.io release and no tagged versions. We pin to a specific 40-char
SHA in `Cargo.toml`; `Cargo.lock` pins the entire transitive graph:

```
osm2streets    rev = "fc119c47dac567d030c6ce7c24a48896f58ed906"  # 2026-04-22
streets_reader rev = "fc119c47dac567d030c6ce7c24a48896f58ed906"  # same SHA
```

Bump only via deliberate review (osm2streets' API is unstable; RESEARCH §Pitfall 8).

## Building the binary

```bash
# preferred: static musl binary (runs on both glibc + musl hosts)
cd tools/osm2streets-cli
rustup target add x86_64-unknown-linux-musl   # one-off
cargo build --release --target x86_64-unknown-linux-musl
cp target/x86_64-unknown-linux-musl/release/osm2streets-cli \
   ../../backend/bin/osm2streets-cli-linux-x64
chmod +x ../../backend/bin/osm2streets-cli-linux-x64
```

Fallback if `musl-gcc` / `musl-tools` is unavailable on the build host (no sudo possible):

```bash
cd tools/osm2streets-cli
RUSTFLAGS="-C target-feature=+crt-static" cargo build --release
cp target/release/osm2streets-cli \
   ../../backend/bin/osm2streets-cli-linux-x64
chmod +x ../../backend/bin/osm2streets-cli-linux-x64
```

The glibc+crt-static variant is compatible with any modern glibc-based Linux dev /
deployment host (Ubuntu 22.04+, Debian 12+) and is what Phase 24-01 actually shipped.

Cold build pulls ~400 crates and takes 3-8 minutes; subsequent rebuilds are seconds.

## Smoke test

```bash
backend/bin/osm2streets-cli-linux-x64 \
    --input backend/src/test/resources/osm/straight.osm \
    --output /tmp/sn.json
jq '.roads | length' /tmp/sn.json   # expect > 0

# Or via stdin/stdout:
cat backend/src/test/resources/osm/straight.osm \
    | backend/bin/osm2streets-cli-linux-x64 \
    | jq '.roads | length'
```

## Verification

Use the SHA-256 file alongside the checked-in binary:

```bash
sha256sum -c backend/bin/osm2streets-cli-linux-x64.sha256
```
