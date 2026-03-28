import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import '@testing-library/jest-dom';
import { StatsPanel } from '../StatsPanel';
import { useSimulationStore } from '../../store/useSimulationStore';

describe('StatsPanel', () => {
  beforeEach(() => {
    useSimulationStore.setState({
      stats: null,
      tickCount: 0,
    });
  });

  it('shows waiting message when no stats', () => {
    render(<StatsPanel />);
    expect(screen.getByText('Waiting for data...')).toBeInTheDocument();
  });

  it('displays formatted stats when data available', () => {
    useSimulationStore.setState({
      stats: {
        vehicleCount: 15,
        avgSpeed: 20.0,   // 20 m/s = 72 km/h
        density: 18.75,
        throughput: 42,
      },
      tickCount: 100,
    });

    render(<StatsPanel />);
    expect(screen.getByText('15')).toBeInTheDocument();
    expect(screen.getByText('72.0 km/h')).toBeInTheDocument();
    expect(screen.getByText('18.8 veh/km')).toBeInTheDocument();
    expect(screen.getByText('42 veh/min')).toBeInTheDocument();
    expect(screen.getByText('100')).toBeInTheDocument();
  });

  it('handles zero stats gracefully', () => {
    useSimulationStore.setState({
      stats: {
        vehicleCount: 0,
        avgSpeed: 0,
        density: 0,
        throughput: 0,
      },
      tickCount: 1,
    });

    render(<StatsPanel />);
    expect(screen.getByText('0.0 km/h')).toBeInTheDocument();
    expect(screen.getByText('0.0 veh/km')).toBeInTheDocument();
  });
});
