package com.trafficsimulator.model;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Stub Lane model.
 * Full implementation provided by Plan 2.1.
 */
@Data
@Builder
public class Lane {

    private String id;
    private double maxSpeed;

    @Builder.Default
    private List<Vehicle> vehicles = new ArrayList<>();
}
