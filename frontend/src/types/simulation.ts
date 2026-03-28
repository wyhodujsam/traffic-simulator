// ---- DTOs matching backend shapes ----

export interface VehicleDto {
  id: string;
  laneId: string;
  position: number;   // metres from lane start
  speed: number;      // m/s
  x: number;          // pixel x coordinate
  y: number;          // pixel y coordinate
  angle: number;      // radians
}

export interface StatsDto {
  vehicleCount: number;
  avgSpeed: number;      // m/s
  density: number;       // vehicles/km
  throughput: number;    // vehicles despawned in last 60s
}

export type SimulationStatus = 'RUNNING' | 'PAUSED' | 'STOPPED';

export interface SimulationStateDto {
  tick: number;
  timestamp: number;
  status: SimulationStatus;
  vehicles: VehicleDto[];
  stats: StatsDto;
}

export interface RoadDto {
  id: string;
  name: string;
  laneCount: number;
  length: number;        // metres
  speedLimit: number;    // m/s
  startX: number;
  startY: number;
  endX: number;
  endY: number;
}

export interface SimulationStatusDto {
  status: SimulationStatus;
  tick: number;
  vehicleCount: number;
  speedMultiplier: number;
  spawnRate: number;
  maxSpeed: number;
  mapId: string | null;
}

// ---- Command types ----

export type CommandType =
  | 'START' | 'STOP' | 'PAUSE' | 'RESUME'
  | 'SET_SPAWN_RATE' | 'SET_SPEED_MULTIPLIER' | 'SET_MAX_SPEED' | 'LOAD_MAP';

export interface CommandDto {
  type: CommandType;
  spawnRate?: number;
  multiplier?: number;
  maxSpeed?: number;
  mapId?: string;
}

// ---- Snapshot wrapper for interpolation ----

export interface Snapshot {
  state: SimulationStateDto;
  receivedAt: number;   // performance.now() timestamp
  vehicleMap: Map<string, VehicleDto>; // O(1) lookup by ID
}
