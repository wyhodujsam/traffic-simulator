package com.trafficsimulator.engine;

import com.trafficsimulator.model.Lane;
import com.trafficsimulator.model.Obstacle;
import com.trafficsimulator.model.Road;
import com.trafficsimulator.model.RoadNetwork;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@Slf4j
public class ObstacleManager {

    private static final double DEFAULT_OBSTACLE_LENGTH = 3.0; // metres

    /**
     * Adds an obstacle to the specified lane.
     * @return the created Obstacle, or null if road/lane not found
     */
    public Obstacle addObstacle(RoadNetwork network, String roadId, int laneIndex,
                                double position, long currentTick) {
        Road road = network.getRoads().get(roadId);
        if (road == null) {
            log.warn("Cannot add obstacle: road {} not found", roadId);
            return null;
        }
        if (laneIndex < 0 || laneIndex >= road.getLanes().size()) {
            log.warn("Cannot add obstacle: lane index {} out of range for road {}", laneIndex, roadId);
            return null;
        }

        Lane lane = road.getLanes().get(laneIndex);

        // Clamp position to valid range
        double clampedPos = Math.max(0, Math.min(position, lane.getLength()));

        Obstacle obstacle = Obstacle.builder()
            .id(UUID.randomUUID().toString())
            .laneId(lane.getId())
            .position(clampedPos)
            .length(DEFAULT_OBSTACLE_LENGTH)
            .createdAtTick(currentTick)
            .build();

        lane.getObstacles().add(obstacle);
        log.info("Obstacle added: id={} lane={} position={}m", obstacle.getId(), lane.getId(), clampedPos);
        return obstacle;
    }

    /**
     * Removes an obstacle by ID, scanning all lanes in the network.
     * @return true if found and removed
     */
    public boolean removeObstacle(RoadNetwork network, String obstacleId) {
        for (Road road : network.getRoads().values()) {
            for (Lane lane : road.getLanes()) {
                boolean removed = lane.getObstacles().removeIf(o -> o.getId().equals(obstacleId));
                if (removed) {
                    log.info("Obstacle removed: id={} from lane={}", obstacleId, lane.getId());
                    return true;
                }
            }
        }
        log.warn("Obstacle not found for removal: id={}", obstacleId);
        return false;
    }

    /**
     * Returns all obstacles across all lanes (for snapshot building).
     */
    public List<Obstacle> getAllObstacles(RoadNetwork network) {
        List<Obstacle> all = new ArrayList<>();
        for (Road road : network.getRoads().values()) {
            for (Lane lane : road.getLanes()) {
                all.addAll(lane.getObstacles());
            }
        }
        return all;
    }
}
