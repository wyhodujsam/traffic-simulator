/** Phase 25 D-10: raw-Canvas axes/legends helper. No chart library. */

export interface AxisSpec {
  readonly ctx: CanvasRenderingContext2D;
  readonly originX: number;
  readonly originY: number;
  readonly width: number;
  readonly height: number;
  readonly xLabel: string;
  readonly yLabel: string;
  readonly xMax: number;
  readonly yMax: number;
  readonly xTicks: number;
  readonly yTicks: number;
}

export function drawAxes(spec: AxisSpec): void {
  const { ctx, originX, originY, width, height } = spec;
  ctx.save();
  ctx.strokeStyle = '#666';
  ctx.fillStyle = '#888';
  ctx.lineWidth = 1;
  ctx.font = '10px monospace';

  // Y axis
  ctx.beginPath();
  ctx.moveTo(originX, originY);
  ctx.lineTo(originX, originY - height);
  ctx.stroke();

  // X axis
  ctx.beginPath();
  ctx.moveTo(originX, originY);
  ctx.lineTo(originX + width, originY);
  ctx.stroke();

  // X tick labels
  for (let i = 0; i <= spec.xTicks; i++) {
    const tx = originX + (i / spec.xTicks) * width;
    const value = (spec.xMax * i / spec.xTicks).toFixed(0);
    ctx.fillText(value, tx - 6, originY + 12);
  }

  // Y tick labels
  for (let i = 0; i <= spec.yTicks; i++) {
    const ty = originY - (i / spec.yTicks) * height;
    const value = (spec.yMax * i / spec.yTicks).toFixed(0);
    ctx.fillText(value, originX - 32, ty + 4);
  }

  // Axis labels
  ctx.fillText(spec.xLabel, originX + width - 40, originY + 24);
  ctx.fillText(spec.yLabel, originX - 32, originY - height - 8);

  ctx.restore();
}

/** Maps speed (m/s) to color: green (fast) → red (slow). Matches main canvas pattern. */
export function speedToColor(speedMps: number, maxSpeedMps: number): string {
  const t = Math.max(0, Math.min(1, speedMps / Math.max(0.1, maxSpeedMps)));
  // t=0 → red (slow), t=1 → green (fast)
  const r = Math.floor(255 * (1 - t));
  const g = Math.floor(255 * t);
  return `rgb(${r},${g},0)`;
}
