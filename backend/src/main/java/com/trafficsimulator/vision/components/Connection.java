package com.trafficsimulator.vision.components;

/** Undirected connection between two component arms. Stitching is implemented in plan 21-02. */
public record Connection(ArmRef a, ArmRef b) {}
