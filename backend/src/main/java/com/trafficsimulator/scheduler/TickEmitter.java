package com.trafficsimulator.scheduler;

import com.trafficsimulator.dto.TickDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
@RequiredArgsConstructor
@Slf4j
public class TickEmitter {

    private final SimpMessagingTemplate messagingTemplate;
    private final AtomicLong tickCounter = new AtomicLong(0);

    @Scheduled(fixedRate = 50)
    public void emitTick() {
        long tick = tickCounter.incrementAndGet();
        TickDto payload = new TickDto(tick, System.currentTimeMillis());
        messagingTemplate.convertAndSend("/topic/simulation", payload);
        if (tick % 100 == 0) {
            log.info("Tick #{}", tick);
        }
    }
}
