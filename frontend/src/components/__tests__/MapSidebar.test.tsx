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

  it('renders Fetch roads (GraphHopper) button in idle state', () => {
    render(
      <MapSidebar
        bbox={bbox}
        state="idle"
        onFetchRoadsGh={() => {}}
      />
    );
    expect(
      screen.getByRole('button', { name: /Fetch roads \(GraphHopper\)/i })
    ).toBeInTheDocument();
  });

  it('clicking Fetch roads (GraphHopper) calls onFetchRoadsGh', () => {
    const spy = vi.fn();
    render(
      <MapSidebar
        bbox={bbox}
        state="idle"
        onFetchRoadsGh={spy}
      />
    );
    fireEvent.click(
      screen.getByRole('button', { name: /Fetch roads \(GraphHopper\)/i })
    );
    expect(spy).toHaveBeenCalledTimes(1);
  });
});

describe('MapSidebar result-state origin', () => {
  it('shows Overpass heading when resultOrigin=Overpass', () => {
    render(
      <MapSidebar
        bbox={bbox}
        state="result"
        result={{ roadCount: 3, intersectionCount: 2 }}
        resultOrigin="Overpass"
      />
    );
    // { exact: true } guards against future copy that might contain "Overpass" as a substring.
    expect(screen.getByText('Overpass', { exact: true })).toBeInTheDocument();
    expect(screen.getByText('3 roads, 2 intersections')).toBeInTheDocument();
  });

  it('shows GraphHopper heading when resultOrigin=GraphHopper', () => {
    render(
      <MapSidebar
        bbox={bbox}
        state="result"
        result={{ roadCount: 5, intersectionCount: 1 }}
        resultOrigin="GraphHopper"
      />
    );
    // { exact: true } prevents a false positive if later copy contains "GraphHopper" as a substring
    // (e.g. "GraphHopper diff", "GraphHopper warnings"). We want the heading match, not any occurrence.
    expect(screen.getByText('GraphHopper', { exact: true })).toBeInTheDocument();
    expect(screen.getByText('5 roads, 1 intersections')).toBeInTheDocument();
  });

  it('falls back to Roads loaded heading when resultOrigin is absent', () => {
    render(
      <MapSidebar
        bbox={bbox}
        state="result"
        result={{ roadCount: 2, intersectionCount: 0 }}
      />
    );
    expect(screen.getByText('Roads loaded', { exact: true })).toBeInTheDocument();
  });
});
