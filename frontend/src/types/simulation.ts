// ---- DTOs matching backend shapes ----

export interface VehicleDto {
  id: string;
  roadId: string;              // which road
  laneId: string;
  laneIndex: number;           // 0-based lane index
  position: number;            // metres from lane start
  speed: number;               // m/s
  laneChangeProgress: number;  // 0..1
  laneChangeSourceIndex: number; // -1 = none
}

export interface TrafficLightDto {
  intersectionId: string;
  roadId: string;
  state: 'GREEN' | 'YELLOW' | 'RED';
  x: number;
  y: number;
  angle: number;
  boxBlocked: boolean;
}

export interface ObstacleDto {
  id: string;
  roadId: string;     // which road
  laneId: string;
  laneIndex: number;  // 0-based lane index
  position: number;   // metres from lane start
}

export interface StatsDto {
  vehicleCount: number;
  avgSpeed: number;      // m/s
  density: number;       // vehicles/km
  throughput: number;    // vehicles despawned in last 60s
}

export interface IntersectionDto {
  id: string;
  type: 'SIGNAL' | 'ROUNDABOUT' | 'PRIORITY' | 'NONE';
  x: number;
  y: number;
  size: number;
}

export type SimulationStatus = 'RUNNING' | 'PAUSED' | 'STOPPED';

export interface SimulationStateDto {
  tick: number;
  timestamp: number;
  status: SimulationStatus;
  vehicles: VehicleDto[];
  obstacles: ObstacleDto[];
  trafficLights: TrafficLightDto[];
  stats: StatsDto;
  error: string | null;
  mapId: string | null;
}

export interface MapInfo {
  id: string;
  name: string;
  description: string | null;
}

export interface LaneDto {
  id: string;
  laneIndex: number;
  active: boolean;
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
  lanes?: LaneDto[];     // optional — present when backend sends lane details
  clipStart: number;     // pixels to trim from road start (near intersection)
  clipEnd: number;       // pixels to trim from road end (near intersection)
  lateralOffset?: number; // perpendicular shift (backend coords) for bidirectional pairs
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
  | 'SET_SPAWN_RATE' | 'SET_SPEED_MULTIPLIER' | 'SET_MAX_SPEED' | 'LOAD_MAP'
  | 'ADD_OBSTACLE' | 'REMOVE_OBSTACLE'
  | 'CLOSE_LANE'
  | 'SET_LIGHT_CYCLE';

export interface CommandDto {
  type: CommandType;
  spawnRate?: number;
  multiplier?: number;
  maxSpeed?: number;
  mapId?: string;
  roadId?: string;       // ADD_OBSTACLE
  laneIndex?: number;    // ADD_OBSTACLE
  position?: number;     // ADD_OBSTACLE (metres)
  obstacleId?: string;   // REMOVE_OBSTACLE
  closeLaneRoadId?: string;   // CLOSE_LANE
  closeLaneIndex?: number;    // CLOSE_LANE
  intersectionId?: string;       // SET_LIGHT_CYCLE
  greenDurationMs?: number;      // SET_LIGHT_CYCLE
  yellowDurationMs?: number;     // SET_LIGHT_CYCLE
}

// ---- Snapshot wrapper for interpolation ----

export interface Snapshot {
  state: SimulationStateDto;
  receivedAt: number;   // performance.now() timestamp
  vehicleMap: Map<string, VehicleDto>; // O(1) lookup by ID
}
