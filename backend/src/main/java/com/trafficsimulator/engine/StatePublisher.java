package com.trafficsimulator.engine;

import com.trafficsimulator.dto.SimulationStateDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class StatePublisher {

    private static final String TOPIC = "/topic/simulation";

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Broadcasts the simulation state snapshot to all subscribers.
     */
    public void broadcast(SimulationStateDto state) {
        messagingTemplate.convertAndSend(TOPIC, state);
    }
}
