package com.trafficsimulator.service;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.trafficsimulator.config.MapConfig;
import com.trafficsimulator.config.MapValidator;
import com.trafficsimulator.vision.components.ComponentSpec;
import com.trafficsimulator.vision.components.Connection;
import com.trafficsimulator.vision.components.ExpansionContext;

import lombok.RequiredArgsConstructor;

/**
 * Phase 21 component-library entry point. Expands a list of {@link ComponentSpec} into a single
 * {@link MapConfig} that passes {@link MapValidator}. Stitching of {@link Connection}s arrives in
 * plan 21-02; this skeleton rejects non-empty connection lists.
 */
@Service
@RequiredArgsConstructor
public class MapComponentLibrary {

    /** Allowed component-id pattern: lowercase letter then lowercase alphanumerics. */
    public static final Pattern ID_RX = Pattern.compile("^[a-z][a-z0-9]*$");

    private final MapValidator mapValidator;

    /** Expand without connections (single component or fully-disjoint set). */
    public MapConfig expand(List<ComponentSpec> components) {
        return expand(components, List.of());
    }

    /**
     * Expand a list of components into a {@link MapConfig}.
     *
     * @throws IllegalArgumentException        when any component id violates the naming rule.
     * @throws UnsupportedOperationException   when {@code connections} is non-empty (deferred to plan 21-02).
     * @throws IllegalStateException           when the produced config fails {@link MapValidator}.
     */
    public MapConfig expand(List<ComponentSpec> components, List<Connection> connections) {
        if (components == null || components.isEmpty()) {
            throw new IllegalArgumentException("MapComponentLibrary.expand requires at least one component");
        }
        for (ComponentSpec c : components) {
            validateId(c.id());
        }
        if (connections != null && !connections.isEmpty()) {
            throw new UnsupportedOperationException(
                    "Component stitching is not implemented yet — arrives in plan 21-02");
        }
        ExpansionContext ctx = new ExpansionContext();
        for (ComponentSpec c : components) {
            c.expand(ctx);
        }
        MapConfig cfg = ctx.toMapConfig("vision-generated", "AI Generated Map (component library)");
        List<String> errors = mapValidator.validate(cfg);
        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    "Component expansion produced invalid MapConfig: " + String.join(", ", errors));
        }
        return cfg;
    }

    private void validateId(String id) {
        if (id == null
                || !ID_RX.matcher(id).matches()
                || id.contains("in")
                || id.contains("out")) {
            throw new IllegalArgumentException(
                    "Component id '"
                            + id
                            + "' must match ^[a-z][a-z0-9]*$ and must not contain 'in' or 'out' substrings");
        }
    }
}
