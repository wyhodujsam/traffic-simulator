import type { IntersectionDto } from '../types/simulation';
import { RENDER_SCALE } from './constants';

/**
 * Draws intersection shapes on the static roads canvas layer.
 * ROUNDABOUT nodes: draws a composite roundabout circle from grouped ring nodes.
 * Others: filled rounded rectangle.
 */
export function drawIntersections(
  ctx: CanvasRenderingContext2D,
  intersections: IntersectionDto[]
): void {
  // Separate roundabout ring nodes from regular intersections
  const roundaboutNodes = intersections.filter(i => i.type === 'ROUNDABOUT');
  const regularNodes = intersections.filter(i => i.type !== 'ROUNDABOUT');

  // Draw roundabout visual from ring node positions
  if (roundaboutNodes.length >= 3) {
    drawRoundaboutFromNodes(ctx, roundaboutNodes);
  }

  // Draw roundabout ring node junctions (small, same color as road)
  for (const ixtn of roundaboutNodes) {
    if (ixtn.size <= 0) continue;
    drawSmallJunction(ctx, ixtn);
  }

  // Draw regular intersections
  for (const ixtn of regularNodes) {
    if (ixtn.size <= 0) continue;
    drawBox(ctx, ixtn);
  }
}

function drawBox(ctx: CanvasRenderingContext2D, ixtn: IntersectionDto): void {
  const size = ixtn.size * RENDER_SCALE;
  const halfSize = size / 2;
  const x = ixtn.x * RENDER_SCALE - halfSize;
  const y = ixtn.y * RENDER_SCALE - halfSize;
  const cornerRadius = 4 * RENDER_SCALE;

  ctx.fillStyle = '#3a3a3a';
  ctx.beginPath();
  ctx.roundRect(x, y, size, size, cornerRadius);
  ctx.fill();

  ctx.strokeStyle = '#4a4a4a';
  ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.roundRect(x, y, size, size, cornerRadius);
  ctx.stroke();
}

/** Small junction at ring node — just fills the gap between roads */
function drawSmallJunction(ctx: CanvasRenderingContext2D, ixtn: IntersectionDto): void {
  const halfSize = ixtn.size * RENDER_SCALE / 2;
  ctx.fillStyle = '#3a3a3a';
  ctx.beginPath();
  ctx.arc(ixtn.x * RENDER_SCALE, ixtn.y * RENDER_SCALE, halfSize, 0, Math.PI * 2);
  ctx.fill();
}

/** Draws the roundabout visual: computes center and radius from ring node positions */
function drawRoundaboutFromNodes(ctx: CanvasRenderingContext2D, nodes: IntersectionDto[]): void {
  // Compute center as average of all ring node positions (scaled)
  let cx = 0, cy = 0;
  for (const n of nodes) { cx += n.x * RENDER_SCALE; cy += n.y * RENDER_SCALE; }
  cx /= nodes.length;
  cy /= nodes.length;

  // Compute radius as average distance from center to ring nodes + margin
  let avgDist = 0;
  for (const n of nodes) {
    avgDist += Math.hypot(n.x * RENDER_SCALE - cx, n.y * RENDER_SCALE - cy);
  }
  avgDist /= nodes.length;
  const outerRadius = avgDist + 10 * RENDER_SCALE;
  const innerRadius = avgDist * 0.35;

  // Filled circle — road surface color
  ctx.fillStyle = '#3a3a3a';
  ctx.beginPath();
  ctx.arc(cx, cy, outerRadius, 0, Math.PI * 2);
  ctx.fill();

  // Outer ring — solid white border
  ctx.strokeStyle = '#ffffff';
  ctx.lineWidth = 2;
  ctx.beginPath();
  ctx.arc(cx, cy, outerRadius, 0, Math.PI * 2);
  ctx.stroke();

  // Inner island — green circle
  ctx.fillStyle = '#2a5a2a';
  ctx.beginPath();
  ctx.arc(cx, cy, innerRadius, 0, Math.PI * 2);
  ctx.fill();

  // Inner island border
  ctx.strokeStyle = '#ffffff';
  ctx.lineWidth = 1;
  ctx.setLineDash([4, 4]);
  ctx.beginPath();
  ctx.arc(cx, cy, innerRadius, 0, Math.PI * 2);
  ctx.stroke();
  ctx.setLineDash([]);
}
