import { create } from 'zustand';
import type {
  SimulationStateDto,
  SimulationStatus,
  StatsDto,
  Snapshot,
  VehicleDto,
  ObstacleDto,
  TrafficLightDto,
  CommandDto,
  RoadDto,
  MapInfo,
  IntersectionDto,
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
  obstacles: ObstacleDto[];

  // --- Traffic lights ---
  trafficLights: TrafficLightDto[];

  // --- Road geometry (fetched once via REST) ---
  roads: RoadDto[];
  roadsLoaded: boolean;

  // --- Intersection geometry (fetched once via REST) ---
  intersections: IntersectionDto[];

  // --- Command sending ---
  sendCommand: ((cmd: CommandDto) => void) | null;

  // --- Map selection ---
  availableMaps: MapInfo[];
  currentMapId: string | null;
  mapError: string | null;
  refetchRoads: (() => void) | null;

  // --- Actions ---
  setTick: (state: SimulationStateDto) => void;
  setSendCommand: (fn: ((cmd: CommandDto) => void) | null) => void;
  setRoads: (roads: RoadDto[]) => void;
  setIntersections: (intersections: IntersectionDto[]) => void;
  setAvailableMaps: (maps: MapInfo[]) => void;
  setCurrentMapId: (mapId: string | null) => void;
  setMapError: (error: string | null) => void;
  setRefetchRoads: (fn: (() => void) | null) => void;
  clearSnapshots: () => void;
}

export const useSimulationStore = create<SimulationStore>((set, get) => ({
  status: 'STOPPED',
  currSnapshot: null,
  prevSnapshot: null,
  tickCount: 0,
  stats: null,
  obstacles: [],
  trafficLights: [],
  roads: [],
  roadsLoaded: false,
  intersections: [],
  sendCommand: null,
  availableMaps: [],
  currentMapId: null,
  mapError: null,
  refetchRoads: null,

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
      trafficLights: state.trafficLights ?? [],
      currentMapId: state.mapId ?? prev.currentMapId,
      mapError: state.error ?? null,
    }));
  },

  setSendCommand: (fn) => set({ sendCommand: fn }),

  setRoads: (roads) => set({ roads, roadsLoaded: true }),
  setIntersections: (intersections) => set({ intersections }),
  setAvailableMaps: (maps) => set({ availableMaps: maps }),
  setCurrentMapId: (mapId) => set({ currentMapId: mapId }),
  setMapError: (error) => set({ mapError: error }),
  setRefetchRoads: (fn) => set({ refetchRoads: fn }),
  clearSnapshots: () => set({
    currSnapshot: null,
    prevSnapshot: null,
    obstacles: [],
    trafficLights: [],
  }),
}));
