# backend/bin — vendored native binaries

This directory hosts platform-specific binaries that the backend invokes via
`ProcessBuilder`. They are checked in (not downloaded at build time) so
deployments are reproducible and do not require network egress to build hosts.

## osm2streets-cli-linux-x64

Lane-level OSM → StreetNetwork converter (Phase 24). Wrapping Rust CLI lives at
`tools/osm2streets-cli/` in this repo; this binary is its `cargo build
--release` artefact.

### Provenance

| Item | Value |
|------|-------|
| Wrapper source | `tools/osm2streets-cli/src/main.rs` |
| Upstream library | `github.com/a-b-street/osm2streets` |
| Pinned git SHA | `fc119c47dac567d030c6ce7c24a48896f58ed906` (HEAD on 2026-04-22) |
| Companion crate | `streets_reader` (same SHA) |
| Target triple | `x86_64-unknown-linux-musl` (static-pie, no dynamic libc dep) |
| File reported by `file` | `ELF 64-bit LSB pie executable, x86-64, version 1 (SYSV), static-pie linked, stripped` |
| Size | 3.5 MB stripped (well under CONTEXT D9's 40 MB commit threshold) |
| Built with | cargo 1.95.0, stable toolchain |

### SHA-256

```
9e657692510b118ee730bd8c1d27b22abb557b36df6241f80ae3ab56c41a3a98  osm2streets-cli-linux-x64
```

Verify with `sha256sum -c osm2streets-cli-linux-x64.sha256`.

### Rebuild recipe

Preferred — static musl (what shipped):

```bash
cd tools/osm2streets-cli
rustup target add x86_64-unknown-linux-musl
cargo build --release --target x86_64-unknown-linux-musl
cp target/x86_64-unknown-linux-musl/release/osm2streets-cli \
   ../../backend/bin/osm2streets-cli-linux-x64
chmod +x ../../backend/bin/osm2streets-cli-linux-x64
sha256sum ../../backend/bin/osm2streets-cli-linux-x64 \
   > ../../backend/bin/osm2streets-cli-linux-x64.sha256
```

Fallback (glibc + `crt-static`) if the build host lacks the musl target and
`musl-tools` cannot be installed (see tools/osm2streets-cli/README.md).

Cold build pulls ~400 crates and takes 3-8 minutes. Re-pinning the SHA requires
bumping Cargo.toml and regenerating Cargo.lock — do so deliberately.

### Docker COPY note

When this binary is copied into a container image, preserve the executable bit:

```dockerfile
COPY --chmod=755 backend/bin/osm2streets-cli-linux-x64 /app/bin/osm2streets-cli-linux-x64
```

A musl-linked static binary runs on both glibc- and musl-based images (Ubuntu,
Debian, Alpine) without further dynamic-linker plumbing (RESEARCH §Pitfall 1).

### Invocation

The binary supports both `--input`/`--output` flags and stdin/stdout pipes. The
Java backend (`Osm2StreetsService`, arriving in plan 24-03) uses stdin/stdout.

```bash
# via flags
backend/bin/osm2streets-cli-linux-x64 \
    --input backend/src/test/resources/osm/straight.osm \
    --output /tmp/sn.json

# via pipe
cat backend/src/test/resources/osm/straight.osm \
    | backend/bin/osm2streets-cli-linux-x64 \
    > /tmp/sn.json
```
