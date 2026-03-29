import { useEffect, useRef, useCallback } from 'react';
import { useSimulationStore } from '../store/useSimulationStore';
import { drawRoads } from '../rendering/drawRoads';
import { drawVehicles } from '../rendering/drawVehicles';
import { drawObstacles } from '../rendering/drawObstacles';
import { drawTrafficLights } from '../rendering/drawTrafficLights';
import { hitTestObstacle, hitTestRoad } from '../rendering/hitTest';
import { interpolateVehicles } from '../rendering/interpolation';
import { CANVAS_PADDING, LANE_WIDTH_PX } from '../rendering/constants';
import type { RoadDto } from '../types/simulation';

export function computeCanvasSize(roads: RoadDto[]): { width: number; height: number } {
  if (roads.length === 0) return { width: 900, height: 600 };

  let maxX = -Infinity, maxY = -Infinity;
  for (const road of roads) {
    const halfW = (road.laneCount * LANE_WIDTH_PX) / 2;
    maxX = Math.max(maxX, road.startX, road.endX);
    maxY = Math.max(maxY, road.startY + halfW, road.endY + halfW);
  }

  return {
    width: Math.ceil(maxX + CANVAS_PADDING),
    height: Math.ceil(maxY + CANVAS_PADDING),
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

      const obstacles = useSimulationStore.getState().obstacles;
      drawObstacles(ctx, obstacles);

      const trafficLights = useSimulationStore.getState().trafficLights;
      drawTrafficLights(ctx, trafficLights);
    } else {
      ctx.clearRect(0, 0, canvas.width, canvas.height);
    }

    rafRef.current = requestAnimationFrame(renderLoop);
  }, []);

  useEffect(() => {
    rafRef.current = requestAnimationFrame(renderLoop);
    return () => cancelAnimationFrame(rafRef.current);
  }, [renderLoop]);

  const handleCanvasClick = useCallback((e: React.MouseEvent<HTMLCanvasElement>) => {
    const canvas = vehiclesCanvasRef.current;
    if (!canvas) return;
    const rect = canvas.getBoundingClientRect();
    const cx = e.clientX - rect.left;
    const cy = e.clientY - rect.top;

    const store = useSimulationStore.getState();
    const { sendCommand, obstacles } = store;
    if (!sendCommand) return;

    // 1. Check if click hits an existing obstacle -> REMOVE
    const hitId = hitTestObstacle(cx, cy, obstacles);
    if (hitId) {
      sendCommand({ type: 'REMOVE_OBSTACLE', obstacleId: hitId });
      return;
    }

    // 2. Otherwise, map to road/lane/position -> ADD
    const hit = hitTestRoad(cx, cy, roads);
    if (hit) {
      sendCommand({
        type: 'ADD_OBSTACLE',
        roadId: hit.roadId,
        laneIndex: hit.laneIndex,
        position: hit.position,
      });
    }
  }, [roads]);

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
        onClick={handleCanvasClick}
        style={{ position: 'absolute', top: 0, left: 0, cursor: 'crosshair' }}
      />
    </div>
  );
}
