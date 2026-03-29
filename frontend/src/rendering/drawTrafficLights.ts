import type { TrafficLightDto } from '../types/simulation';
import { LANE_WIDTH_PX } from './constants';

/** Radius of each traffic light indicator circle */
const LIGHT_RADIUS = 4;
/** Spacing between the three light circles */
const LIGHT_SPACING = 10;

const COLORS = {
  GREEN: { active: '#00ff00', dim: '#003300' },
  YELLOW: { active: '#ffff00', dim: '#333300' },
  RED: { active: '#ff0000', dim: '#330000' },
} as const;

/**
 * Draws traffic light indicators at each approach road's stop line.
 * Drawn on the dynamic (vehicles) layer since state changes per tick.
 */
export function drawTrafficLights(
  ctx: CanvasRenderingContext2D,
  trafficLights: TrafficLightDto[]
): void {
  for (const tl of trafficLights) {
    ctx.save();
    ctx.translate(tl.x, tl.y);
    ctx.rotate(tl.angle);

    // Position lights perpendicular to road direction, offset to the side
    const offsetX = -LIGHT_SPACING * 1.5;
    const offsetY = LANE_WIDTH_PX;

    // Background box
    ctx.fillStyle = '#1a1a1a';
    ctx.fillRect(offsetX - LIGHT_RADIUS - 2, offsetY - LIGHT_RADIUS - 2,
      LIGHT_SPACING * 3 + LIGHT_RADIUS * 2 + 4, LIGHT_RADIUS * 2 + 4);

    // Draw three circles: RED, YELLOW, GREEN
    const states: Array<'RED' | 'YELLOW' | 'GREEN'> = ['RED', 'YELLOW', 'GREEN'];
    for (let i = 0; i < states.length; i++) {
      const s = states[i];
      const isActive = tl.state === s;
      ctx.beginPath();
      ctx.arc(offsetX + i * LIGHT_SPACING, offsetY, LIGHT_RADIUS, 0, Math.PI * 2);
      ctx.fillStyle = isActive ? COLORS[s].active : COLORS[s].dim;
      ctx.fill();
    }

    ctx.restore();
  }
}
