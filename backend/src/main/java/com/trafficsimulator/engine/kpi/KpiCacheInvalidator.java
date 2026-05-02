package com.trafficsimulator.engine.kpi;

/**
 * Engine-layer hook for invalidating downstream KPI sub-sampling caches (KPI-07).
 *
 * <p>The cache itself lives in the scheduler layer ({@code SnapshotBuilder.lastSegmentKpis} et al.)
 * because that is the natural owner of the per-tick STOMP snapshot path. {@code CommandDispatcher}
 * lives in the engine layer and is forbidden by ArchUnit from importing {@code scheduler.*}, so we
 * introduce this single-method interface in {@code engine.kpi} for the scheduler to implement.
 * CommandDispatcher's LoadMap / LoadConfig handlers depend only on this interface.
 */
@FunctionalInterface
public interface KpiCacheInvalidator {
    /** Drops every cached per-segment / per-intersection KPI list. Called on map change. */
    void clearCache();
}
