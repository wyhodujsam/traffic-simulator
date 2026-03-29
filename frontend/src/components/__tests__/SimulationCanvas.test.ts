import { describe, it, expect } from 'vitest';
import { computeCanvasSize } from '../SimulationCanvas';
import { CANVAS_PADDING, LANE_WIDTH_PX } from '../../rendering/constants';
import type { RoadDto } from '../../types/simulation';

const STRAIGHT_ROAD: RoadDto = {
  id: 'r1',
  name: 'Main Road',
  laneCount: 3,
  length: 800,
  speedLimit: 33.3,
  startX: 50,
  startY: 300,
  endX: 850,
  endY: 300,
};

describe('computeCanvasSize', () => {
  it('returns default size when no roads', () => {
    const { width, height } = computeCanvasSize([]);
    expect(width).toBe(900);
    expect(height).toBe(600);
  });

  it('canvas width contains road endX + padding', () => {
    const { width } = computeCanvasSize([STRAIGHT_ROAD]);
    expect(width).toBeGreaterThanOrEqual(STRAIGHT_ROAD.endX + CANVAS_PADDING);
  });

  it('canvas height contains road startY + half road width + padding', () => {
    const halfW = (STRAIGHT_ROAD.laneCount * LANE_WIDTH_PX) / 2;
    const { height } = computeCanvasSize([STRAIGHT_ROAD]);
    expect(height).toBeGreaterThanOrEqual(STRAIGHT_ROAD.startY + halfW + CANVAS_PADDING);
  });

  it('road at y=300 is inside canvas bounds (the bug fix)', () => {
    const { height } = computeCanvasSize([STRAIGHT_ROAD]);
    // Road center is at y=300, vehicles at ~286..314
    // Canvas must be tall enough to contain them
    expect(height).toBeGreaterThan(300);
  });

  it('vehicles at road coords are within canvas', () => {
    const { width, height } = computeCanvasSize([STRAIGHT_ROAD]);
    // Vehicle x range: 50..850, y range: ~286..314
    const laneOffset = ((STRAIGHT_ROAD.laneCount - 1) / 2) * LANE_WIDTH_PX;
    const maxVehicleY = STRAIGHT_ROAD.startY + laneOffset;
    const maxVehicleX = STRAIGHT_ROAD.endX;

    expect(width).toBeGreaterThan(maxVehicleX);
    expect(height).toBeGreaterThan(maxVehicleY);
  });

  it('handles multiple roads', () => {
    const road2: RoadDto = {
      id: 'r2',
      name: 'Side Road',
      laneCount: 2,
      length: 400,
      speedLimit: 20,
      startX: 400,
      startY: 100,
      endX: 400,
      endY: 500,
    };
    const { width, height } = computeCanvasSize([STRAIGHT_ROAD, road2]);
    expect(width).toBeGreaterThanOrEqual(850 + CANVAS_PADDING);
    const halfW2 = (road2.laneCount * LANE_WIDTH_PX) / 2;
    expect(height).toBeGreaterThanOrEqual(road2.endY + halfW2 + CANVAS_PADDING);
  });

  it('handles road with startX=0 startY=0', () => {
    const road: RoadDto = {
      id: 'r0',
      name: 'Origin Road',
      laneCount: 1,
      length: 200,
      speedLimit: 15,
      startX: 0,
      startY: 0,
      endX: 200,
      endY: 0,
    };
    const { width, height } = computeCanvasSize([road]);
    expect(width).toBeGreaterThanOrEqual(200 + CANVAS_PADDING);
    expect(height).toBeGreaterThanOrEqual(LANE_WIDTH_PX / 2 + CANVAS_PADDING);
  });
});
