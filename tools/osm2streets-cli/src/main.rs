//! osm2streets-cli — thin wrapper around A/B Street's `osm2streets` library.
//!
//! Protocol (dual-mode: plan 24-01 `--input`/`--output` + plan 24-03 stdin/stdout):
//!   * If `--input <path>` is supplied, read OSM XML from that file; otherwise read from stdin.
//!   * If `--output <path>` is supplied, write StreetNetwork JSON to that file; otherwise stdout.
//!   * stderr is reserved for log lines; Java side ignores it unless exit != 0.
//!   * Exit 0 on success; non-zero with error on stderr for any failure.
//!
//! Build (from `tools/osm2streets-cli/`):
//!   cargo build --release --target x86_64-unknown-linux-musl   # preferred static
//!   RUSTFLAGS="-C target-feature=+crt-static" cargo build --release  # fallback when musl-tools missing

use std::fs;
use std::io::{self, Read, Write};
use std::path::PathBuf;

use anyhow::{Context, Result};
use osm2streets::{MapConfig, Transformation};
use streets_reader::osm_to_street_network;
use abstutil::Timer;

struct Args {
    input: Option<PathBuf>,
    output: Option<PathBuf>,
}

fn parse_args() -> Result<Args> {
    let mut input: Option<PathBuf> = None;
    let mut output: Option<PathBuf> = None;
    let mut it = std::env::args().skip(1);
    while let Some(arg) = it.next() {
        match arg.as_str() {
            "--input" | "-i" => {
                let v = it.next().context("--input requires a path")?;
                input = Some(PathBuf::from(v));
            }
            "--output" | "-o" => {
                let v = it.next().context("--output requires a path")?;
                output = Some(PathBuf::from(v));
            }
            "--help" | "-h" => {
                eprintln!("usage: osm2streets-cli [--input <path>] [--output <path>]");
                eprintln!("  defaults to stdin/stdout when flags omitted");
                std::process::exit(0);
            }
            "--version" | "-V" => {
                println!("osm2streets-cli {}", env!("CARGO_PKG_VERSION"));
                std::process::exit(0);
            }
            other => anyhow::bail!("unknown argument: {}", other),
        }
    }
    Ok(Args { input, output })
}

fn read_input(path: Option<&PathBuf>) -> Result<Vec<u8>> {
    match path {
        Some(p) => fs::read(p).with_context(|| format!("reading OSM XML from {}", p.display())),
        None => {
            let mut buf = Vec::new();
            io::stdin().read_to_end(&mut buf).context("reading OSM XML from stdin")?;
            Ok(buf)
        }
    }
}

fn write_output(path: Option<&PathBuf>, payload: &str) -> Result<()> {
    match path {
        Some(p) => fs::write(p, payload)
            .with_context(|| format!("writing StreetNetwork JSON to {}", p.display())),
        None => {
            let stdout = io::stdout();
            let mut handle = stdout.lock();
            handle.write_all(payload.as_bytes()).context("writing to stdout")?;
            handle.write_all(b"\n").context("writing newline to stdout")?;
            Ok(())
        }
    }
}

fn main() -> Result<()> {
    let args = parse_args()?;

    let xml = read_input(args.input.as_ref())?;

    // No clip polygon; Overpass bbox is assumed to have pre-clipped the XML.
    let clip_pts = None;
    let cfg = MapConfig::default();
    let mut timer = Timer::throwaway();

    let (mut network, _doc) = osm_to_street_network(&xml, clip_pts, cfg, &mut timer)
        .context("osm_to_street_network failed")?;

    // Apply the standard suite of simplifications for small clipped areas.
    // This collapses degenerate intersections and short connector roads so the
    // JSON is closer to what a consumer wants (mirrors osm2streets-js default).
    network.apply_transformations(Transformation::standard_for_clipped_areas(), &mut timer);

    let json = serde_json::to_string(&network).context("serializing StreetNetwork to JSON")?;

    write_output(args.output.as_ref(), &json)?;
    Ok(())
}
