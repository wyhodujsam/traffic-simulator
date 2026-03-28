import type { RoadDto } from '../types/simulation';
import { LANE_WIDTH_PX } from './constants';

/**
 * Draws all roads on the static canvas layer.
 * Roads are drawn as dark gray rectangles with white lane markings.
 */
export function drawRoads(ctx: CanvasRenderingContext2D, roads: RoadDto[]): void {
  ctx.clearRect(0, 0, ctx.canvas.width, ctx.canvas.height);

  // Background
  ctx.fillStyle = '#1a1a2e';
  ctx.fillRect(0, 0, ctx.canvas.width, ctx.canvas.height);

  for (const road of roads) {
    drawRoad(ctx, road);
  }
}

function drawRoad(ctx: CanvasRenderingContext2D, road: RoadDto): void {
  const dx = road.endX - road.startX;
  const dy = road.endY - road.startY;
  const roadLength = Math.sqrt(dx * dx + dy * dy);
  const angle = Math.atan2(dy, dx);
  const roadWidth = road.laneCount * LANE_WIDTH_PX;

  ctx.save();
  ctx.translate(road.startX, road.startY);
  ctx.rotate(angle);

  // Road fill — dark gray
  ctx.fillStyle = '#3a3a3a';
  ctx.fillRect(0, -roadWidth / 2, roadLength, roadWidth);

  // Road boundaries — solid white lines
  ctx.strokeStyle = '#ffffff';
  ctx.lineWidth = 2;
  ctx.beginPath();
  ctx.moveTo(0, -roadWidth / 2);
  ctx.lineTo(roadLength, -roadWidth / 2);
  ctx.moveTo(0, roadWidth / 2);
  ctx.lineTo(roadLength, roadWidth / 2);
  ctx.stroke();

  // Lane dividers — white dashed lines between lanes
  ctx.strokeStyle = '#ffffff';
  ctx.lineWidth = 1;
  ctx.setLineDash([8, 12]);
  for (let i = 1; i < road.laneCount; i++) {
    const y = -roadWidth / 2 + i * LANE_WIDTH_PX;
    ctx.beginPath();
    ctx.moveTo(0, y);
    ctx.lineTo(roadLength, y);
    ctx.stroke();
  }
  ctx.setLineDash([]);

  ctx.restore();
}
