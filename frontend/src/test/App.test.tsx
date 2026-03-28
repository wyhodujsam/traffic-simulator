import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import App from '../App';
import { useSimulationStore } from '../store/useSimulationStore';

vi.mock('../hooks/useWebSocket', () => ({
  useWebSocket: vi.fn(),
}));

describe('App', () => {
  beforeEach(() => {
    useSimulationStore.setState({ lastTick: null, tickCount: 0 });
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
    useSimulationStore.setState({ tickCount: 42, lastTick: { tick: 42, timestamp: Date.now() } });
    render(<App />);
    expect(screen.getByText('Tick count: 42')).toBeInTheDocument();
  });

  it('should show latency when tick is received', () => {
    const now = Date.now();
    useSimulationStore.setState({ tickCount: 1, lastTick: { tick: 1, timestamp: now } });
    render(<App />);
    expect(screen.getByText(/Latency:/)).toBeInTheDocument();
  });
});
