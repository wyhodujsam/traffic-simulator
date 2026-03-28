import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import App from '../App';
import { useSimulationStore } from '../store/useSimulationStore';
import type { SimulationStateDto, Snapshot } from '../types/simulation';

vi.mock('../hooks/useWebSocket', () => ({
  useWebSocket: vi.fn(),
}));

function makeSnapshot(tick: number, timestamp: number): Snapshot {
  const state: SimulationStateDto = {
    tick,
    timestamp,
    status: 'RUNNING',
    vehicles: [],
    stats: { vehicleCount: 0, avgSpeed: 0, density: 0, throughput: 0 },
  };
  return {
    state,
    receivedAt: performance.now(),
    vehicleMap: new Map(),
  };
}

describe('App', () => {
  beforeEach(() => {
    useSimulationStore.setState({
      status: 'STOPPED',
      currSnapshot: null,
      prevSnapshot: null,
      tickCount: 0,
      stats: null,
      roads: [],
      roadsLoaded: false,
      sendCommand: null,
    });
  });

  it('should render title', () => {
    render(<App />);
    expect(screen.getByText('Traffic Simulator')).toBeInTheDocument();
  });

  it('should show waiting message when no tick received', () => {
    render(<App />);
    expect(screen.getByText(/waiting for connection/)).toBeInTheDocument();
  });

  it('should show tick count', () => {
    useSimulationStore.setState({ tickCount: 42, currSnapshot: makeSnapshot(42, Date.now()) });
    render(<App />);
    expect(screen.getByText('Tick count: 42')).toBeInTheDocument();
  });

  it('should show latency when tick is received', () => {
    const now = Date.now();
    useSimulationStore.setState({ tickCount: 1, currSnapshot: makeSnapshot(1, now) });
    render(<App />);
    expect(screen.getByText(/Latency:/)).toBeInTheDocument();
  });
});
