import { describe, it, expect } from 'vitest';
import { interpolateVehicles } from '../interpolation';
import type { Snapshot, SimulationStateDto, VehicleDto } from '../../types/simulation';
import { TICK_INTERVAL_MS } from '../constants';

function makeVehicle(overrides: Partial<VehicleDto> = {}): VehicleDto {
  return {
    id: 'v1',
    laneId: 'r1-lane0',
    position: 100,
    speed: 10,
    x: 200,
    y: 300,
    angle: 0,
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
    stats: { vehicleCount: vehicles.length, avgSpeed: 0, density: 0, throughput: 0 },
  };
  return {
    state,
    receivedAt,
    vehicleMap: new Map(vehicles.map((v) => [v.id, v])),
  };
}

describe('interpolateVehicles', () => {
  it('returns current positions when no previous snapshot', () => {
    const curr = makeSnapshot([makeVehicle({ x: 200, y: 300 })], 1000);
    const result = interpolateVehicles(curr, null, 1025);
    expect(result).toHaveLength(1);
    expect(result[0].x).toBe(200);
    expect(result[0].y).toBe(300);
  });

  it('interpolates at alpha=0.5 to midpoint', () => {
    const prev = makeSnapshot([makeVehicle({ x: 100, y: 300 })], 900);
    const curr = makeSnapshot([makeVehicle({ x: 200, y: 300 })], 1000);
    const result = interpolateVehicles(curr, prev, 1000 + TICK_INTERVAL_MS / 2);
    expect(result[0].x).toBeCloseTo(150, 1);
  });

  it('clamps alpha to 1.0 when tick is late', () => {
    const prev = makeSnapshot([makeVehicle({ x: 100, y: 300 })], 900);
    const curr = makeSnapshot([makeVehicle({ x: 200, y: 300 })], 1000);
    const result = interpolateVehicles(curr, prev, 1000 + TICK_INTERVAL_MS * 3);
    expect(result[0].x).toBe(200);
  });

  it('handles newly spawned vehicles (not in prev)', () => {
    const prev = makeSnapshot([], 900);
    const curr = makeSnapshot([makeVehicle({ id: 'new', x: 50, y: 300 })], 1000);
    const result = interpolateVehicles(curr, prev, 1025);
    expect(result).toHaveLength(1);
    expect(result[0].x).toBe(50);
  });

  it('does not include despawned vehicles (in prev but not curr)', () => {
    const prev = makeSnapshot([makeVehicle({ id: 'gone', x: 800 })], 900);
    const curr = makeSnapshot([], 1000);
    const result = interpolateVehicles(curr, prev, 1025);
    expect(result).toHaveLength(0);
  });

  it('interpolates speed between snapshots', () => {
    const prev = makeSnapshot([makeVehicle({ speed: 10 })], 900);
    const curr = makeSnapshot([makeVehicle({ speed: 20 })], 1000);
    const result = interpolateVehicles(curr, prev, 1000 + TICK_INTERVAL_MS / 2);
    expect(result[0].speed).toBeCloseTo(15, 1);
  });
});
