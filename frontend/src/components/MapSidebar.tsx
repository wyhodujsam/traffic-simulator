import { useRef, useEffect, useState } from 'react';
import { BboxInfo } from './BoundingBoxMap';

interface MapSidebarProps {
  readonly bbox: BboxInfo | null;
  readonly state: 'idle' | 'loading' | 'result' | 'error';
  readonly onFetchRoads?: () => void;
  readonly onFetchRoadsGh?: () => void;
  readonly onFetchRoadsO2s?: () => void;
  readonly onUploadImage?: (file: File) => void;
  readonly onAnalyzeBbox?: () => void;
  readonly onAnalyzeComponents?: () => void;
  readonly result?: { roadCount: number; intersectionCount: number } | null;
  readonly error?: string | null;
  readonly onReset?: () => void;
  readonly onExportJson?: () => void;
  readonly onRunSimulation?: () => void;
  readonly simulationLoading?: boolean;
  readonly loadingMessage?: string;
  readonly resultOrigin?: 'Overpass' | 'GraphHopper' | 'osm2streets' | null;
}

const buttonBase: React.CSSProperties = {
  background: '#1a1a2e',
  border: '1px solid #444',
  color: '#e0e0e0',
  padding: '8px 16px',
  cursor: 'pointer',
  width: '100%',
  marginBottom: '8px',
  fontFamily: 'monospace',
  fontSize: '13px',
  textAlign: 'left',
};

function IdleContent({ bbox }: { readonly bbox: BboxInfo | null }) {
  if (!bbox) {
    return (
      <p style={{ color: '#888', fontSize: '13px', margin: '0 0 12px' }}>
        Move map to select area
      </p>
    );
  }

  const w = Math.round(bbox.widthMeters);
  const h = Math.round(bbox.heightMeters);

  return (
    <>
      <p style={{ margin: '0 0 4px', fontSize: '14px' }}>
        {w}m x {h}m
      </p>
      <table style={{ fontSize: '12px', color: '#aaa', marginBottom: '16px', borderCollapse: 'collapse' }}>
        <tbody>
          <tr>
            <td style={{ paddingRight: '8px' }}>N</td>
            <td>{bbox.north.toFixed(4)}</td>
          </tr>
          <tr>
            <td style={{ paddingRight: '8px' }}>S</td>
            <td>{bbox.south.toFixed(4)}</td>
          </tr>
          <tr>
            <td style={{ paddingRight: '8px' }}>W</td>
            <td>{bbox.west.toFixed(4)}</td>
          </tr>
          <tr>
            <td style={{ paddingRight: '8px' }}>E</td>
            <td>{bbox.east.toFixed(4)}</td>
          </tr>
        </tbody>
      </table>
    </>
  );
}

function IdleActions({
  onFetchRoads,
  onFetchRoadsGh,
  onFetchRoadsO2s,
  onUploadImage,
  onAnalyzeBbox,
  onAnalyzeComponents,
}: {
  readonly onFetchRoads?: () => void;
  readonly onFetchRoadsGh?: () => void;
  readonly onFetchRoadsO2s?: () => void;
  readonly onUploadImage?: (file: File) => void;
  readonly onAnalyzeBbox?: () => void;
  readonly onAnalyzeComponents?: () => void;
}) {
  const fileInputRef = useRef<HTMLInputElement>(null);

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file && onUploadImage) {
      onUploadImage(file);
    }
    // Reset so same file can be re-selected if needed
    if (fileInputRef.current) {
      fileInputRef.current.value = '';
    }
  };

  return (
    <>
      <button style={buttonBase} onClick={onFetchRoads}>
        Fetch Roads
      </button>
      <button style={buttonBase} onClick={onFetchRoadsGh}>
        Fetch roads (GraphHopper)
      </button>
      <button style={buttonBase} onClick={onFetchRoadsO2s}>
        Fetch roads (osm2streets)
      </button>
      <button style={buttonBase} onClick={onAnalyzeBbox}>
        AI Vision (from bbox)
      </button>
      <button style={buttonBase} onClick={onAnalyzeComponents}>
        AI Vision (component library)
      </button>
      <input
        type="file"
        ref={fileInputRef}
        accept="image/jpeg,image/png"
        style={{ display: 'none' }}
        onChange={handleFileChange}
      />
      <button
        style={{ ...buttonBase, marginBottom: 0 }}
        onClick={() => fileInputRef.current?.click()}
      >
        Upload Image
      </button>
    </>
  );
}

const LOADER_KEYFRAMES = `
@keyframes ts-loader-slide {
  0%   { transform: translateX(-100%); }
  100% { transform: translateX(400%); }
}`;

