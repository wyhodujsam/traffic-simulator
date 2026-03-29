import type { VehicleDto, ObstacleDto, RoadDto } from '../types/simulation';

const LANE_WIDTH_PX = 14;

export interface ProjectedPosition {
  x: number;
  y: number;
  angle: number;
}

/**
 * Projects a vehicle from domain coordinates to pixel coordinates.
 * Uses road geometry (startX/Y, endX/Y) and lane index.
 */
export function projectVehicle(vehicle: VehicleDto, roads: RoadDto[]): ProjectedPosition {
  const road = roads.find(r => r.id === vehicle.roadId);
  if (!road) return { x: 0, y: 0, angle: 0 };

  const fraction = vehicle.position / road.length;
  const x = road.startX + fraction * (road.endX - road.startX);
  const yBase = road.startY + fraction * (road.endY - road.startY);

  const laneCount = road.laneCount;
  const targetOffset = (vehicle.laneIndex - (laneCount - 1) / 2) * LANE_WIDTH_PX;

  let y: number;
  if (vehicle.laneChangeSourceIndex >= 0 && vehicle.laneChangeProgress < 1.0) {
    const sourceOffset = (vehicle.laneChangeSourceIndex - (laneCount - 1) / 2) * LANE_WIDTH_PX;
    y = yBase + sourceOffset + vehicle.laneChangeProgress * (targetOffset - sourceOffset);
  } else {
    y = yBase + targetOffset;
  }

  const angle = Math.atan2(road.endY - road.startY, road.endX - road.startX);
  return { x, y, angle };
}

/**
 * Projects an obstacle from domain coordinates to pixel coordinates.
 */
export function projectObstacle(obstacle: ObstacleDto, roads: RoadDto[]): ProjectedPosition {
  const road = roads.find(r => r.id === obstacle.roadId);
  if (!road) return { x: 0, y: 0, angle: 0 };

  const fraction = obstacle.position / road.length;
  const x = road.startX + fraction * (road.endX - road.startX);
  const yBase = road.startY + fraction * (road.endY - road.startY);
  const laneOffset = (obstacle.laneIndex - (road.laneCount - 1) / 2) * LANE_WIDTH_PX;
  const y = yBase + laneOffset;
  const angle = Math.atan2(road.endY - road.startY, road.endX - road.startX);
  return { x, y, angle };
}
