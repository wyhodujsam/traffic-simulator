import { create } from 'zustand';
import type {
  SimulationStateDto,
  SimulationStatus,
  StatsDto,
  Snapshot,
  VehicleDto,
  CommandDto,
  RoadDto,
  ObstacleDto,
} from '../types/simulation';

function buildVehicleMap(vehicles: VehicleDto[]): Map<string, VehicleDto> {
  const map = new Map<string, VehicleDto>();
  for (const v of vehicles) {
    map.set(v.id, v);
  }
  return map;
}

interface SimulationStore {
  // --- State from WebSocket ---
  status: SimulationStatus;
  currSnapshot: Snapshot | null;
  prevSnapshot: Snapshot | null;
  tickCount: number;
  stats: StatsDto | null;

  // --- Obstacles ---
  obstacles: ObstacleDto[];

  // --- Road geometry (fetched once via REST) ---
  roads: RoadDto[];
  roadsLoaded: boolean;

  // --- Command sending ---
  sendCommand: ((cmd: CommandDto) => void) | null;

  // --- Actions ---
  setTick: (state: SimulationStateDto) => void;
  setSendCommand: (fn: ((cmd: CommandDto) => void) | null) => void;
  setRoads: (roads: RoadDto[]) => void;
}

export const useSimulationStore = create<SimulationStore>((set, get) => ({
  status: 'STOPPED',
  currSnapshot: null,
  prevSnapshot: null,
  tickCount: 0,
  stats: null,
  obstacles: [],
  roads: [],
  roadsLoaded: false,
  sendCommand: null,

  setTick: (state: SimulationStateDto) => {
    const now = performance.now();
    const snapshot: Snapshot = {
      state,
      receivedAt: now,
      vehicleMap: buildVehicleMap(state.vehicles),
    };
    set((prev) => ({
      status: state.status,
      prevSnapshot: prev.currSnapshot,
      currSnapshot: snapshot,
      tickCount: prev.tickCount + 1,
      stats: state.stats,
      obstacles: state.obstacles ?? [],
    }));
  },

  setSendCommand: (fn) => set({ sendCommand: fn }),

  setRoads: (roads) => set({ roads, roadsLoaded: true }),
}));
