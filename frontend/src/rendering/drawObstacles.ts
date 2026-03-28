import type { ObstacleDto } from '../types/simulation';
import { OBSTACLE_LENGTH_PX, LANE_WIDTH_PX } from './constants';

/**
 * Draws all obstacles on the dynamic canvas layer.
 * Each obstacle is a red rectangle spanning the full lane width
 * with a white X-pattern for visual distinction from vehicles.
 */
export function drawObstacles(
  ctx: CanvasRenderingContext2D,
  obstacles: ObstacleDto[]
): void {
  for (const o of obstacles) {
    ctx.save();
    ctx.translate(o.x, o.y);
    ctx.rotate(o.angle);

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
