package com.trafficsimulator.model;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Stub Road model.
 * Full implementation provided by Plan 2.1.
 */
@Data
@Builder
public class Road {

    private String id;

    @Builder.Default
    private List<Lane> lanes = new ArrayList<>();
}