function LoadingContent({ message }: { readonly message: string }) {
  const [elapsed, setElapsed] = useState(0);
  useEffect(() => {
    const start = Date.now();
    const id = setInterval(() => setElapsed(Math.round((Date.now() - start) / 1000)), 250);
    return () => clearInterval(id);
  }, []);
  return (
    <div style={{ fontSize: '13px', color: '#aaa' }}>
      <style>{LOADER_KEYFRAMES}</style>
      <p style={{ margin: '0 0 8px' }}>{message}</p>
      <div style={{
        height: '6px',
        background: '#333',
        borderRadius: '3px',
        overflow: 'hidden',
        position: 'relative',
      }}>
        <div style={{
          position: 'absolute',
          height: '100%',
          width: '25%',
          background: '#0088ff',
          borderRadius: '3px',
          animation: 'ts-loader-slide 1.4s ease-in-out infinite',
        }} />
      </div>
      <p style={{ margin: '8px 0 0', fontSize: '11px', color: '#888' }}>
        Elapsed: {elapsed}s
      </p>
    </div>
  );
}

function ErrorContent({ error, onReset }: { readonly error?: string | null; readonly onReset?: () => void }) {
  return (
    <>
      <p style={{ margin: '0 0 8px', fontSize: '13px', color: '#ff6b6b' }}>
        {error ?? 'An error occurred'}
      </p>
      <button style={buttonBase} onClick={onReset}>
        Try Again
      </button>
    </>
  );
}

function ResultContent({ result, onReset, onExportJson, onRunSimulation, simulationLoading, resultOrigin }: {
  readonly result?: { roadCount: number; intersectionCount: number } | null;
  readonly onReset?: () => void;
  readonly onExportJson?: () => void;
  readonly onRunSimulation?: () => void;
  readonly simulationLoading?: boolean;
  readonly resultOrigin?: 'Overpass' | 'GraphHopper' | 'osm2streets' | null;
}) {
  const heading = resultOrigin ?? 'Roads loaded';
  return (
    <>
      <p style={{ margin: '0 0 4px', fontSize: '14px' }}>{heading}</p>
      <p style={{ margin: '0 0 12px', fontSize: '13px', color: '#aaa' }}>
        {result?.roadCount ?? 0} roads, {result?.intersectionCount ?? 0} intersections
      </p>
      <button
        style={{ ...buttonBase, opacity: simulationLoading ? 0.5 : 1 }}
        onClick={onRunSimulation}
        disabled={simulationLoading}
      >
        {simulationLoading ? 'Loading...' : 'Run Simulation'}
      </button>
      <button style={buttonBase} onClick={onExportJson}>
        Export JSON
      </button>
      <button style={{ ...buttonBase, marginBottom: 0 }} onClick={onReset}>
        New Selection
      </button>
    </>
  );
}

export function MapSidebar({
  bbox,
  state,
  onFetchRoads,
  onFetchRoadsGh,
  onFetchRoadsO2s,
  onUploadImage,
  onAnalyzeBbox,
  onAnalyzeComponents,
  result,
  error,
  onReset,
  onExportJson,
  onRunSimulation,
  simulationLoading,
  loadingMessage = 'Fetching road data...',
  resultOrigin,
}: MapSidebarProps) {
  return (
    <aside style={{
      width: '260px',
      minWidth: '260px',
      flexShrink: 0,
      borderLeft: '1px solid #333',
      padding: '16px',
      fontFamily: 'monospace',
      color: '#e0e0e0',
      background: '#0d0d1a',
      display: 'flex',
      flexDirection: 'column',
      overflow: 'auto',
    }}>
      <p style={{ margin: '0 0 12px', fontWeight: 'bold', fontSize: '14px' }}>
        Map Selection
      </p>

      {state === 'idle' && (
        <>
          <IdleContent bbox={bbox} />
          <IdleActions
            onFetchRoads={onFetchRoads}
            onFetchRoadsGh={onFetchRoadsGh}
            onFetchRoadsO2s={onFetchRoadsO2s}
            onUploadImage={onUploadImage}
            onAnalyzeBbox={onAnalyzeBbox}
            onAnalyzeComponents={onAnalyzeComponents}
          />
        </>
      )}
      {state === 'loading' && <LoadingContent message={loadingMessage} />}
      {state === 'error' && <ErrorContent error={error} onReset={onReset} />}
      {state === 'result' && (
        <ResultContent
          result={result}
          onReset={onReset}
          onExportJson={onExportJson}
          onRunSimulation={onRunSimulation}
          simulationLoading={simulationLoading}
          resultOrigin={resultOrigin}
        />
      )}
    </aside>
  );
}
