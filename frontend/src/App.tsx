import { useWebSocket } from './hooks/useWebSocket';
import { useSimulationStore } from './store/useSimulationStore';

function App() {
  useWebSocket();
  const lastTick = useSimulationStore((s) => s.lastTick);
  const tickCount = useSimulationStore((s) => s.tickCount);

  return (
    <div style={{ padding: '2rem', fontFamily: 'monospace' }}>
      <h1>Traffic Simulator</h1>
      <p>Tick count: {tickCount}</p>
      <p>Last tick: {lastTick ? JSON.stringify(lastTick) : 'waiting for connection...'}</p>
      {lastTick && (
        <p>
          Latency: {Date.now() - lastTick.timestamp} ms
        </p>
      )}
    </div>
  );
}

export default App;
