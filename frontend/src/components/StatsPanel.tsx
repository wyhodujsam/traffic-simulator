import { useSimulationStore } from '../store/useSimulationStore';

interface StatRowProps {
  label: string;
  value: string;
}

function StatRow({ label, value }: StatRowProps) {
  return (
    <div style={{
      display: 'flex',
      justifyContent: 'space-between',
      padding: '4px 0',
      borderBottom: '1px solid #333',
    }}>
      <span style={{ color: '#888' }}>{label}</span>
      <span style={{ color: '#e0e0e0', fontWeight: 'bold' }}>{value}</span>
    </div>
  );
}

export function StatsPanel() {
  const stats = useSimulationStore((s) => s.stats);
  const tickCount = useSimulationStore((s) => s.tickCount);

  if (!stats) {
    return (
      <div style={{ padding: '16px', fontFamily: 'monospace', color: '#888' }}>
        <h3 style={{ margin: '0 0 12px 0', color: '#e0e0e0' }}>Statistics</h3>
        <p>Waiting for data...</p>
      </div>
    );
  }

  const avgSpeedKmh = (stats.avgSpeed * 3.6).toFixed(1);
  const densityStr = stats.density.toFixed(1);
  const throughputStr = stats.throughput.toFixed(0);

  return (
    <div style={{ padding: '16px', fontFamily: 'monospace', color: '#e0e0e0' }}>
      <h3 style={{ margin: '0 0 12px 0' }}>Statistics</h3>
      <StatRow label="Vehicles" value={String(stats.vehicleCount)} />
      <StatRow label="Avg speed" value={`${avgSpeedKmh} km/h`} />
      <StatRow label="Density" value={`${densityStr} veh/km`} />
      <StatRow label="Throughput" value={`${throughputStr} veh/min`} />
      <StatRow label="Ticks received" value={String(tickCount)} />
    </div>
  );
}
