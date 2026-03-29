import type { IntersectionDto } from '../types/simulation';

/**
 * Draws intersection boxes on the static roads canvas layer.
 * Each intersection is a filled dark gray rounded rectangle
 * that visually connects the clipped road ends.
 */
export function drawIntersections(
  ctx: CanvasRenderingContext2D,
  intersections: IntersectionDto[]
): void {
  for (const ixtn of intersections) {
    if (ixtn.size <= 0) continue;

    const halfSize = ixtn.size / 2;
    const x = ixtn.x - halfSize;
    const y = ixtn.y - halfSize;
    const cornerRadius = 4;

    // Filled intersection area — same color as road surface
    ctx.fillStyle = '#3a3a3a';
    ctx.beginPath();
    ctx.roundRect(x, y, ixtn.size, ixtn.size, cornerRadius);
    ctx.fill();

    // Subtle border
    ctx.strokeStyle = '#4a4a4a';
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.roundRect(x, y, ixtn.size, ixtn.size, cornerRadius);
    ctx.stroke();
  }
}
