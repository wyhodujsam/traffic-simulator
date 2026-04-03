import type { InterpolatedVehicle } from './interpolation';
import { VEHICLE_LENGTH_PX, VEHICLE_WIDTH_PX } from './constants';

// --- Deterministic hash from vehicle UUID → stable per-vehicle properties ---
function hashId(id: string): number {
  let h = 0;
  for (let i = 0; i < id.length; i++) {
    h = Math.trunc((h << 5) - h + (id.codePointAt(i) ?? 0));
  }
  return Math.abs(h);
}

// --- Car body colors (realistic paint jobs) ---
const BODY_COLORS = [
  '#1a1a2e', // dark navy
  '#e63946', // red
  '#f1faee', // white/cream
  '#457b9d', // steel blue
  '#2a9d8f', // teal
  '#264653', // charcoal
  '#e9c46a', // gold/yellow
  '#f4a261', // orange
  '#6d6875', // grey-purple
  '#3d405b', // slate
  '#81b29a', // sage green
  '#b5838d', // dusty rose
  '#023047', // deep blue
  '#8ecae6', // sky blue
  '#c1121f', // crimson
  '#606c38', // olive
];

const enum VehicleType { Sedan, SUV, Sports, Truck, Van }

function getVehicleType(hash: number): VehicleType {
  const r = hash % 100;
  if (r < 40) return VehicleType.Sedan;
  if (r < 60) return VehicleType.SUV;
  if (r < 75) return VehicleType.Sports;
  if (r < 90) return VehicleType.Truck;
  return VehicleType.Van;
}

// --- Drawing functions per vehicle type (top-down view, facing RIGHT) ---
// Origin at vehicle center. Length along X, width along Y.

function drawSedan(ctx: CanvasRenderingContext2D, L: number, W: number, color: string) {
  const hL = L / 2, hW = W / 2;

  // Body — rounded rectangle
  ctx.fillStyle = color;
  ctx.beginPath();
  ctx.roundRect(-hL, -hW, L, W, 1.2);
  ctx.fill();

  // Windshield (front) — lighter
  ctx.fillStyle = '#7ec8e3';
  ctx.fillRect(hL * 0.35, -hW * 0.7, L * 0.18, W * 0.7);

  // Rear window
  ctx.fillRect(-hL * 0.75, -hW * 0.65, L * 0.12, W * 0.65);

  // Headlights (front)
  ctx.fillStyle = '#ffffcc';
  ctx.fillRect(hL - 1.2, -hW + 0.3, 1.2, 1);
  ctx.fillRect(hL - 1.2, hW - 1.3, 1.2, 1);

  // Taillights (rear)
  ctx.fillStyle = '#ff3333';
  ctx.fillRect(-hL, -hW + 0.3, 1, 0.8);
  ctx.fillRect(-hL, hW - 1.1, 1, 0.8);
}

function drawSUV(ctx: CanvasRenderingContext2D, L: number, W: number, color: string) {
  const suvL = L * 1.1, suvW = W * 1.1;
  const shL = suvL / 2, shW = suvW / 2;

  // Body — bulkier, less rounded
  ctx.fillStyle = color;
  ctx.beginPath();
  ctx.roundRect(-shL, -shW, suvL, suvW, 0.8);
  ctx.fill();

  // Roof rack lines
  ctx.strokeStyle = 'rgba(0,0,0,0.25)';
  ctx.lineWidth = 0.4;
  ctx.beginPath();
  ctx.moveTo(-shL * 0.3, -shW * 0.5);
  ctx.lineTo(shL * 0.3, -shW * 0.5);
  ctx.moveTo(-shL * 0.3, shW * 0.5);
  ctx.lineTo(shL * 0.3, shW * 0.5);
  ctx.stroke();

  // Windshield
  ctx.fillStyle = '#7ec8e3';
  ctx.fillRect(shL * 0.3, -shW * 0.7, suvL * 0.15, suvW * 0.7);

  // Rear window
  ctx.fillRect(-shL * 0.7, -shW * 0.6, suvL * 0.1, suvW * 0.6);

  // Headlights
  ctx.fillStyle = '#ffffcc';
  ctx.fillRect(shL - 1.3, -shW + 0.4, 1.3, 1.2);
  ctx.fillRect(shL - 1.3, shW - 1.6, 1.3, 1.2);

  // Taillights
  ctx.fillStyle = '#ff3333';
  ctx.fillRect(-shL, -shW + 0.4, 1, 1);
  ctx.fillRect(-shL, shW - 1.4, 1, 1);
}

