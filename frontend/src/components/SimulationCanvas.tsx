import { useEffect, useRef, useCallback } from 'react';
import { useSimulationStore } from '../store/useSimulationStore';
import { drawRoads } from '../rendering/drawRoads';
import { drawVehicles } from '../rendering/drawVehicles';
import { interpolateVehicles } from '../rendering/interpolation';
import { CANVAS_PADDING, LANE_WIDTH_PX } from '../rendering/constants';
import type { RoadDto } from '../types/simulation';

function computeCanvasSize(roads: RoadDto[]): { width: number; height: number } {
  if (roads.length === 0) return { width: 900, height: 600 };

  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  for (const road of roads) {
    const halfW = (road.laneCount * LANE_WIDTH_PX) / 2;
    minX = Math.min(minX, road.startX, road.endX);
    maxX = Math.max(maxX, road.startX, road.endX);
    minY = Math.min(minY, road.startY - halfW, road.endY - halfW);
    maxY = Math.max(maxY, road.startY + halfW, road.endY + halfW);
  }

  return {
    width: Math.ceil(maxX - minX + CANVAS_PADDING * 2),
    height: Math.ceil(maxY - minY + CANVAS_PADDING * 2),
  };
}

export function SimulationCanvas() {
  const roadsCanvasRef = useRef<HTMLCanvasElement>(null);
  const vehiclesCanvasRef = useRef<HTMLCanvasElement>(null);
  const rafRef = useRef<number>(0);

  const roads = useSimulationStore((s) => s.roads);
  const roadsLoaded = useSimulationStore((s) => s.roadsLoaded);

  const { width, height } = computeCanvasSize(roads);

  // Draw static roads layer once when roads are loaded
  useEffect(() => {
    if (!roadsLoaded || roads.length === 0) return;
    const canvas = roadsCanvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    drawRoads(ctx, roads);
  }, [roads, roadsLoaded]);

  // Animation loop for vehicles layer
  const renderLoop = useCallback(() => {
    const canvas = vehiclesCanvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const store = useSimulationStore.getState();
    const { currSnapshot, prevSnapshot } = store;

    if (currSnapshot) {
      const now = performance.now();
      const vehicles = interpolateVehicles(currSnapshot, prevSnapshot, now);
      drawVehicles(ctx, vehicles);
    } else {
      ctx.clearRect(0, 0, canvas.width, canvas.height);
    }

    rafRef.current = requestAnimationFrame(renderLoop);
  }, []);

  useEffect(() => {
    rafRef.current = requestAnimationFrame(renderLoop);
    return () => cancelAnimationFrame(rafRef.current);
  }, [renderLoop]);

  return (
    <div style={{ position: 'relative', width, height }}>
      <canvas
        ref={roadsCanvasRef}
        width={width}
        height={height}
        style={{ position: 'absolute', top: 0, left: 0 }}
      />
      <canvas
        ref={vehiclesCanvasRef}
        width={width}
        height={height}
        style={{ position: 'absolute', top: 0, left: 0 }}
      />
    </div>
  );
}
