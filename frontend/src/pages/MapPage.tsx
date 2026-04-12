import { useState, useRef, useCallback } from 'react';
import { BoundingBoxMap, BboxInfo } from '../components/BoundingBoxMap';
import { MapSidebar } from '../components/MapSidebar';
import { useIsMobile } from '../hooks/useIsMobile';

export function MapPage() {
  const isMobile = useIsMobile();
  const [bbox, setBbox] = useState<BboxInfo | null>(null);
  const [sidebarState, setSidebarState] = useState<'idle' | 'loading' | 'result' | 'error'>('idle');
  const [fetchResult, setFetchResult] = useState<{ roadCount: number; intersectionCount: number } | null>(null);
  const [fetchError, setFetchError] = useState<string | null>(null);
  const [mapConfig, setMapConfig] = useState<unknown>(null);

  const mapViewRef = useRef<{ center: [number, number]; zoom: number }>({
    center: [52.2297, 21.0122],
    zoom: 14,
  });

  const handleViewChange = (center: [number, number], zoom: number) => {
    mapViewRef.current = { center, zoom };
  };

  const handleFetchRoads = useCallback(async () => {
    if (!bbox) return;
    setSidebarState('loading');
    setFetchError(null);
    setFetchResult(null);
    try {
      const response = await fetch('/api/osm/fetch-roads', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          south: bbox.south,
          west: bbox.west,
          north: bbox.north,
          east: bbox.east,
        }),
      });
      if (!response.ok) {
        const err = await response.json().catch(() => ({ error: 'Unknown error' }));
        throw new Error((err as { error?: string }).error ?? `HTTP ${response.status}`);
      }
      const config = await response.json() as { roads?: unknown[]; intersections?: unknown[] };
      setMapConfig(config);
      setFetchResult({
        roadCount: config.roads?.length ?? 0,
        intersectionCount: config.intersections?.length ?? 0,
      });
      setSidebarState('result');
    } catch (e: unknown) {
      const message = e instanceof Error ? e.message : 'Failed to fetch roads';
      setFetchError(message);
      setSidebarState('error');
    }
  }, [bbox]);

  const handleReset = useCallback(() => {
    setSidebarState('idle');
    setFetchError(null);
    setFetchResult(null);
    setMapConfig(null);
  }, []);

  const sidebarStyle: React.CSSProperties = isMobile
    ? {
        width: '100%',
        minWidth: '0',
        borderLeft: 'none',
        borderTop: '1px solid #333',
        flexShrink: 0,
      }
    : {
        width: '280px',
        minWidth: '280px',
        flexShrink: 0,
        borderLeft: '1px solid #333',
        overflow: 'auto',
      };

  // mapConfig stored for Phase 19 wiring
  void mapConfig;

  return (
    <div style={{
      flex: 1,
      display: 'flex',
      flexDirection: isMobile ? 'column' : 'row',
      overflow: 'hidden',
      minHeight: 0,
    }}>
      <div style={{
        flex: 1,
        minWidth: 0,
        position: 'relative',
      }}>
        <div style={{ position: 'absolute', inset: 0 }}>
        <BoundingBoxMap
          center={mapViewRef.current.center}
          zoom={mapViewRef.current.zoom}
          onBoundsChange={setBbox}
          onViewChange={handleViewChange}
        />
        </div>
      </div>

      <div style={sidebarStyle}>
        <MapSidebar
          bbox={bbox}
          state={sidebarState}
          onFetchRoads={handleFetchRoads}
          result={fetchResult}
          error={fetchError}
          onReset={handleReset}
        />
      </div>
    </div>
  );
}
