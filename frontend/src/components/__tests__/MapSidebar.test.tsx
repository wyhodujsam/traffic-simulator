import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import '@testing-library/jest-dom';
import { MapSidebar } from '../MapSidebar';
import type { BboxInfo } from '../BoundingBoxMap';

const bbox: BboxInfo = {
  south: 52.5,
  west: 21.0,
  north: 52.51,
  east: 21.01,
  widthMeters: 700,
  heightMeters: 1100,
};

describe('MapSidebar idle actions', () => {
  it('renders AI Vision (component library) button in idle state', () => {
    render(
      <MapSidebar
        bbox={bbox}
        state="idle"
        onAnalyzeComponents={() => {}}
      />
    );
    expect(
      screen.getByRole('button', { name: /AI Vision \(component library\)/i })
    ).toBeInTheDocument();
  });

  it('clicking the new button calls onAnalyzeComponents', () => {
    const spy = vi.fn();
    render(
      <MapSidebar
        bbox={bbox}
        state="idle"
        onAnalyzeComponents={spy}
      />
    );
    fireEvent.click(
      screen.getByRole('button', { name: /AI Vision \(component library\)/i })
    );
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it('existing buttons still fire their callbacks', () => {
    const onFetchRoads = vi.fn();
    const onAnalyzeBbox = vi.fn();
    render(
      <MapSidebar
        bbox={bbox}
        state="idle"
        onFetchRoads={onFetchRoads}
        onAnalyzeBbox={onAnalyzeBbox}
      />
    );
    fireEvent.click(screen.getByRole('button', { name: /^Fetch Roads$/i }));
    fireEvent.click(screen.getByRole('button', { name: /AI Vision \(from bbox\)/i }));
    expect(onFetchRoads).toHaveBeenCalledTimes(1);
    expect(onAnalyzeBbox).toHaveBeenCalledTimes(1);
  });
});
