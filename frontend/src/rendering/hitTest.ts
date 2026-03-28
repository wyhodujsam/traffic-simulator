import type { RoadDto, ObstacleDto } from '../types/simulation';
import { LANE_WIDTH_PX, OBSTACLE_LENGTH_PX, OBSTACLE_HIT_PADDING } from './constants';

export interface RoadHit {
  roadId: string;
  laneIndex: number;
  position: number; // metres along road
}

/**
 * Tests if a click point hits an existing obstacle.
 * Uses rotated AABB hit-testing with padding for better ergonomics.
 * Returns the obstacle ID if hit, or null.
 */
export function hitTestObstacle(
  cx: number,
  cy: number,
  obstacles: ObstacleDto[]
): string | null {
  const halfL = OBSTACLE_LENGTH_PX / 2 + OBSTACLE_HIT_PADDING;
  const halfW = LANE_WIDTH_PX / 2 + OBSTACLE_HIT_PADDING;

  for (const o of obstacles) {
    // Transform click into obstacle's local frame
    const dx = cx - o.x;
    const dy = cy - o.y;
    const cos = Math.cos(-o.angle);
    const sin = Math.sin(-o.angle);
    const localX = dx * cos - dy * sin;
    const localY = dx * sin + dy * cos;

    if (Math.abs(localX) <= halfL && Math.abs(localY) <= halfW) {
      return o.id;
    }
  }

  return null;
}

/**
 * Maps a canvas click point to a road/lane/position.
 * Uses vector projection onto each road to find the closest match.
 * Returns null if click is not on any road.
 */
export function hitTestRoad(
  cx: number,
  cy: number,
  roads: RoadDto[]
): RoadHit | null {
  let bestHit: RoadHit | null = null;
  let bestPerpDist = Infinity;

  for (const road of roads) {
    const dx = road.endX - road.startX;
    const dy = road.endY - road.startY;
    const roadLenPx = Math.sqrt(dx * dx + dy * dy);
    if (roadLenPx === 0) continue;

    // Unit vectors: along road and perpendicular
    const ux = dx / roadLenPx;
    const uy = dy / roadLenPx;
    const nx = -uy; // perpendicular
    const ny = ux;

    // Vector from road start to click
    const px = cx - road.startX;
    const py = cy - road.startY;

    // Project onto road axis
    const along = px * ux + py * uy;
    if (along < 0 || along > roadLenPx) continue;

    // Project onto perpendicular
    const perp = px * nx + py * ny;
    const halfWidth = (road.laneCount * LANE_WIDTH_PX) / 2;
    if (Math.abs(perp) > halfWidth) continue;

    // Lane index: 0 = topmost (most negative perp)
    const laneIndex = Math.floor((perp + halfWidth) / LANE_WIDTH_PX);
    const clampedLane = Math.max(0, Math.min(laneIndex, road.laneCount - 1));

    // Simulation position in metres
    const positionMetres = (along / roadLenPx) * road.length;

    const perpDist = Math.abs(perp);
    if (perpDist < bestPerpDist) {
      bestPerpDist = perpDist;
      bestHit = {
        roadId: road.id,
        laneIndex: clampedLane,
        position: positionMetres,
      };
    }
  }

  return bestHit;
}
