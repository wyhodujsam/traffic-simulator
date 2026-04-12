import { CircleMarker, Polyline } from 'react-leaflet';

interface MapConfigPreview {
  nodes: Array<{ id: string; x: number; y: number }>;
  roads: Array<{ id: string; fromNodeId: string; toNodeId: string; laneCount: number }>;
}

interface BboxBounds {
  south: number;
  west: number;
  north: number;
  east: number;
}

interface RoadGraphPreviewProps {
  readonly mapConfig: MapConfigPreview;
  readonly bbox: BboxBounds;
}

function toLatLng(
  x: number,
  y: number,
  maxX: number,
  maxY: number,
  bbox: BboxBounds,
): [number, number] {
  const lat = bbox.south + (1 - y / maxY) * (bbox.north - bbox.south);
  const lng = bbox.west + (x / maxX) * (bbox.east - bbox.west);
  return [lat, lng];
}

export function RoadGraphPreview({ mapConfig, bbox }: RoadGraphPreviewProps) {
  const { nodes, roads } = mapConfig;

  if (nodes.length === 0) return null;

  const maxX = Math.max(...nodes.map((n) => n.x));
  const maxY = Math.max(...nodes.map((n) => n.y));

  const nodeMap = new Map<string, [number, number]>();
  for (const node of nodes) {
    nodeMap.set(node.id, toLatLng(node.x, node.y, maxX, maxY, bbox));
  }

  return (
    <>
      {roads.map((road) => {
        const from = nodeMap.get(road.fromNodeId);
        const to = nodeMap.get(road.toNodeId);
        if (!from || !to) return null;
        const weight = Math.max(2, road.laneCount * 1.5);
        return (
          <Polyline
            key={road.id}
            positions={[from, to]}
            pathOptions={{ color: '#44aaff', opacity: 0.7, weight }}
          />
        );
      })}
      {nodes.map((node) => {
        const pos = nodeMap.get(node.id);
        if (!pos) return null;
        return (
          <CircleMarker
            key={node.id}
            center={pos}
            radius={4}
            pathOptions={{ color: '#44aaff', fillColor: '#44aaff', fillOpacity: 1, weight: 1 }}
          />
        );
      })}
    </>
  );
}
