import type { InterpolatedVehicle } from './interpolation';
import { VEHICLE_LENGTH_PX, VEHICLE_WIDTH_PX, DEFAULT_MAX_SPEED } from './constants';

/**
 * Returns HSL color string based on vehicle speed.
 * Green (120) = fast (at max speed), Yellow (60) = medium, Red (0) = stopped.
 */
function speedColor(speed: number): string {
  const ratio = Math.min(Math.max(speed / DEFAULT_MAX_SPEED, 0), 1);
  const hue = ratio * 120; // 0=red, 60=yellow, 120=green
  return `hsl(${hue}, 80%, 50%)`;
}

/**
 * Draws all vehicles on the dynamic canvas layer.
 * Each vehicle is a colored rectangle rotated to match road direction.
 */
export function drawVehicles(
  ctx: CanvasRenderingContext2D,
  vehicles: InterpolatedVehicle[]
): void {
  ctx.clearRect(0, 0, ctx.canvas.width, ctx.canvas.height);

  for (const v of vehicles) {
    ctx.save();
    ctx.translate(v.x, v.y);
    ctx.rotate(v.angle);

    ctx.fillStyle = speedColor(v.speed);
    ctx.fillRect(
      -VEHICLE_LENGTH_PX / 2,
      -VEHICLE_WIDTH_PX / 2,
      VEHICLE_LENGTH_PX,
      VEHICLE_WIDTH_PX
    );

    ctx.restore();
  }
}
