import { describe, it, expect, beforeEach } from 'vitest';
import { useSimulationStore } from '../store/useSimulationStore';

describe('useSimulationStore', () => {
  beforeEach(() => {
    useSimulationStore.setState({ lastTick: null, tickCount: 0 });
  });

  it('should have initial state with null lastTick and zero tickCount', () => {
    const state = useSimulationStore.getState();
    expect(state.lastTick).toBeNull();
    expect(state.tickCount).toBe(0);
  });

  it('should update lastTick and increment tickCount on setTick', () => {
    const tick = { tick: 1, timestamp: Date.now() };
    useSimulationStore.getState().setTick(tick);

    const state = useSimulationStore.getState();
    expect(state.lastTick).toEqual(tick);
    expect(state.tickCount).toBe(1);
  });

  it('should increment tickCount on each setTick call', () => {
    const store = useSimulationStore.getState();
    store.setTick({ tick: 1, timestamp: 1000 });
    store.setTick({ tick: 2, timestamp: 1050 });
    store.setTick({ tick: 3, timestamp: 1100 });

    const state = useSimulationStore.getState();
    expect(state.tickCount).toBe(3);
    expect(state.lastTick?.tick).toBe(3);
  });

  it('should always keep the latest tick', () => {
    const store = useSimulationStore.getState();
    store.setTick({ tick: 100, timestamp: 5000 });
    store.setTick({ tick: 101, timestamp: 5050 });

    const state = useSimulationStore.getState();
    expect(state.lastTick?.tick).toBe(101);
    expect(state.lastTick?.timestamp).toBe(5050);
  });
});
