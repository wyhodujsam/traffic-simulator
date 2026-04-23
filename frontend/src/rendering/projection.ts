import type { VehicleDto, ObstacleDto, RoadDto } from '../types/simulation';
import { LANE_WIDTH_PX, RENDER_SCALE } from './constants';

export interface ProjectedPosition {
  x: number;
  y: number;
  angle: number;
}

function applyLateralOffset(road: RoadDto, x: number, y: number): { x: number; y: number } {
  const lateral = road.lateralOffset ?? 0;
  if (!lateral) return { x, y };
  const dx = road.endX - road.startX;
  const dy = road.endY - road.startY;
  const len = Math.hypot(dx, dy) || 1;
  // Perpendicular pointing right of driving direction (matches drawRoads after rotate).
  const perpX = -dy / len;
  const perpY = dx / len;
  const shift = lateral * RENDER_SCALE;
  return { x: x + perpX * shift, y: y + perpY * shift };
}

/**
 * Projects a vehicle from domain coordinates to pixel coordinates.
 * Uses road geometry (startX/Y, endX/Y) and lane index, scaled by RENDER_SCALE.
 */
export function projectVehicle(vehicle: VehicleDto, roads: RoadDto[]): ProjectedPosition {
  const road = roads.find(r => r.id === vehicle.roadId);
  if (!road) return { x: 0, y: 0, angle: 0 };

  const fraction = vehicle.position / road.length;
  const x = (road.startX + fraction * (road.endX - road.startX)) * RENDER_SCALE;
  const yBase = (road.startY + fraction * (road.endY - road.startY)) * RENDER_SCALE;

  const laneCount = road.laneCount;
  const targetOffset = (vehicle.laneIndex - (laneCount - 1) / 2) * LANE_WIDTH_PX;

  let y: number;
  if (vehicle.laneChangeSourceIndex >= 0 && vehicle.laneChangeProgress < 1) {
    const sourceOffset = (vehicle.laneChangeSourceIndex - (laneCount - 1) / 2) * LANE_WIDTH_PX;
    y = yBase + sourceOffset + vehicle.laneChangeProgress * (targetOffset - sourceOffset);
  } else {
    y = yBase + targetOffset;
  }

  const angle = Math.atan2(road.endY - road.startY, road.endX - road.startX);
  const shifted = applyLateralOffset(road, x, y);
  return { x: shifted.x, y: shifted.y, angle };
}

/**
 * Projects an obstacle from domain coordinates to pixel coordinates.
 */
export function projectObstacle(obstacle: ObstacleDto, roads: RoadDto[]): ProjectedPosition {
  const road = roads.find(r => r.id === obstacle.roadId);
  if (!road) return { x: 0, y: 0, angle: 0 };

  const fraction = obstacle.position / road.length;
  const x = (road.startX + fraction * (road.endX - road.startX)) * RENDER_SCALE;
  const yBase = (road.startY + fraction * (road.endY - road.startY)) * RENDER_SCALE;
  const laneOffset = (obstacle.laneIndex - (road.laneCount - 1) / 2) * LANE_WIDTH_PX;
  const y = yBase + laneOffset;
  const angle = Math.atan2(road.endY - road.startY, road.endX - road.startX);
  const shifted = applyLateralOffset(road, x, y);
  return { x: shifted.x, y: shifted.y, angle };
}
