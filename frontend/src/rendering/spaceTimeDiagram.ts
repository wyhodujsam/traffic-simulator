/** Phase 25 D-10: rolling 30s buffer of per-road vehicle positions, colored by speed. */
import type { Snapshot } from '../types/simulation';
import { drawAxes, speedToColor } from './diagramAxes';

const BUFFER_TICKS = 600;          // 30s @ 20Hz per D-10
const SPEED_LIMIT_MPS = 22.2;      // approximate; could be parameterized
const PADDING = 40;

interface Cell { tickIndex: number; pos: number; speed: number; }

export class SpaceTimeRenderer {
  private buffer: Cell[] = [];
  private currentTick = 0;

  ingest(snapshot: Snapshot): void {
    this.currentTick = snapshot.state.tick;
    for (const v of snapshot.state.vehicles) {
      this.buffer.push({ tickIndex: this.currentTick, pos: v.position, speed: v.speed });
    }
    // Evict entries older than BUFFER_TICKS
    const cutoff = this.currentTick - BUFFER_TICKS;
    this.buffer = this.buffer.filter(c => c.tickIndex >= cutoff);
  }

  render(canvas: HTMLCanvasElement): void {
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    const w = canvas.width;
    const h = canvas.height;
    ctx.clearRect(0, 0, w, h);
    ctx.fillStyle = '#111';
    ctx.fillRect(0, 0, w, h);

    drawAxes({
      ctx, originX: PADDING, originY: h - PADDING,
      width: w - 2 * PADDING, height: h - 2 * PADDING,
      xLabel: 'time (s)', yLabel: 'position (m)',
      xMax: BUFFER_TICKS / 20, yMax: 250,    // 30s × per-segment 250m
      xTicks: 5, yTicks: 5,
    });

    // Plot each cell as a 2x2 px dot
    const xScale = (w - 2 * PADDING) / BUFFER_TICKS;
    const yScale = (h - 2 * PADDING) / 250;
    for (const c of this.buffer) {
      const x = PADDING + (BUFFER_TICKS - (this.currentTick - c.tickIndex)) * xScale;
      const y = (h - PADDING) - c.pos * yScale;
      ctx.fillStyle = speedToColor(c.speed, SPEED_LIMIT_MPS);
      ctx.fillRect(x, y, 2, 2);
    }
  }
}
