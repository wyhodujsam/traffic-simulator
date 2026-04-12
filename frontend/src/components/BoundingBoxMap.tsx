import 'leaflet/dist/leaflet.css';
import { MapContainer, TileLayer, useMap } from 'react-leaflet';
import L from 'leaflet';
import React, { useEffect, useRef, useCallback } from 'react';

export interface BboxInfo {
  readonly south: number;
  readonly west: number;
  readonly north: number;
  readonly east: number;
  readonly widthMeters: number;
  readonly heightMeters: number;
}

interface BoundingBoxMapProps {
  readonly onBoundsChange?: (bbox: BboxInfo) => void;
  readonly center?: [number, number];
  readonly zoom?: number;
  readonly onViewChange?: (center: [number, number], zoom: number) => void;
  readonly children?: React.ReactNode;
}

interface BoundingBoxLayerProps {
  readonly onBoundsChange?: (bbox: BboxInfo) => void;
  readonly onViewChange?: (center: [number, number], zoom: number) => void;
}

function BoundingBoxLayer({ onBoundsChange, onViewChange }: BoundingBoxLayerProps) {
  const map = useMap();
  const rectRef = useRef<L.Rectangle | null>(null);
  const onBoundsChangeRef = useRef(onBoundsChange);
  const onViewChangeRef = useRef(onViewChange);
  onBoundsChangeRef.current = onBoundsChange;
  onViewChangeRef.current = onViewChange;

  const updateRect = useCallback(() => {
    const bounds = map.getBounds();
    const latPad = (bounds.getNorth() - bounds.getSouth()) * 0.2;
    const lngPad = (bounds.getEast() - bounds.getWest()) * 0.2;
    const bboxBounds = L.latLngBounds(
      [bounds.getSouth() + latPad, bounds.getWest() + lngPad],
      [bounds.getNorth() - latPad, bounds.getEast() - lngPad]
    );

    if (rectRef.current) {
      rectRef.current.setBounds(bboxBounds);
    } else {
      rectRef.current = L.rectangle(bboxBounds, {
        color: '#0088ff',
        weight: 2,
        fillOpacity: 0.1,
        interactive: false,
      }).addTo(map);
    }

    const sw = bboxBounds.getSouthWest();
    const ne = bboxBounds.getNorthEast();
    const widthMeters = map.distance(sw, L.latLng(sw.lat, ne.lng));
    const heightMeters = map.distance(sw, L.latLng(ne.lat, sw.lng));

    onBoundsChangeRef.current?.({
      south: bboxBounds.getSouth(),
      west: bboxBounds.getWest(),
      north: bboxBounds.getNorth(),
      east: bboxBounds.getEast(),
      widthMeters,
      heightMeters,
    });

    const center = map.getCenter();
    onViewChangeRef.current?.([center.lat, center.lng], map.getZoom());
  }, [map]);

  useEffect(() => {
    updateRect();
    map.on('moveend zoomend', updateRect);

    return () => {
      map.off('moveend zoomend', updateRect);
      rectRef.current?.remove();
      rectRef.current = null;
    };
  }, [map, updateRect]);

  return null;
}

export function BoundingBoxMap({ onBoundsChange, center, zoom, onViewChange, children }: BoundingBoxMapProps) {
  const defaultCenter: [number, number] = [52.2297, 21.0122];
  const defaultZoom = 14;

  return (
    <MapContainer
      center={center ?? defaultCenter}
      zoom={zoom ?? defaultZoom}
      style={{ height: '100%', width: '100%' }}
    >
      <TileLayer
        attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
        url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
      />
      <BoundingBoxLayer onBoundsChange={onBoundsChange} onViewChange={onViewChange} />
      {children}
    </MapContainer>
  );
}
