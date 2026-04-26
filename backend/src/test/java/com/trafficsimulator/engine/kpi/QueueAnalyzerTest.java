package com.trafficsimulator.engine.kpi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.trafficsimulator.model.Lane;
import com.trafficsimulator.model.Vehicle;

class QueueAnalyzerTest {

    private static final double SPEED_LIMIT = 22.2;
    private static final double SLOW = SPEED_LIMIT * 0.20; // < 0.30 × speedLimit
    private static final double FAST = SPEED_LIMIT * 0.95; // > 0.30 × speedLimit

    @Test
    void emptyLane_returnsZero() {
        Lane lane = mock(Lane.class);
        when(lane.getVehiclesView()).thenReturn(List.of());
        assertThat(QueueAnalyzer.maxQueueLengthMeters(lane, SPEED_LIMIT)).isZero();
    }

    @Test
    void noQueuedVehicles_returnsZero() {
        Vehicle v1 = mockVehicle(240, FAST);
        Vehicle v2 = mockVehicle(220, FAST);
        Vehicle v3 = mockVehicle(200, FAST);
        Lane lane = mock(Lane.class);
        when(lane.getVehiclesView()).thenReturn(List.of(v1, v2, v3));
        assertThat(QueueAnalyzer.maxQueueLengthMeters(lane, SPEED_LIMIT)).isZero();
    }

    @Test
    void contiguousQueueFromExit_returnsLength() {
        Vehicle v1 = mockVehicle(240, SLOW);
        Vehicle v2 = mockVehicle(220, SLOW);
        Vehicle v3 = mockVehicle(200, SLOW);
        Lane lane = mock(Lane.class);
        when(lane.getVehiclesView()).thenReturn(List.of(v1, v2, v3));
        // queue starts at 240 (exit), ends at 200 → 40 m
        assertThat(QueueAnalyzer.maxQueueLengthMeters(lane, SPEED_LIMIT)).isEqualTo(40.0);
    }

    @Test
    void queueBreaksOnFastVehicle_returnsHeadOnly() {
        Vehicle v1 = mockVehicle(240, SLOW);
        Vehicle v2 = mockVehicle(220, FAST); // queue ends here
        Vehicle v3 = mockVehicle(200, SLOW);
        Lane lane = mock(Lane.class);
        when(lane.getVehiclesView()).thenReturn(List.of(v1, v2, v3));
        // single-vehicle queue — start == end → 0
        assertThat(QueueAnalyzer.maxQueueLengthMeters(lane, SPEED_LIMIT)).isZero();
    }

    @Test
    void noQueueAtExit_returnsZero() {
        Vehicle v1 = mockVehicle(240, FAST);
        Vehicle v2 = mockVehicle(220, SLOW);
        Vehicle v3 = mockVehicle(200, SLOW);
        Lane lane = mock(Lane.class);
        when(lane.getVehiclesView()).thenReturn(List.of(v1, v2, v3));
        // D-06 says "from exit going upstream"; head vehicle is fast → no queue at exit. Return 0.
        assertThat(QueueAnalyzer.maxQueueLengthMeters(lane, SPEED_LIMIT)).isZero();
    }

    /**
     * Builds a Vehicle mock with stubbed {@code getPosition} / {@code getSpeed}. {@code lenient()}
     * suppresses {@link org.mockito.exceptions.misusing.UnnecessaryStubbingException} when the
     * analyser short-circuits (e.g., {@link #noQueueAtExit_returnsZero}) before reading the speed of
     * trailing vehicles in the list.
     */
    private Vehicle mockVehicle(double position, double speed) {
        Vehicle v = mock(Vehicle.class);
        lenient().when(v.getPosition()).thenReturn(position);
        lenient().when(v.getSpeed()).thenReturn(speed);
        return v;
    }
}
