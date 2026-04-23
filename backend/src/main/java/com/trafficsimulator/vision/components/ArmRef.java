package com.trafficsimulator.vision.components;

/** Reference to a named arm of a component (e.g. {@code rb1.north}). */
public record ArmRef(String componentId, String armName) {

    /** Parses {@code "rb1.north"} into {@code new ArmRef("rb1", "north")}. */
    public static ArmRef parse(String dotted) {
        if (dotted == null) {
            throw new IllegalArgumentException("ArmRef literal is null");
        }
        int dot = dotted.indexOf('.');
        if (dot <= 0 || dot == dotted.length() - 1) {
            throw new IllegalArgumentException(
                    "ArmRef literal must be 'componentId.armName', got: " + dotted);
        }
        return new ArmRef(dotted.substring(0, dot), dotted.substring(dot + 1));
    }
}
