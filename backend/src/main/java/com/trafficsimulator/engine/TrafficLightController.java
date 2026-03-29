package com.trafficsimulator.engine;

import com.trafficsimulator.model.Intersection;
import com.trafficsimulator.model.IntersectionType;
import com.trafficsimulator.model.RoadNetwork;
import com.trafficsimulator.model.TrafficLight;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class TrafficLightController implements ITrafficLightController {

    /**
     * Advances all traffic lights in the network by dt seconds.
     * Called at the start of each tick, before physics.
     */
    @Override
    public void tick(double dt, RoadNetwork network) {
        if (network == null) return;
        for (Intersection ixtn : network.getIntersections().values()) {
            if (ixtn.getType() == IntersectionType.SIGNAL && ixtn.getTrafficLight() != null) {
                int prevPhase = ixtn.getTrafficLight().getCurrentPhaseIndex();
                ixtn.getTrafficLight().tick(dt);
                int newPhase = ixtn.getTrafficLight().getCurrentPhaseIndex();
                if (prevPhase != newPhase) {
                    log.debug("Intersection {} phase changed: {} -> {} ({})",
                        ixtn.getId(), prevPhase, newPhase,
                        ixtn.getTrafficLight().getCurrentPhase().getType());
                }
            }
        }
    }
}
