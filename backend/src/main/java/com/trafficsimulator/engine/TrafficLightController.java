package com.trafficsimulator.engine;

import com.trafficsimulator.model.Intersection;
import com.trafficsimulator.model.IntersectionType;
import com.trafficsimulator.model.RoadNetwork;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Advances all traffic lights in the network each tick.
 * Stub implementation — full logic provided by Plan 08-01.
 */
@Component
@Slf4j
public class TrafficLightController {

    /**
     * Advances traffic lights for all SIGNAL intersections by dt seconds.
     */
    public void tick(double dt, RoadNetwork network) {
        if (network == null) return;
        for (Intersection ixtn : network.getIntersections().values()) {
            if (ixtn.getType() == IntersectionType.SIGNAL && ixtn.getTrafficLight() != null) {
                ixtn.getTrafficLight().advance(dt);
            }
        }
    }
}
