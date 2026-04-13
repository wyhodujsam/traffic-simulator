import { useState, useRef, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { BoundingBoxMap, BboxInfo } from '../components/BoundingBoxMap';
import { MapSidebar } from '../components/MapSidebar';
import { RoadGraphPreview } from '../components/RoadGraphPreview';
import { useIsMobile } from '../hooks/useIsMobile';
import { useSimulationStore } from '../store/useSimulationStore';

interface MapConfigData {
  nodes: Array<{ id: string; x: number; y: number }>;
  roads: Array<{ id: string; fromNodeId: string; toNodeId: string; laneCount: number }>;
  intersections?: unknown[];
}

export function MapPage() {
  const isMobile = useIsMobile();
  const navigate = useNavigate();
  const [bbox, setBbox] = useState<BboxInfo | null>(null);
  const [sidebarState, setSidebarState] = useState<'idle' | 'loading' | 'result' | 'error'>('idle');
  const [fetchResult, setFetchResult] = useState<{ roadCount: number; intersectionCount: number } | null>(null);
  const [fetchError, setFetchError] = useState<string | null>(null);
  const [mapConfig, setMapConfig] = useState<MapConfigData | null>(null);
  const [simulationLoading, setSimulationLoading] = useState(false);
  const [uploadMode, setUploadMode] = useState(false);

  const mapViewRef = useRef<{ center: [number, number]; zoom: number }>({
    center: [52.2297, 21.0122],
    zoom: 14,
  });

  const handleViewChange = (center: [number, number], zoom: number) => {
    mapViewRef.current = { center, zoom };
  };

  const handleFetchRoads = useCallback(async () => {
    if (!bbox) return;
    setUploadMode(false);
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
      const config = await response.json() as MapConfigData;
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

  const handleAnalyzeBbox = useCallback(async () => {
    if (!bbox) return;
    setUploadMode(true);
    setSidebarState('loading');
    setFetchError(null);
    setFetchResult(null);
    try {
      const response = await fetch('/api/vision/analyze-bbox', {
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
      const config = await response.json() as MapConfigData;
      setMapConfig(config);
      setFetchResult({
        roadCount: config.roads?.length ?? 0,
        intersectionCount: config.intersections?.length ?? 0,
      });
      setSidebarState('result');
    } catch (e: unknown) {
      const message = e instanceof Error ? e.message : 'Failed to analyze bbox';
      setFetchError(message);
      setSidebarState('error');
    }
  }, [bbox]);

  const handleUploadImage = useCallback(async (file: File) => {
    setUploadMode(true);
    setSidebarState('loading');
    setFetchError(null);
    setFetchResult(null);
    try {
      const formData = new FormData();
      formData.append('image', file);
      const response = await fetch('/api/vision/analyze', {
        method: 'POST',
        body: formData,
      });
      if (!response.ok) {
        const err = await response.json().catch(() => ({ error: 'Unknown error' }));
        throw new Error((err as { error?: string }).error ?? `HTTP ${response.status}`);
      }
      const config = await response.json() as MapConfigData;
      setMapConfig(config);
      setFetchResult({
        roadCount: config.roads?.length ?? 0,
        intersectionCount: config.intersections?.length ?? 0,
      });
      setSidebarState('result');
    } catch (e: unknown) {
      const message = e instanceof Error ? e.message : 'Failed to analyze image';
      setFetchError(message);
      setSidebarState('error');
    }
  }, []);

  const handleReset = useCallback(() => {
    setSidebarState('idle');
    setFetchError(null);
    setFetchResult(null);
    setMapConfig(null);
    setUploadMode(false);
  }, []);

  const handleExportJson = useCallback(() => {
    if (!mapConfig) return;
    const json = JSON.stringify(mapConfig, null, 2);
    const blob = new Blob([json], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'map-config.json';
    a.click();
    URL.revokeObjectURL(url);
  }, [mapConfig]);

  const handleRunSimulation = useCallback(async () => {
    if (!mapConfig) return;
    setSimulationLoading(true);
    try {
      const response = await fetch('/api/command/load-config', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(mapConfig),
      });
      if (!response.ok) {
        const err = await response.json().catch(() => ({ error: 'Unknown error' }));
        throw new Error((err as { error?: string }).error ?? `HTTP ${response.status}`);
      }
      // Clear stale snapshots/roads from previous scenario before re-fetching
      useSimulationStore.getState().clearSnapshots();
      const refetchRoads = useSimulationStore.getState().refetchRoads;
      // Wait briefly for backend to process load-config, then refetch roads
      await new Promise((resolve) => setTimeout(resolve, 200));
      if (refetchRoads) refetchRoads();

      // Send START command via STOMP to begin simulation
      const sendCommand = useSimulationStore.getState().sendCommand;
      if (sendCommand) {
        sendCommand({ type: 'START' });
      }
      // Navigate to simulation page
      navigate('/');
    } catch (e: unknown) {
      const message = e instanceof Error ? e.message : 'Failed to load config';
      setFetchError(message);
      setSidebarState('error');
    } finally {
      setSimulationLoading(false);
    }
  }, [mapConfig, navigate]);

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
          >
            {sidebarState === 'result' && mapConfig && bbox && (
              <RoadGraphPreview mapConfig={mapConfig} bbox={bbox} />
            )}
          </BoundingBoxMap>
        </div>
      </div>

      <div style={sidebarStyle}>
        <MapSidebar
          bbox={bbox}
          state={sidebarState}
          onFetchRoads={handleFetchRoads}
          onUploadImage={handleUploadImage}
          onAnalyzeBbox={handleAnalyzeBbox}
          result={fetchResult}
          error={fetchError}
          onReset={handleReset}
          onExportJson={handleExportJson}
          onRunSimulation={handleRunSimulation}
          simulationLoading={simulationLoading}
          loadingMessage={uploadMode ? 'Analyzing road image...' : 'Fetching road data...'}
        />
      </div>
    </div>
  );
}
