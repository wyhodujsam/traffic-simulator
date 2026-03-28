import { useWebSocket } from './hooks/useWebSocket';
import { useSimulationStore } from './store/useSimulationStore';

function App() {
  useWebSocket();
  const status = useSimulationStore((s) => s.status);
  const tickCount = useSimulationStore((s) => s.tickCount);
  const currSnapshot = useSimulationStore((s) => s.currSnapshot);

  return (
    <div style={{ padding: '2rem', fontFamily: 'monospace' }}>
      <h1>Traffic Simulator</h1>
      <p>Status: {status}</p>
      <p>Tick count: {tickCount}</p>
      <p>
        Last tick:{' '}
        {currSnapshot
          ? `tick=${currSnapshot.state.tick}, vehicles=${currSnapshot.state.vehicles.length}`
          : 'waiting for connection...'}
      </p>
      {currSnapshot && (
        <p>
          Latency: {Date.now() - currSnapshot.state.timestamp} ms
        </p>
      )}
    </div>
  );
}

export default App;
