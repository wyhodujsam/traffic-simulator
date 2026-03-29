import type { Snapshot, VehicleDto, RoadDto } from '../types/simulation';
import { projectVehicle } from './projection';
import { TICK_INTERVAL_MS } from './constants';

export interface InterpolatedVehicle {
  id: string;
  x: number;
  y: number;
  angle: number;
  speed: number;
}

/**
 * Interpolates domain fields between two snapshots, then projects to pixels.
 * Alpha = time elapsed since currSnapshot was received / tick interval.
 */
export function interpolateVehicles(
  currSnapshot: Snapshot,
  prevSnapshot: Snapshot | null,
  now: number,
  roads: RoadDto[]
): InterpolatedVehicle[] {
  const elapsed = now - currSnapshot.receivedAt;
  const alpha = Math.min(Math.max(elapsed / TICK_INTERVAL_MS, 0), 1);

  const result: InterpolatedVehicle[] = [];

  for (const vehicle of currSnapshot.state.vehicles) {
    let interpolated: VehicleDto;

    if (prevSnapshot === null) {
      interpolated = vehicle;
    } else {
      const prev = prevSnapshot.vehicleMap.get(vehicle.id);
      if (prev === undefined) {
        // Newly spawned vehicle — no interpolation
        interpolated = vehicle;
      } else {
        // Interpolate domain fields
        interpolated = {
          ...vehicle,
          position: prev.position + alpha * (vehicle.position - prev.position),
          speed: prev.speed + alpha * (vehicle.speed - prev.speed),
          laneChangeProgress: prev.laneChangeProgress + alpha * (vehicle.laneChangeProgress - prev.laneChangeProgress),
        };
      }
    }

    const { x, y, angle } = projectVehicle(interpolated, roads);
    result.push({ id: interpolated.id, x, y, angle, speed: interpolated.speed });
  }

  return result;
}
