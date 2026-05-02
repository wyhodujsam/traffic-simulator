import { describe, it, expect } from 'vitest';
import { interpolateVehicles } from '../interpolation';
import type { Snapshot, SimulationStateDto, VehicleDto, RoadDto } from '../../types/simulation';
import { TICK_INTERVAL_MS, RENDER_SCALE } from '../constants';

/** Horizontal road from (0,0) to (1000,0), length=1000m, single lane */
const testRoads: RoadDto[] = [
  {
    id: 'r1',
    name: 'Test Road',
    laneCount: 1,
    length: 1000,
    speedLimit: 33.3,
    startX: 0,
    startY: 0,
    endX: 1000,
    endY: 0,
    clipStart: 0,
    clipEnd: 0,
  },
];

function makeVehicle(overrides: Partial<VehicleDto> = {}): VehicleDto {
  return {
    id: 'v1',
    roadId: 'r1',
    laneId: 'r1-lane0',
    laneIndex: 0,
    position: 200,
    speed: 10,
    laneChangeProgress: 1.0,
    laneChangeSourceIndex: -1,
    ...overrides,
  };
}

function makeSnapshot(
  vehicles: VehicleDto[],
  receivedAt: number
): Snapshot {
  const state: SimulationStateDto = {
    tick: 1,
    timestamp: Date.now(),
    status: 'RUNNING',
    vehicles,
    obstacles: [],
    trafficLights: [],
    stats: { vehicleCount: vehicles.length, avgSpeed: 0, density: 0, throughput: 0 },
    error: null,
    mapId: null,
  };
  return {
    state,
    receivedAt,
    vehicleMap: new Map(vehicles.map((v) => [v.id, v])),
  };
}

describe('interpolateVehicles', () => {
  it('returns projected positions when no previous snapshot', () => {
    // position=200 on road length=1000 -> fraction=0.2 -> x = (0 + 0.2*1000) * RENDER_SCALE = 600
    const curr = makeSnapshot([makeVehicle({ position: 200 })], 1000);
    const result = interpolateVehicles(curr, null, 1025, testRoads);
    expect(result).toHaveLength(1);
    expect(result[0].x).toBeCloseTo(200 * RENDER_SCALE, 1);
    expect(result[0].y).toBeCloseTo(0, 1);
  });

  it('interpolates at alpha=0.5 to midpoint', () => {
    // prev position=100, curr position=200, midpoint=150 -> x=150*RENDER_SCALE
    const prev = makeSnapshot([makeVehicle({ position: 100 })], 900);
    const curr = makeSnapshot([makeVehicle({ position: 200 })], 1000);
    const result = interpolateVehicles(curr, prev, 1000 + TICK_INTERVAL_MS / 2, testRoads);
    expect(result[0].x).toBeCloseTo(150 * RENDER_SCALE, 1);
  });

  it('clamps alpha to 1.0 when tick is late', () => {
    const prev = makeSnapshot([makeVehicle({ position: 100 })], 900);
    const curr = makeSnapshot([makeVehicle({ position: 200 })], 1000);
    const result = interpolateVehicles(curr, prev, 1000 + TICK_INTERVAL_MS * 3, testRoads);
    expect(result[0].x).toBeCloseTo(200 * RENDER_SCALE, 1);
  });

  it('handles newly spawned vehicles (not in prev)', () => {
    const prev = makeSnapshot([], 900);
    const curr = makeSnapshot([makeVehicle({ id: 'new', position: 50 })], 1000);
    const result = interpolateVehicles(curr, prev, 1025, testRoads);
    expect(result).toHaveLength(1);
    expect(result[0].x).toBeCloseTo(50 * RENDER_SCALE, 1);
  });

  it('does not include despawned vehicles (in prev but not curr)', () => {
    const prev = makeSnapshot([makeVehicle({ id: 'gone', position: 800 })], 900);
    const curr = makeSnapshot([], 1000);
    const result = interpolateVehicles(curr, prev, 1025, testRoads);
    expect(result).toHaveLength(0);
  });

  it('interpolates speed between snapshots', () => {
    const prev = makeSnapshot([makeVehicle({ speed: 10 })], 900);
    const curr = makeSnapshot([makeVehicle({ speed: 20 })], 1000);
    const result = interpolateVehicles(curr, prev, 1000 + TICK_INTERVAL_MS / 2, testRoads);
    expect(result[0].speed).toBeCloseTo(15, 1);
  });
});
