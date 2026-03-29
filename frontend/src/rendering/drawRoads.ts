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

  // Draw intersection boxes to cover overlapping road ends
  drawIntersectionBoxes(ctx, roads);
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

  // Closed lane hatching (if lane data available)
  if (road.lanes) {
    for (const lane of road.lanes) {
      if (!lane.active) {
        const laneY = -roadWidth / 2 + lane.laneIndex * LANE_WIDTH_PX;
        ctx.fillStyle = 'rgba(255, 60, 60, 0.3)';
        ctx.fillRect(0, laneY, roadLength, LANE_WIDTH_PX);

        // Diagonal hatching
        ctx.strokeStyle = 'rgba(255, 60, 60, 0.5)';
        ctx.lineWidth = 1;
        ctx.setLineDash([]);
        const spacing = 12;
        for (let xPos = 0; xPos < roadLength + LANE_WIDTH_PX; xPos += spacing) {
          ctx.beginPath();
          ctx.moveTo(xPos, laneY);
          ctx.lineTo(xPos - LANE_WIDTH_PX, laneY + LANE_WIDTH_PX);
          ctx.stroke();
        }
      }
    }
  }

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

/**
 * Finds intersection points (where multiple roads share an endpoint)
 * and draws a dark square to cover overlapping lane markings.
 */
function drawIntersectionBoxes(ctx: CanvasRenderingContext2D, roads: RoadDto[]): void {
  // Collect all road endpoints and group by proximity
  const points: { x: number; y: number; roadWidth: number }[] = [];
  for (const road of roads) {
    const w = road.laneCount * LANE_WIDTH_PX;
    points.push({ x: road.startX, y: road.startY, roadWidth: w });
    points.push({ x: road.endX, y: road.endY, roadWidth: w });
  }

  // Find clusters of endpoints within 25px of each other (intersection nodes)
  const used = new Set<number>();
  for (let i = 0; i < points.length; i++) {
    if (used.has(i)) continue;
    const cluster = [points[i]];
    used.add(i);
    for (let j = i + 1; j < points.length; j++) {
      if (used.has(j)) continue;
      const dx = points[i].x - points[j].x;
      const dy = points[i].y - points[j].y;
      if (Math.sqrt(dx * dx + dy * dy) < 25) {
        cluster.push(points[j]);
        used.add(j);
      }
    }
    // Only draw box if 3+ road endpoints converge (actual intersection)
    if (cluster.length >= 3) {
      const cx = cluster.reduce((s, p) => s + p.x, 0) / cluster.length;
      const cy = cluster.reduce((s, p) => s + p.y, 0) / cluster.length;
      const maxW = Math.max(...cluster.map(p => p.roadWidth));
      const boxSize = maxW + 4;

      ctx.fillStyle = '#3a3a3a';
      ctx.fillRect(cx - boxSize / 2, cy - boxSize / 2, boxSize, boxSize);

      // Subtle border
      ctx.strokeStyle = '#555';
      ctx.lineWidth = 1;
      ctx.setLineDash([]);
      ctx.strokeRect(cx - boxSize / 2, cy - boxSize / 2, boxSize, boxSize);
    }
  }
}
