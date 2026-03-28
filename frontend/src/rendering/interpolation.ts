import type { Snapshot } from '../types/simulation';
import { TICK_INTERVAL_MS } from './constants';

export interface InterpolatedVehicle {
  id: string;
  x: number;
  y: number;
  angle: number;
  speed: number;
}

/**
 * Computes interpolated vehicle positions between two snapshots.
 * Alpha = time elapsed since currSnapshot was received / tick interval.
 * Returns array of interpolated vehicles for rendering.
 */
export function interpolateVehicles(
  currSnapshot: Snapshot,
  prevSnapshot: Snapshot | null,
  now: number
): InterpolatedVehicle[] {
  const elapsed = now - currSnapshot.receivedAt;
  const alpha = Math.min(Math.max(elapsed / TICK_INTERVAL_MS, 0), 1);

  const result: InterpolatedVehicle[] = [];

  for (const vehicle of currSnapshot.state.vehicles) {
    if (prevSnapshot === null) {
      result.push({
        id: vehicle.id,
        x: vehicle.x,
        y: vehicle.y,
        angle: vehicle.angle,
        speed: vehicle.speed,
      });
      continue;
    }

    const prev = prevSnapshot.vehicleMap.get(vehicle.id);
    if (prev === undefined) {
      // Newly spawned vehicle — no interpolation
      result.push({
        id: vehicle.id,
        x: vehicle.x,
        y: vehicle.y,
        angle: vehicle.angle,
        speed: vehicle.speed,
      });
    } else {
      result.push({
        id: vehicle.id,
        x: prev.x + alpha * (vehicle.x - prev.x),
        y: prev.y + alpha * (vehicle.y - prev.y),
        angle: vehicle.angle,
        speed: prev.speed + alpha * (vehicle.speed - prev.speed),
      });
    }
  }

  return result;
}
