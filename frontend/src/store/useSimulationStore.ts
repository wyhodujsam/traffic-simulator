import { create } from 'zustand';

export interface TickDto {
  tick: number;
  timestamp: number;
}

interface SimulationStore {
  lastTick: TickDto | null;
  tickCount: number;
  setTick: (tick: TickDto) => void;
}

export const useSimulationStore = create<SimulationStore>((set) => ({
  lastTick: null,
  tickCount: 0,
  setTick: (tick) =>
    set((state) => ({
      lastTick: tick,
      tickCount: state.tickCount + 1,
    })),
}));
