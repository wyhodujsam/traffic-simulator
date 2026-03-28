package com.trafficsimulator.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * STOMP command DTO received from the frontend on /app/command.
 *
 * <p>The {@code type} field must be one of: START, STOP, PAUSE, RESUME,
 * SET_SPAWN_RATE, SET_SPEED_MULTIPLIER, LOAD_MAP, SET_MAX_SPEED,
 * ADD_OBSTACLE, REMOVE_OBSTACLE, CLOSE_LANE. Unknown types produce a
 * descriptive error message with the list of valid types.
 *
 * <p>Payload fields are nullable and read based on {@code type}:
 * <ul>
 *   <li>{@code spawnRate} — required for SET_SPAWN_RATE</li>
 *   <li>{@code multiplier} — required for SET_SPEED_MULTIPLIER</li>
 *   <li>{@code mapId} — required for LOAD_MAP</li>
 *   <li>{@code maxSpeed} — required for SET_MAX_SPEED</li>
 *   <li>{@code roadId}, {@code laneIndex}, {@code position} — required for ADD_OBSTACLE</li>
 *   <li>{@code roadId}, {@code laneIndex} — required for CLOSE_LANE</li>
 *   <li>{@code obstacleId} — required for REMOVE_OBSTACLE</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CommandDto {

    /** Command type string. See Javadoc for valid values. */
    private String type;

    /** Vehicles per second — used by SET_SPAWN_RATE. */
    private Double spawnRate;

    /** Speed multiplier (e.g. 0.5 = half speed) — used by SET_SPEED_MULTIPLIER. */
    private Double multiplier;

    /** Map identifier — used by LOAD_MAP. */
    private String mapId;

    /** Global max speed in m/s — used by SET_MAX_SPEED. */
    private Double maxSpeed;

    /** Road ID — used by ADD_OBSTACLE. */
    private String roadId;

    /** Lane index (0-based) — used by ADD_OBSTACLE. */
    private Integer laneIndex;

    /** Position in metres — used by ADD_OBSTACLE. */
    private Double position;

    /** Obstacle ID — used by REMOVE_OBSTACLE. */
    private String obstacleId;
}