function drawSports(ctx: CanvasRenderingContext2D, L: number, W: number, color: string) {
  const hL = L / 2, hW = W / 2;

  // Sleek body — tapered front
  ctx.fillStyle = color;
  ctx.beginPath();
  ctx.moveTo(-hL, -hW * 0.85);
  ctx.lineTo(hL * 0.5, -hW);
  ctx.quadraticCurveTo(hL + 1, 0, hL * 0.5, hW);
  ctx.lineTo(-hL, hW * 0.85);
  ctx.quadraticCurveTo(-hL - 0.5, 0, -hL, -hW * 0.85);
  ctx.fill();

  // Racing stripe
  ctx.fillStyle = 'rgba(255,255,255,0.2)';
  ctx.fillRect(-hL * 0.8, -0.4, L * 0.85, 0.8);

  // Windshield
  ctx.fillStyle = '#5bb8d4';
  ctx.beginPath();
  ctx.moveTo(hL * 0.2, -hW * 0.6);
  ctx.lineTo(hL * 0.5, -hW * 0.7);
  ctx.lineTo(hL * 0.5, hW * 0.7);
  ctx.lineTo(hL * 0.2, hW * 0.6);
  ctx.fill();

  // Headlights — narrow, aggressive
  ctx.fillStyle = '#ffffdd';
  ctx.fillRect(hL * 0.5, -hW * 0.85, 1, 0.7);
  ctx.fillRect(hL * 0.5, hW * 0.15, 1, 0.7);

  // Taillights
  ctx.fillStyle = '#ff2222';
  ctx.fillRect(-hL * 0.9, -hW * 0.7, 0.8, 0.6);
  ctx.fillRect(-hL * 0.9, hW * 0.1, 0.8, 0.6);
}

function drawTruck(ctx: CanvasRenderingContext2D, L: number, W: number, color: string) {
  const tL = L * 1.15, tW = W * 1.05;
  const thL = tL / 2, thW = tW / 2;

  // Cab (front third)
  ctx.fillStyle = color;
  ctx.beginPath();
  ctx.roundRect(thL * 0.2, -thW, tL * 0.3, tW, 0.8);
  ctx.fill();

  // Cargo bed (rear two-thirds) — darker
  ctx.fillStyle = shadeColor(color, -30);
  ctx.fillRect(-thL, -thW, tL * 0.65, tW);

  // Cargo bed border
  ctx.strokeStyle = 'rgba(0,0,0,0.3)';
  ctx.lineWidth = 0.4;
  ctx.strokeRect(-thL, -thW, tL * 0.65, tW);

  // Windshield
  ctx.fillStyle = '#7ec8e3';
  ctx.fillRect(thL * 0.55, -thW * 0.65, tL * 0.1, tW * 0.65);

  // Headlights
  ctx.fillStyle = '#ffffcc';
  ctx.fillRect(thL - 1, -thW + 0.3, 1, 1.2);
  ctx.fillRect(thL - 1, thW - 1.5, 1, 1.2);

  // Taillights
  ctx.fillStyle = '#ff4444';
  ctx.fillRect(-thL, -thW + 0.3, 0.8, 1);
  ctx.fillRect(-thL, thW - 1.3, 0.8, 1);
}

