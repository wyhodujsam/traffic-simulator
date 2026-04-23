---
phase: 21
plan: 01
subsystem: vision/component-library
tags: [component-catalog, sealed-interface, records, mvp]
requirements: [P21-CATALOG-TYPES, P21-CATALOG-SEALED, P21-LIB-SKELETON]
key-files:
  created:
    - backend/src/main/java/com/trafficsimulator/vision/components/ComponentSpec.java
    - backend/src/main/java/com/trafficsimulator/vision/components/ArmRef.java
    - backend/src/main/java/com/trafficsimulator/vision/components/Connection.java
    - backend/src/main/java/com/trafficsimulator/vision/components/ExpansionContext.java
    - backend/src/main/java/com/trafficsimulator/vision/components/RoundaboutFourArm.java
    - backend/src/main/java/com/trafficsimulator/vision/components/SignalFourWay.java
    - backend/src/main/java/com/trafficsimulator/vision/components/TIntersection.java
    - backend/src/main/java/com/trafficsimulator/vision/components/StraightSegment.java
    - backend/src/main/java/com/trafficsimulator/service/MapComponentLibrary.java
commit: 997d733
---

# Phase 21 Plan 01: Component Catalog Summary

Introduced the `vision/components` package with a sealed `ComponentSpec` interface and four MVP records (`RoundaboutFourArm`, `SignalFourWay`, `TIntersection`, `StraightSegment`). Each record carries its own `expand(ExpansionContext ctx)` behaviour so new component types can be added by writing a record + test, without touching the stitcher. `ArmRef`, `Connection`, and `ExpansionContext` form the deterministic expansion primitives; `MapComponentLibrary` exposes `expand(List<ComponentSpec>)` (stitching comes in plan 21-02).

## Key design notes

- **Sealed interface** — `ComponentSpec` permits only the known records. Adding a type requires updating the `permits` clause, which is intentional: the compiler flags every `switch` that needs a new branch.
- **Record-owned expansion** — each record converts its own geometry into `NodeConfig` + `RoadConfig` entries via `ExpansionContext`. Plan 21-02 added `registerArm(...)` to this surface.
- **No stitching yet** — plan 21-01 focuses on individual expansion + ID validation (`^[a-z][a-z0-9]*$`, forbidding substrings `in`/`out` that would collide with `IntersectionGeometry.reverseRoadId`).

## Verification
`mvn test` green for all pre-existing suites plus the new per-component expansion tests. See 21-02-SUMMARY for the aggregated test count.
