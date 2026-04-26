/** Phase 25 D-10: rolling 60s scatter of (density, flow) sampled once per simulated second. */
import type { SegmentKpiDto } from '../types/simulation';
import { drawAxes } from './diagramAxes';

const WINDOW_SECONDS = 60;
const SAMPLE_EVERY_TICKS = 20;     // once per simulated second
const PADDING = 40;

interface Sample { tickIndex: number; density: number; flow: number; }

export class FundamentalDiagramRenderer {
  private samples: Sample[] = [];
  private lastSampleTick = -1;

  ingest(currentTick: number, segmentKpis: SegmentKpiDto[] | undefined): void {
    if (!segmentKpis || segmentKpis.length === 0) return;
    if (currentTick - this.lastSampleTick < SAMPLE_EVERY_TICKS) return;
    this.lastSampleTick = currentTick;
    for (const s of segmentKpis) {
      this.samples.push({ tickIndex: currentTick, density: s.densityPerKm, flow: s.flowVehiclesPerMin });
    }
    const cutoff = currentTick - WINDOW_SECONDS * 20;
    this.samples = this.samples.filter(s => s.tickIndex >= cutoff);
  }

  render(canvas: HTMLCanvasElement): void {
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    const w = canvas.width;
    const h = canvas.height;
    ctx.clearRect(0, 0, w, h);
    ctx.fillStyle = '#111';
    ctx.fillRect(0, 0, w, h);

    const xMax = 60;     // veh/km
    const yMax = 1500;   // veh/min (rough)
    drawAxes({
      ctx, originX: PADDING, originY: h - PADDING,
      width: w - 2 * PADDING, height: h - 2 * PADDING,
      xLabel: 'density (veh/km)', yLabel: 'flow (veh/min)',
      xMax, yMax, xTicks: 6, yTicks: 5,
    });

    const xScale = (w - 2 * PADDING) / xMax;
    const yScale = (h - 2 * PADDING) / yMax;
    ctx.fillStyle = '#4af';
    for (const s of this.samples) {
      const x = PADDING + s.density * xScale;
      const y = (h - PADDING) - Math.min(yMax, s.flow) * yScale;
      ctx.beginPath();
      ctx.arc(x, y, 2, 0, 2 * Math.PI);
      ctx.fill();
    }
  }
}
