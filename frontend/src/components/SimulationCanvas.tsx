import { useEffect, useRef, useCallback, useState } from 'react';
import { useSimulationStore } from '../store/useSimulationStore';
import { drawRoads } from '../rendering/drawRoads';
import { drawIntersections } from '../rendering/drawIntersections';
import { drawVehicles } from '../rendering/drawVehicles';
import { drawObstacles } from '../rendering/drawObstacles';
import { drawTrafficLights } from '../rendering/drawTrafficLights';
import { hitTestObstacle, hitTestRoad } from '../rendering/hitTest';
import { interpolateVehicles } from '../rendering/interpolation';
import { CANVAS_PADDING, LANE_WIDTH_PX, RENDER_SCALE } from '../rendering/constants';
import type { RoadDto } from '../types/simulation';

export function computeCanvasSize(roads: RoadDto[]): { width: number; height: number } {
  if (roads.length === 0) return { width: 900, height: 600 };

  let maxX = -Infinity, maxY = -Infinity;
  for (const road of roads) {
    const halfW = (road.laneCount * LANE_WIDTH_PX) / 2;
    maxX = Math.max(maxX, road.startX * RENDER_SCALE, road.endX * RENDER_SCALE);
    maxY = Math.max(maxY, road.startY * RENDER_SCALE + halfW, road.endY * RENDER_SCALE + halfW);
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
  const containerRef = useRef<HTMLDivElement>(null);

  const roads = useSimulationStore((s) => s.roads);
  const roadsLoaded = useSimulationStore((s) => s.roadsLoaded);
  const intersections = useSimulationStore((s) => s.intersections);

  const { width, height } = computeCanvasSize(roads);

  // Track container dimensions for scaling
  const [containerSize, setContainerSize] = useState<{ width: number; height: number }>({
    width: 900,
    height: 600,
  });

  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;

    const observer = new ResizeObserver((entries) => {
      for (const entry of entries) {
        const { width: w, height: h } = entry.contentRect;
        if (w > 0 && h > 0) {
          setContainerSize({ width: w, height: h });
        }
      }
    });

    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  // Compute CSS scale factor to fill container while preserving aspect ratio
  const scaleX = containerSize.width / width;
  const scaleY = containerSize.height / height;
  const finalScale = Math.max(0.3, Math.min(scaleX, scaleY, 3));

  // Draw static roads layer once when roads are loaded
  useEffect(() => {
    if (!roadsLoaded || roads.length === 0) return;
    const canvas = roadsCanvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    drawRoads(ctx, roads);
    drawIntersections(ctx, intersections);
  }, [roads, roadsLoaded, intersections]);

  // Pause animation when tab is hidden (saves CPU, prevents stale interpolation)
  const visibleRef = useRef(true);
  useEffect(() => {
    const handler = () => { visibleRef.current = !document.hidden; };
    document.addEventListener('visibilitychange', handler);
    return () => document.removeEventListener('visibilitychange', handler);
  }, []);

  // Animation loop for vehicles layer
  const renderLoop = useCallback(() => {
    rafRef.current = requestAnimationFrame(renderLoop);

    // Skip rendering when tab is hidden
    if (!visibleRef.current) return;

    const canvas = vehiclesCanvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const store = useSimulationStore.getState();
    const { currSnapshot, prevSnapshot } = store;

    if (currSnapshot) {
      const now = performance.now();
      const storeRoads = useSimulationStore.getState().roads;
      const vehicles = interpolateVehicles(currSnapshot, prevSnapshot, now, storeRoads);
      drawVehicles(ctx, vehicles);

      const obstacles = useSimulationStore.getState().obstacles;
      drawObstacles(ctx, obstacles, storeRoads);

      const trafficLights = useSimulationStore.getState().trafficLights;
      drawTrafficLights(ctx, trafficLights);
    } else {
      ctx.clearRect(0, 0, canvas.width, canvas.height);
    }
  }, []);

  useEffect(() => {
    rafRef.current = requestAnimationFrame(renderLoop);
    return () => cancelAnimationFrame(rafRef.current);
  }, [renderLoop]);

  const handleCanvasClick = useCallback((e: React.MouseEvent<HTMLCanvasElement>) => {
    const canvas = vehiclesCanvasRef.current;
    if (!canvas) return;
    const rect = canvas.getBoundingClientRect();
    // getBoundingClientRect returns the CSS-scaled size; compensate to get canvas pixel coords
    const cx = (e.clientX - rect.left) * (canvas.width / rect.width);
    const cy = (e.clientY - rect.top) * (canvas.height / rect.height);

    const store = useSimulationStore.getState();
    const { sendCommand, obstacles } = store;
    if (!sendCommand) return;

    // 1. Check if click hits an existing obstacle -> REMOVE
    const hitId = hitTestObstacle(cx, cy, obstacles, roads);
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

  // Scaled dimensions for layout — transform doesn't affect layout box,
  // so we set the outer wrapper to the scaled size and use transformOrigin top-left
  const scaledWidth = Math.ceil(width * finalScale);
  const scaledHeight = Math.ceil(height * finalScale);

  return (
    <div
      ref={containerRef}
      style={{
        width: '100%',
        height: '100%',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        overflow: 'hidden',
      }}
    >
      <div style={{
        width: scaledWidth,
        height: scaledHeight,
        flexShrink: 0,
        position: 'relative',
      }}>
        <div style={{
          position: 'absolute',
          top: 0,
          left: 0,
          width,
          height,
          transform: `scale(${finalScale})`,
          transformOrigin: 'top left',
        }}>
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
      </div>
    </div>
  );
}
