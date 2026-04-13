package com.trafficsimulator.vision.components;

import java.awt.geom.Point2D;
import java.util.List;

/**
 * Jackson-friendly mutable DTOs for the flat {@code {components, connections}} JSON schema emitted
 * by {@code ComponentVisionService}'s Claude prompt (Phase 21).
 *
 * <p>Two-pass parsing: Jackson reads {@link Envelope} → {@link ComponentSpecDto} → caller switches
 * on {@code type} to construct the sealed {@link ComponentSpec} records. This sidesteps Jackson's
 * fragile polymorphic deserialisation of sealed interfaces (see RESEARCH pitfall 6).
 */
public class ComponentSpecDto {

    public String type;
    public String id;
    public Point2D.Double centerPx;
    public Point2D.Double startPx;
    public Point2D.Double endPx;
    public double rotationDeg;
    public List<String> armsPresent;
    public Double lengthPx;

    /** Top-level envelope Claude emits. */
    public static class Envelope {
        public List<ComponentSpecDto> components;
        public List<ConnectionDto> connections;
    }

    /** Flat connection literal: {@code "rb1.north"} dotted strings for both ends. */
    public static class ConnectionDto {
        public String a;
        public String b;
    }
}