function drawVan(ctx: CanvasRenderingContext2D, L: number, W: number, color: string) {
  const vL = L * 1.1, vW = W * 1.08;
  const vhL = vL / 2, vhW = vW / 2;

  // Body — boxy, tall
  ctx.fillStyle = color;
  ctx.beginPath();
  ctx.roundRect(-vhL, -vhW, vL, vW, 0.6);
  ctx.fill();

  // Side panel line
  ctx.strokeStyle = 'rgba(0,0,0,0.15)';
  ctx.lineWidth = 0.3;
  ctx.beginPath();
  ctx.moveTo(-vhL * 0.2, -vhW);
  ctx.lineTo(-vhL * 0.2, vhW);
  ctx.stroke();

  // Windshield (wide, flat)
  ctx.fillStyle = '#7ec8e3';
  ctx.fillRect(vhL * 0.4, -vhW * 0.75, vL * 0.15, vW * 0.75);

  // Headlights
  ctx.fillStyle = '#ffffcc';
  ctx.fillRect(vhL - 1, -vhW + 0.5, 1, 1);
  ctx.fillRect(vhL - 1, vhW - 1.5, 1, 1);

  // Taillights
  ctx.fillStyle = '#ff3333';
  ctx.fillRect(-vhL, -vhW + 0.5, 0.8, 0.8);
  ctx.fillRect(-vhL, vhW - 1.3, 0.8, 0.8);
}

function shadeColor(hex: string, percent: number): string {
  const num = Number.parseInt(hex.replace('#', ''), 16);
  const r = Math.min(255, Math.max(0, (num >> 16) + percent));
  const g = Math.min(255, Math.max(0, ((num >> 8) & 0x00ff) + percent));
  const b = Math.min(255, Math.max(0, (num & 0x0000ff) + percent));
  return `rgb(${r},${g},${b})`;
}

// --- Brake light glow when decelerating ---
function drawBrakeLights(ctx: CanvasRenderingContext2D, L: number, W: number, speed: number) {
  if (speed > 0.5) return; // only when nearly stopped or stopped
  const hL = L / 2, hW = W / 2;
  ctx.fillStyle = 'rgba(255,50,50,0.6)';
  ctx.beginPath();
  ctx.arc(-hL, -hW + 0.8, 1.2, 0, Math.PI * 2);
  ctx.arc(-hL, hW - 0.8, 1.2, 0, Math.PI * 2);
  ctx.fill();
}

/**
 * Draws all vehicles on the dynamic canvas layer.
 * Each vehicle gets a deterministic type and color based on its UUID.
 */
export function drawVehicles(
  ctx: CanvasRenderingContext2D,
  vehicles: InterpolatedVehicle[]
): void {
  ctx.clearRect(0, 0, ctx.canvas.width, ctx.canvas.height);

  for (const v of vehicles) {
    const hash = hashId(v.id);
    const type = getVehicleType(hash);
    const color = BODY_COLORS[hash % BODY_COLORS.length];

    ctx.save();
    ctx.translate(v.x, v.y);
    ctx.rotate(v.angle);

    switch (type) {
      case VehicleType.Sedan:
        drawSedan(ctx, VEHICLE_LENGTH_PX, VEHICLE_WIDTH_PX, color);
        break;
      case VehicleType.SUV:
        drawSUV(ctx, VEHICLE_LENGTH_PX, VEHICLE_WIDTH_PX, color);
        break;
      case VehicleType.Sports:
        drawSports(ctx, VEHICLE_LENGTH_PX, VEHICLE_WIDTH_PX, color);
        break;
      case VehicleType.Truck:
        drawTruck(ctx, VEHICLE_LENGTH_PX, VEHICLE_WIDTH_PX, color);
        break;
      case VehicleType.Van:
        drawVan(ctx, VEHICLE_LENGTH_PX, VEHICLE_WIDTH_PX, color);
        break;
    }

    drawBrakeLights(ctx, VEHICLE_LENGTH_PX, VEHICLE_WIDTH_PX, v.speed);

    ctx.restore();
  }
}
