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

  // Find parallel road pairs (in/out with ~LANE_WIDTH_PX offset)
  const pairedSides = findPairedBoundaries(roads);

  for (const road of roads) {
    drawRoad(ctx, road, pairedSides.get(road.id) ?? new Set());
  }

  // Draw intersection boxes to cover overlapping road ends
  drawIntersectionBoxes(ctx, roads);
}

/**
 * Detects parallel road pairs (in/out offset by ~LANE_WIDTH_PX) and returns
 * which boundary sides to skip (to make them look like a single 2-lane road).
 * Returns map: roadId → Set of sides to suppress ('top' or 'bottom').
 */
function findPairedBoundaries(roads: RoadDto[]): Map<string, Set<string>> {
  const result = new Map<string, Set<string>>();
  const threshold = LANE_WIDTH_PX + 4; // allow some tolerance

  for (let i = 0; i < roads.length; i++) {
    for (let j = i + 1; j < roads.length; j++) {
      const a = roads[i], b = roads[j];
      // Check if roads are approximately parallel and close
      const aMidX = (a.startX + a.endX) / 2, aMidY = (a.startY + a.endY) / 2;
      const bMidX = (b.startX + b.endX) / 2, bMidY = (b.startY + b.endY) / 2;
      const midDist = Math.sqrt((aMidX - bMidX) ** 2 + (aMidY - bMidY) ** 2);

      if (midDist > threshold * 2) continue; // too far apart

      // Check if they share similar direction (parallel or antiparallel)
      const aDx = a.endX - a.startX, aDy = a.endY - a.startY;
      const bDx = b.endX - b.startX, bDy = b.endY - b.startY;
      const aLen = Math.sqrt(aDx * aDx + aDy * aDy);
      const bLen = Math.sqrt(bDx * bDx + bDy * bDy);
      if (aLen < 1 || bLen < 1) continue;

      const dot = Math.abs((aDx * bDx + aDy * bDy) / (aLen * bLen));
      if (dot < 0.95) continue; // not parallel enough

      // Determine which side is inner (facing the other road)
      // Cross product tells us which side b is on relative to a
      const cross = aDx * (bMidY - aMidY) - aDy * (bMidX - aMidX);

      if (!result.has(a.id)) result.set(a.id, new Set());
      if (!result.has(b.id)) result.set(b.id, new Set());

      if (cross > 0) {
        result.get(a.id)!.add('bottom');
        result.get(b.id)!.add('top');
      } else {
        result.get(a.id)!.add('top');
        result.get(b.id)!.add('bottom');
      }
    }
  }
  return result;
}

function drawRoad(ctx: CanvasRenderingContext2D, road: RoadDto, suppressedSides: Set<string>): void {
  const dx = road.endX - road.startX;
  const dy = road.endY - road.startY;
  const roadLength = Math.sqrt(dx * dx + dy * dy);
  const angle = Math.atan2(dy, dx);
  const roadWidth = road.laneCount * LANE_WIDTH_PX;

  // Clip road rendering at intersection boundaries
  const clipS = road.clipStart ?? 0;
  const clipE = road.clipEnd ?? 0;
  const visibleStart = clipS;
  const visibleLength = roadLength - clipS - clipE;
  if (visibleLength <= 0) return; // entire road is inside intersection
  const visibleEnd = visibleStart + visibleLength;

  ctx.save();
  ctx.translate(road.startX, road.startY);
  ctx.rotate(angle);

  // Road fill — dark gray
  ctx.fillStyle = '#3a3a3a';
  ctx.fillRect(visibleStart, -roadWidth / 2, visibleLength, roadWidth);

  // Closed lane hatching (if lane data available)
  if (road.lanes) {
    for (const lane of road.lanes) {
      if (!lane.active) {
        const laneY = -roadWidth / 2 + lane.laneIndex * LANE_WIDTH_PX;
        ctx.fillStyle = 'rgba(255, 60, 60, 0.3)';
        ctx.fillRect(visibleStart, laneY, visibleLength, LANE_WIDTH_PX);

        // Diagonal hatching
        ctx.strokeStyle = 'rgba(255, 60, 60, 0.5)';
        ctx.lineWidth = 1;
        ctx.setLineDash([]);
        const spacing = 12;
        for (let xPos = visibleStart; xPos < visibleEnd + LANE_WIDTH_PX; xPos += spacing) {
          ctx.beginPath();
          ctx.moveTo(xPos, laneY);
          ctx.lineTo(xPos - LANE_WIDTH_PX, laneY + LANE_WIDTH_PX);
          ctx.stroke();
        }
      }
    }
  }

  // Road boundaries — solid white lines (skip inner side for paired roads)
  ctx.strokeStyle = '#ffffff';
  ctx.lineWidth = 2;
  ctx.beginPath();
  if (!suppressedSides.has('top')) {
    ctx.moveTo(visibleStart, -roadWidth / 2);
    ctx.lineTo(visibleEnd, -roadWidth / 2);
  }
  if (!suppressedSides.has('bottom')) {
    ctx.moveTo(visibleStart, roadWidth / 2);
    ctx.lineTo(visibleEnd, roadWidth / 2);
  }
  ctx.stroke();

  // Dashed center line between paired roads (replace suppressed boundary)
  if (suppressedSides.has('top') || suppressedSides.has('bottom')) {
    ctx.strokeStyle = '#ffff00';
    ctx.lineWidth = 1;
    ctx.setLineDash([10, 10]);
    const y = suppressedSides.has('top') ? -roadWidth / 2 : roadWidth / 2;
    ctx.beginPath();
    ctx.moveTo(visibleStart, y);
    ctx.lineTo(visibleEnd, y);
    ctx.stroke();
    ctx.setLineDash([]);
  }

  // Lane dividers — white dashed lines between lanes
  ctx.strokeStyle = '#ffffff';
  ctx.lineWidth = 1;
  ctx.setLineDash([8, 12]);
  for (let i = 1; i < road.laneCount; i++) {
    const y = -roadWidth / 2 + i * LANE_WIDTH_PX;
    ctx.beginPath();
    ctx.moveTo(visibleStart, y);
    ctx.lineTo(visibleEnd, y);
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
      // Box must cover ALL road ends converging here — needs to be wide enough
      // for 2 road widths per direction + offset between in/out pairs
      const boxSize = Math.max(maxW * 4, 60);

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
