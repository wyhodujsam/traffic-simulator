import { useState, useRef } from 'react';
import { BoundingBoxMap, BboxInfo } from '../components/BoundingBoxMap';
import { MapSidebar } from '../components/MapSidebar';
import { useIsMobile } from '../hooks/useIsMobile';

export function MapPage() {
  const isMobile = useIsMobile();
  const [bbox, setBbox] = useState<BboxInfo | null>(null);

  const mapViewRef = useRef<{ center: [number, number]; zoom: number }>({
    center: [52.2297, 21.0122],
    zoom: 14,
  });

  const handleViewChange = (center: [number, number], zoom: number) => {
    mapViewRef.current = { center, zoom };
  };

  const sidebarStyle: React.CSSProperties = isMobile
    ? {
        width: '100%',
        minWidth: '0',
        borderLeft: 'none',
        borderTop: '1px solid #333',
        flexShrink: 0,
      }
    : {};

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
        <BoundingBoxMap
          center={mapViewRef.current.center}
          zoom={mapViewRef.current.zoom}
          onBoundsChange={setBbox}
          onViewChange={handleViewChange}
        />
      </div>

      <div style={sidebarStyle}>
        <MapSidebar bbox={bbox} state="idle" />
      </div>
    </div>
  );
}
