import { describe, it, expect, beforeEach } from 'vitest';
import { useSimulationStore } from '../useSimulationStore';
import type { SimulationStateDto } from '../../types/simulation';

function makeState(overrides: Partial<SimulationStateDto> = {}): SimulationStateDto {
  return {
    tick: 1,
    timestamp: Date.now(),
    status: 'RUNNING',
    vehicles: [],
    obstacles: [],
    trafficLights: [],
    stats: { vehicleCount: 0, avgSpeed: 0, density: 0, throughput: 0 },
    error: null,
    mapId: null,
    ...overrides,
  };
}

describe('useSimulationStore', () => {
  beforeEach(() => {
    // Reset store between tests
    useSimulationStore.setState({
      status: 'STOPPED',
      currSnapshot: null,
      prevSnapshot: null,
      tickCount: 0,
      stats: null,
      roads: [],
      roadsLoaded: false,
      sendCommand: null,
      diagnosticsOpen: false,
    });
  });

  it('initializes with STOPPED status and null snapshots', () => {
    const state = useSimulationStore.getState();
    expect(state.status).toBe('STOPPED');
    expect(state.currSnapshot).toBeNull();
    expect(state.prevSnapshot).toBeNull();
    expect(state.tickCount).toBe(0);
  });

  it('setTick updates status, snapshots, and tickCount', () => {
    const tick1 = makeState({ tick: 1, status: 'RUNNING' });
    useSimulationStore.getState().setTick(tick1);

    const state = useSimulationStore.getState();
    expect(state.status).toBe('RUNNING');
    expect(state.currSnapshot).not.toBeNull();
    expect(state.prevSnapshot).toBeNull(); // first tick has no prev
    expect(state.tickCount).toBe(1);
  });

  it('setTick rotates curr to prev on second tick', () => {
    const tick1 = makeState({ tick: 1 });
    const tick2 = makeState({ tick: 2 });

    useSimulationStore.getState().setTick(tick1);
    useSimulationStore.getState().setTick(tick2);

    const state = useSimulationStore.getState();
    expect(state.currSnapshot?.state.tick).toBe(2);
    expect(state.prevSnapshot?.state.tick).toBe(1);
    expect(state.tickCount).toBe(2);
  });

  it('setTick builds vehicleMap for O(1) lookup', () => {
    const tick = makeState({
      vehicles: [
        { id: 'v1', roadId: 'r1', laneId: 'l1', laneIndex: 0, position: 10, speed: 5, laneChangeProgress: 0, laneChangeSourceIndex: -1 },
        { id: 'v2', roadId: 'r1', laneId: 'l1', laneIndex: 0, position: 20, speed: 8, laneChangeProgress: 0, laneChangeSourceIndex: -1 },
      ],
    });
    useSimulationStore.getState().setTick(tick);

    const map = useSimulationStore.getState().currSnapshot?.vehicleMap;
    expect(map?.size).toBe(2);
    expect(map?.get('v1')?.speed).toBe(5);
    expect(map?.get('v2')?.speed).toBe(8);
  });

  it('setRoads marks roadsLoaded as true', () => {
    useSimulationStore.getState().setRoads([
      { id: 'r1', name: 'Test', laneCount: 3, length: 800, speedLimit: 33, startX: 50, startY: 300, endX: 850, endY: 300, clipStart: 0, clipEnd: 0 },
    ]);
    const state = useSimulationStore.getState();
    expect(state.roads).toHaveLength(1);
    expect(state.roadsLoaded).toBe(true);
  });

  it('setSendCommand stores the function reference', () => {
    const mockSend = () => {};
    useSimulationStore.getState().setSendCommand(mockSend);
    expect(useSimulationStore.getState().sendCommand).toBe(mockSend);
  });
});

describe('useSimulationStore — diagnostics UI state (Phase 25 D-09)', () => {
  beforeEach(() => {
    // Reset diagnosticsOpen to default
    useSimulationStore.setState({ diagnosticsOpen: false });
  });

  it('diagnosticsOpen defaults to false', () => {
    expect(useSimulationStore.getState().diagnosticsOpen).toBe(false);
  });

  it('toggleDiagnostics flips state', () => {
    useSimulationStore.getState().toggleDiagnostics();
    expect(useSimulationStore.getState().diagnosticsOpen).toBe(true);
    useSimulationStore.getState().toggleDiagnostics();
    expect(useSimulationStore.getState().diagnosticsOpen).toBe(false);
  });

  it('stats accepts KPI block via setTick', () => {
    const state: SimulationStateDto = {
      tick: 1,
      timestamp: 0,
      status: 'RUNNING',
      vehicles: [],
      obstacles: [],
      trafficLights: [],
      stats: {
        vehicleCount: 1,
        avgSpeed: 10,
        density: 1,
        throughput: 5,
        kpi: { throughputVehiclesPerMin: 5, meanDelaySeconds: 0.5, p95QueueLengthMeters: 0, worstLos: 'B' },
        segmentKpis: [],
        intersectionKpis: [],
      },
      error: null,
      mapId: 'test',
    };
    useSimulationStore.getState().setTick(state);
    const stats = useSimulationStore.getState().stats;
    expect(stats?.kpi?.worstLos).toBe('B');
    expect(stats?.kpi?.throughputVehiclesPerMin).toBe(5);
  });
});
