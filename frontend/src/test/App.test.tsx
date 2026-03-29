import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import App from '../App';
import { useSimulationStore } from '../store/useSimulationStore';

vi.mock('../hooks/useWebSocket', () => ({
  useWebSocket: vi.fn(),
}));

vi.mock('../components/SimulationCanvas', () => ({
  SimulationCanvas: () => <div data-testid="canvas">Canvas</div>,
}));

vi.mock('../components/ControlsPanel', () => ({
  ControlsPanel: () => <div data-testid="controls">Controls</div>,
}));

vi.mock('../components/StatsPanel', () => ({
  StatsPanel: () => <div data-testid="stats">Stats</div>,
}));

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

  it('should render SimulationCanvas', () => {
    render(<App />);
    expect(screen.getByTestId('canvas')).toBeInTheDocument();
  });

  it('should render ControlsPanel', () => {
    render(<App />);
    expect(screen.getByTestId('controls')).toBeInTheDocument();
  });

  it('should render StatsPanel', () => {
    render(<App />);
    expect(screen.getByTestId('stats')).toBeInTheDocument();
  });
});
