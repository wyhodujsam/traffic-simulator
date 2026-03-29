import type { ObstacleDto, RoadDto } from '../types/simulation';
import { projectObstacle } from './projection';
import { OBSTACLE_LENGTH_PX, LANE_WIDTH_PX } from './constants';

/**
 * Draws all obstacles on the dynamic canvas layer.
 * Each obstacle is a red rectangle spanning the full lane width
 * with a white X-pattern for visual distinction from vehicles.
 */
export function drawObstacles(
  ctx: CanvasRenderingContext2D,
  obstacles: ObstacleDto[],
  roads: RoadDto[]
): void {
  for (const o of obstacles) {
    const { x, y, angle } = projectObstacle(o, roads);

    ctx.save();
    ctx.translate(x, y);
    ctx.rotate(angle);

    const halfL = OBSTACLE_LENGTH_PX / 2;
    const halfW = LANE_WIDTH_PX / 2;

    // Red rectangle blocking lane
    ctx.fillStyle = '#ff3333';
    ctx.fillRect(-halfL, -halfW, OBSTACLE_LENGTH_PX, LANE_WIDTH_PX);

    // White X-pattern for visual distinction
    ctx.strokeStyle = '#ffffff';
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.moveTo(-halfL, -halfW);
    ctx.lineTo(halfL, halfW);
    ctx.moveTo(halfL, -halfW);
    ctx.lineTo(-halfL, halfW);
    ctx.stroke();

    ctx.restore();
  }
}
