import { describe, it, expect, beforeEach } from 'vitest';
import { useSimulationStore } from '../store/useSimulationStore';
import type { SimulationStateDto } from '../types/simulation';

function makeTick(tick: number, timestamp: number): SimulationStateDto {
  return {
    tick,
    timestamp,
    status: 'RUNNING',
    vehicles: [],
    stats: { vehicleCount: 0, avgSpeed: 0, density: 0, throughput: 0 },
  };
}

describe('useSimulationStore', () => {
  beforeEach(() => {
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

  it('should have initial state with null currSnapshot and zero tickCount', () => {
    const state = useSimulationStore.getState();
    expect(state.currSnapshot).toBeNull();
    expect(state.tickCount).toBe(0);
  });

  it('should update currSnapshot and increment tickCount on setTick', () => {
    const tick = makeTick(1, Date.now());
    useSimulationStore.getState().setTick(tick);

    const state = useSimulationStore.getState();
    expect(state.currSnapshot?.state).toEqual(tick);
    expect(state.tickCount).toBe(1);
  });

  it('should increment tickCount on each setTick call', () => {
    const store = useSimulationStore.getState();
    store.setTick(makeTick(1, 1000));
    store.setTick(makeTick(2, 1050));
    store.setTick(makeTick(3, 1100));

    const state = useSimulationStore.getState();
    expect(state.tickCount).toBe(3);
    expect(state.currSnapshot?.state.tick).toBe(3);
  });

  it('should keep prev and curr snapshots', () => {
    const store = useSimulationStore.getState();
    store.setTick(makeTick(100, 5000));
    store.setTick(makeTick(101, 5050));

    const state = useSimulationStore.getState();
    expect(state.currSnapshot?.state.tick).toBe(101);
    expect(state.prevSnapshot?.state.tick).toBe(100);
  });

  it('should build vehicleMap for O(1) lookup', () => {
    const tick: SimulationStateDto = {
      tick: 1,
      timestamp: 1000,
      status: 'RUNNING',
      vehicles: [
        { id: 'v1', laneId: 'l1', position: 10, speed: 5, x: 100, y: 200, angle: 0 },
        { id: 'v2', laneId: 'l1', position: 20, speed: 8, x: 150, y: 200, angle: 0 },
      ],
      stats: { vehicleCount: 2, avgSpeed: 6.5, density: 1, throughput: 0 },
    };
    useSimulationStore.getState().setTick(tick);

    const state = useSimulationStore.getState();
    expect(state.currSnapshot?.vehicleMap.get('v1')?.speed).toBe(5);
    expect(state.currSnapshot?.vehicleMap.get('v2')?.speed).toBe(8);
    expect(state.currSnapshot?.vehicleMap.size).toBe(2);
  });

  it('should update status from tick data', () => {
    useSimulationStore.getState().setTick(makeTick(1, 1000));
    expect(useSimulationStore.getState().status).toBe('RUNNING');
  });

  it('should store roads via setRoads', () => {
    const roads = [{ id: 'r1', name: 'Main St', laneCount: 2, length: 500, speedLimit: 13.9, startX: 0, startY: 0, endX: 500, endY: 0 }];
    useSimulationStore.getState().setRoads(roads);

    const state = useSimulationStore.getState();
    expect(state.roads).toEqual(roads);
    expect(state.roadsLoaded).toBe(true);
  });
});
