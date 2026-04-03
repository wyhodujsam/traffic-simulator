import type { RoadDto } from '../types/simulation';
import { LANE_WIDTH_PX, RENDER_SCALE } from './constants';

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

}

/**
 * Detects parallel road pairs (in/out offset by ~LANE_WIDTH_PX) and returns
 * which boundary sides to skip (to make them look like a single 2-lane road).
 * Returns map: roadId → Set of sides to suppress ('top' or 'bottom').
 */
function findPairedBoundaries(roads: RoadDto[]): Map<string, Set<string>> {
  const result = new Map<string, Set<string>>();

  for (let i = 0; i < roads.length; i++) {
    for (let j = i + 1; j < roads.length; j++) {
      markPairedSides(roads[i], roads[j], result);
    }
  }
  return result;
}

function markPairedSides(a: RoadDto, b: RoadDto, result: Map<string, Set<string>>): void {
  const threshold = LANE_WIDTH_PX + 4;
  const aMidX = (a.startX + a.endX) / 2 * RENDER_SCALE;
  const aMidY = (a.startY + a.endY) / 2 * RENDER_SCALE;
  const bMidX = (b.startX + b.endX) / 2 * RENDER_SCALE;
  const bMidY = (b.startY + b.endY) / 2 * RENDER_SCALE;
  const midDist = Math.hypot(aMidX - bMidX, aMidY - bMidY);

  if (midDist > threshold * 2) return;

  const aDx = a.endX - a.startX, aDy = a.endY - a.startY;
  const bDx = b.endX - b.startX, bDy = b.endY - b.startY;
  const aLen = Math.hypot(aDx, aDy);
  const bLen = Math.hypot(bDx, bDy);
  if (aLen < 1 || bLen < 1) return;

  const dot = Math.abs((aDx * bDx + aDy * bDy) / (aLen * bLen));
  if (dot < 0.95) return;

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

function drawRoad(ctx: CanvasRenderingContext2D, road: RoadDto, suppressedSides: Set<string>): void {
  const dx = (road.endX - road.startX) * RENDER_SCALE;
  const dy = (road.endY - road.startY) * RENDER_SCALE;
  const roadLength = Math.hypot(dx, dy);
  const angle = Math.atan2(dy, dx);
  const roadWidth = road.laneCount * LANE_WIDTH_PX;

  const clipS = (road.clipStart ?? 0) * RENDER_SCALE;
  const clipE = (road.clipEnd ?? 0) * RENDER_SCALE;
  const visibleStart = clipS;
  const visibleLength = roadLength - clipS - clipE;
  if (visibleLength <= 0) return;
  const visibleEnd = visibleStart + visibleLength;

  ctx.save();
  ctx.translate(road.startX * RENDER_SCALE, road.startY * RENDER_SCALE);
  ctx.rotate(angle);

  ctx.fillStyle = '#3a3a3a';
  ctx.fillRect(visibleStart, -roadWidth / 2, visibleLength, roadWidth);

  drawClosedLaneHatching(ctx, road, roadWidth, visibleStart, visibleLength, visibleEnd);
  drawRoadBoundaries(ctx, roadWidth, visibleStart, visibleEnd, suppressedSides);
  drawLaneDividers(ctx, road.laneCount, roadWidth, visibleStart, visibleEnd);

  ctx.restore();
}

function drawClosedLaneHatching(ctx: CanvasRenderingContext2D, road: RoadDto,
    roadWidth: number, visibleStart: number, visibleLength: number, visibleEnd: number): void {
  if (!road.lanes) return;
  for (const lane of road.lanes) {
    if (lane.active) continue;
    const laneY = -roadWidth / 2 + lane.laneIndex * LANE_WIDTH_PX;
    ctx.fillStyle = 'rgba(255, 60, 60, 0.3)';
    ctx.fillRect(visibleStart, laneY, visibleLength, LANE_WIDTH_PX);

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

function drawRoadBoundaries(ctx: CanvasRenderingContext2D, roadWidth: number,
    visibleStart: number, visibleEnd: number, suppressedSides: Set<string>): void {
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
}

function drawLaneDividers(ctx: CanvasRenderingContext2D, laneCount: number,
    roadWidth: number, visibleStart: number, visibleEnd: number): void {
  ctx.strokeStyle = '#ffffff';
  ctx.lineWidth = 1;
  ctx.setLineDash([8, 12]);
  for (let i = 1; i < laneCount; i++) {
    const y = -roadWidth / 2 + i * LANE_WIDTH_PX;
    ctx.beginPath();
    ctx.moveTo(visibleStart, y);
    ctx.lineTo(visibleEnd, y);
    ctx.stroke();
  }
  ctx.setLineDash([]);
}
