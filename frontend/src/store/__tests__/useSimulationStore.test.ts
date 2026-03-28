import { describe, it, expect, beforeEach } from 'vitest';
import { useSimulationStore } from '../useSimulationStore';
import type { SimulationStateDto } from '../../types/simulation';

function makeState(overrides: Partial<SimulationStateDto> = {}): SimulationStateDto {
  return {
    tick: 1,
    timestamp: Date.now(),
    status: 'RUNNING',
    vehicles: [],
    stats: { vehicleCount: 0, avgSpeed: 0, density: 0, throughput: 0 },
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
        { id: 'v1', laneId: 'l1', position: 10, speed: 5, x: 100, y: 200, angle: 0 },
        { id: 'v2', laneId: 'l1', position: 20, speed: 8, x: 150, y: 200, angle: 0 },
      ],
    });
    useSimulationStore.getState().setTick(tick);

    const map = useSimulationStore.getState().currSnapshot?.vehicleMap;
    expect(map?.size).toBe(2);
    expect(map?.get('v1')?.x).toBe(100);
    expect(map?.get('v2')?.x).toBe(150);
  });

  it('setRoads marks roadsLoaded as true', () => {
    useSimulationStore.getState().setRoads([
      { id: 'r1', name: 'Test', laneCount: 3, length: 800, speedLimit: 33, startX: 50, startY: 300, endX: 850, endY: 300 },
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
