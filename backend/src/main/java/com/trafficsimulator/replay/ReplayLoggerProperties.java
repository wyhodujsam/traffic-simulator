package com.trafficsimulator.replay;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/**
 * Spring configuration properties for the replay logger. Default disabled per CONTEXT.md §D-14
 * (REPLAY-03) to avoid disk fill in casual runs; auto-enabled by RUN_FOR_TICKS / RUN_FOR_TICKS_FAST
 * dispatchers.
 */
@ConfigurationProperties(prefix = "simulator.replay")
@Data
public class ReplayLoggerProperties {

    /** When true, replay logger writes on every tick. Auto-enabled by RUN_FOR_TICKS commands. */
    private boolean enabled = false;

    /** Replay output directory (relative to project root). Default: target/replays. */
    private String directory = "target/replays";
}
