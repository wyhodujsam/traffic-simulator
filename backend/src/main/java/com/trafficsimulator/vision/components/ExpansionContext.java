package com.trafficsimulator.vision.components;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.trafficsimulator.config.MapConfig;
import com.trafficsimulator.config.MapConfig.DespawnPointConfig;
import com.trafficsimulator.config.MapConfig.IntersectionConfig;
import com.trafficsimulator.config.MapConfig.NodeConfig;
import com.trafficsimulator.config.MapConfig.RoadConfig;
import com.trafficsimulator.config.MapConfig.SpawnPointConfig;

/**
 * Mutable accumulator passed to {@link ComponentSpec#expand(ExpansionContext)}. Each component
 * appends to the public lists; {@link #toMapConfig(String, String)} assembles the final config.
 *
 * <p>Plan 21-02 adds an arm index ({@link #registerArm}/{@link #lookupArm}) and stitching
 * helpers used by {@link com.trafficsimulator.service.MapComponentLibrary} when fusing coincident
 * arm endpoints into a shared INTERSECTION node.
 */
public final class ExpansionContext {

    /** Default spawn rate carried into the produced {@link MapConfig}. */
    public static final double DEFAULT_SPAWN_RATE = 0.6;

    public final List<NodeConfig> nodes = new ArrayList<>();
    public final List<RoadConfig> roads = new ArrayList<>();
    public final List<IntersectionConfig> intersections = new ArrayList<>();
    public final List<SpawnPointConfig> spawnPoints = new ArrayList<>();
    public final List<DespawnPointConfig> despawnPoints = new ArrayList<>();

    /** Lookup of {@code componentId.armName} → arm record, populated by component expand methods. */
    private final Map<String, ArmRecord> armIndex = new HashMap<>();

    /**
     * Records an emitted arm so the stitcher can resolve {@link ArmRef} references to concrete
     * node ids and world coordinates.
     */
    public void registerArm(
            String componentId,
            String armName,
            String entryNodeId,
            String exitNodeId,
            Point2D.Double worldPos) {
        armIndex.put(componentId + "." + armName, new ArmRecord(entryNodeId, exitNodeId, worldPos));
    }

    /**
     * Resolves an {@link ArmRef} to its {@link ArmRecord}.
     *
     * @throws IllegalArgumentException if the arm was never registered (typo or absent ring arm).
     */
    public ArmRecord lookupArm(ArmRef ref) {
        ArmRecord r = armIndex.get(ref.componentId() + "." + ref.armName());
        if (r == null) {
            throw new IllegalArgumentException(
                    "Unknown arm reference '"
                            + ref.componentId()
                            + "."
                            + ref.armName()
                            + "' — not emitted by any component (known: "
                            + armIndex.keySet()
                            + ")");
        }
        return r;
    }

    /** Rewrites {@code from}/{@code to} of any road whose endpoint appears in {@code deadIds}. */
    public void rewriteRoadEndpoint(Set<String> deadIds, String newId) {
        for (RoadConfig r : roads) {
            if (deadIds.contains(r.getFromNodeId())) {
                r.setFromNodeId(newId);
            }
            if (deadIds.contains(r.getToNodeId())) {
                r.setToNodeId(newId);
            }
        }
    }

    /** Removes nodes whose ids appear in {@code ids}. */
    public void dropNodes(Set<String> ids) {
        nodes.removeIf(n -> ids.contains(n.getId()));
    }

    /**
     * Drops spawn/despawn points referencing roads whose original from-/to-node id was just
     * deleted. Concretely: a spawn lives on an {@code _in} road that started at an ENTRY; when
     * that ENTRY is fused away the spawn must go too. Same for despawns on {@code _out} roads.
     *
     * <p>Implementation: drop spawn/despawn whose road no longer has either endpoint matching
     * the original ENTRY/EXIT node id (i.e. it now points at the merged node). Simpler rule that
     * mirrors plan intent — the road's other end is the structural node (ring/center) which
     * survives, so any spawn/despawn on a road touching the merged node gets dropped.
     */
    public void dropSpawnDespawnForRoadsReferencing(Set<String> deadNodeIds) {
        Set<String> affectedRoadIds = new HashSet<>();
        for (RoadConfig r : roads) {
            // After rewriteRoadEndpoint the from/to has already been swapped to mergedId; we
            // cannot detect by current endpoints. Caller must invoke this BEFORE rewrite.
            if (deadNodeIds.contains(r.getFromNodeId()) || deadNodeIds.contains(r.getToNodeId())) {
                affectedRoadIds.add(r.getId());
            }
        }
        spawnPoints.removeIf(sp -> affectedRoadIds.contains(sp.getRoadId()));
        despawnPoints.removeIf(dp -> affectedRoadIds.contains(dp.getRoadId()));
    }

    /**
     * Returns {@code componentId__localId}. The double underscore separator avoids collision with
     * the {@code _in}/{@code _out} suffix used by the engine routing convention.
     */
    public String prefix(String componentId, String localId) {
        return componentId + "__" + localId;
    }

    /** Convenience helper to build a {@link NodeConfig} record. */
    public NodeConfig addNode(String id, String type, double x, double y) {
        NodeConfig n = new NodeConfig();
        n.setId(id);
        n.setType(type);
        n.setX(x);
        n.setY(y);
        nodes.add(n);
        return n;
    }

    /** Convenience helper to build a {@link RoadConfig} record. */
    public RoadConfig addRoad(
            String id,
            String name,
            String fromNodeId,
            String toNodeId,
            double length,
            double speedLimit,
            int laneCount) {
        RoadConfig r = new RoadConfig();
        r.setId(id);
        r.setName(name);
        r.setFromNodeId(fromNodeId);
        r.setToNodeId(toNodeId);
        r.setLength(length);
        r.setSpeedLimit(speedLimit);
        r.setLaneCount(laneCount);
        roads.add(r);
        return r;
    }

    /** Convenience helper to build a {@link SpawnPointConfig}. */
    public SpawnPointConfig addSpawn(String roadId, int laneIndex, double position) {
        SpawnPointConfig sp = new SpawnPointConfig();
        sp.setRoadId(roadId);
        sp.setLaneIndex(laneIndex);
        sp.setPosition(position);
        spawnPoints.add(sp);
        return sp;
    }

    /** Convenience helper to build a {@link DespawnPointConfig}. */
    public DespawnPointConfig addDespawn(String roadId, int laneIndex, double position) {
        DespawnPointConfig dp = new DespawnPointConfig();
        dp.setRoadId(roadId);
        dp.setLaneIndex(laneIndex);
        dp.setPosition(position);
        despawnPoints.add(dp);
        return dp;
    }

    /** Builds a {@link MapConfig} from the accumulated state. */
    public MapConfig toMapConfig(String id, String name) {
        MapConfig cfg = new MapConfig();
        cfg.setId(id);
        cfg.setName(name);
        cfg.setDescription("Generated by MapComponentLibrary (Phase 21).");
        cfg.setNodes(new ArrayList<>(nodes));
        cfg.setRoads(new ArrayList<>(roads));
        cfg.setIntersections(new ArrayList<>(intersections));
        cfg.setSpawnPoints(new ArrayList<>(spawnPoints));
        cfg.setDespawnPoints(new ArrayList<>(despawnPoints));
        cfg.setDefaultSpawnRate(DEFAULT_SPAWN_RATE);
        return cfg;
    }

    /** Arm registration record: ENTRY node id, EXIT node id, and world-pixel position. */
    public record ArmRecord(String entryNodeId, String exitNodeId, Point2D.Double worldPos) {}
}
