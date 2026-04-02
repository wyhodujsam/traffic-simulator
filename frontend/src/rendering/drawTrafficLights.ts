import type { TrafficLightDto } from '../types/simulation';
import { LANE_WIDTH_PX } from './constants';

/** Radius of each traffic light indicator circle */
const LIGHT_RADIUS = 4;
/** Spacing between the three light circles */
const LIGHT_SPACING = 10;
/** Pixels to offset backward from intersection center to stop line */
const STOP_LINE_OFFSET = 25;
/** Offset perpendicular to road direction (places light to the side of road) */
const ROAD_SIDE_OFFSET = LANE_WIDTH_PX * 1.2;

const COLORS = {
  GREEN: { active: '#00ff00', dim: '#003300' },
  YELLOW: { active: '#ffff00', dim: '#333300' },
  RED: { active: '#ff0000', dim: '#330000' },
} as const;

/**
 * Draws traffic light indicators at each approach road's stop line.
 * Lights are positioned back from the intersection center along the road
 * direction and offset to the side for clear road association.
 * When box-blocking is active (GREEN but outbound roads full), an orange
 * border and "B" label indicate why vehicles are stopped.
 *
 * Drawn on the dynamic (vehicles) layer since state changes per tick.
 */
export function drawTrafficLights(
  ctx: CanvasRenderingContext2D,
  trafficLights: TrafficLightDto[]
): void {
  for (const tl of trafficLights) {
    const cos = Math.cos(tl.angle);
    const sin = Math.sin(tl.angle);

    // Offset backward along road direction to stop line position
    const stopX = tl.x - cos * STOP_LINE_OFFSET;
    const stopY = tl.y - sin * STOP_LINE_OFFSET;

    // Offset perpendicular (to the right of the road direction)
    const perpX = -sin * ROAD_SIDE_OFFSET;
    const perpY = cos * ROAD_SIDE_OFFSET;

    // Connection line from light to road stop line (world coordinates)
    ctx.beginPath();
    ctx.moveTo(stopX + perpX, stopY + perpY);
    ctx.lineTo(stopX, stopY);
    ctx.strokeStyle = tl.state === 'GREEN' ? '#00aa00' : tl.state === 'RED' ? '#aa0000' : '#aaaa00';
    ctx.lineWidth = 1.5;
    ctx.stroke();

    ctx.save();
    ctx.translate(stopX + perpX, stopY + perpY);
    ctx.rotate(tl.angle);

    // Draw background box for the 3 lights
    const boxW = LIGHT_SPACING * 2 + LIGHT_RADIUS * 2 + 6;
    const boxH = LIGHT_RADIUS * 2 + 6;
    ctx.fillStyle = '#1a1a1a';
    ctx.fillRect(-boxW / 2, -boxH / 2, boxW, boxH);

    // Draw three circles: RED, YELLOW, GREEN (left to right)
    const states: Array<'RED' | 'YELLOW' | 'GREEN'> = ['RED', 'YELLOW', 'GREEN'];
    for (let i = 0; i < states.length; i++) {
      const s = states[i];
      const isActive = tl.state === s;
      ctx.beginPath();
      ctx.arc(-LIGHT_SPACING + i * LIGHT_SPACING, 0, LIGHT_RADIUS, 0, Math.PI * 2);
      ctx.fillStyle = isActive ? COLORS[s].active : COLORS[s].dim;
      ctx.fill();
    }

    // Box-blocking indicator: orange border when green but blocked
    if (tl.boxBlocked && tl.state === 'GREEN') {
      ctx.strokeStyle = '#ff8800';
      ctx.lineWidth = 2;
      ctx.strokeRect(-boxW / 2, -boxH / 2, boxW, boxH);
      // Small "B" label above the light
      ctx.fillStyle = '#ff8800';
      ctx.font = 'bold 8px monospace';
      ctx.textAlign = 'center';
      ctx.fillText('B', 0, -boxH / 2 - 3);
    }

    ctx.restore();
  }
}
