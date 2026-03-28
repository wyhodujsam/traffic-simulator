package com.trafficsimulator.model;

import lombok.Builder;
import lombok.Data;

/**
 * Stub Vehicle model.
 * Full implementation provided by Plan 2.1.
 */
@Data
@Builder
public class Vehicle {

    private String id;
    private double position;
    private double speed;
}
