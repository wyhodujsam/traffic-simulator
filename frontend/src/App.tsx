import { useWebSocket } from './hooks/useWebSocket';
import { SimulationCanvas } from './components/SimulationCanvas';
import { ControlsPanel } from './components/ControlsPanel';
import { StatsPanel } from './components/StatsPanel';
import { useSimulationStore } from './store/useSimulationStore';

function DisconnectBanner() {
  const connected = useSimulationStore((s) => s.connected);
  if (connected) return null;
  return (
    <div style={{
      background: '#cc3300',
      color: '#fff',
      padding: '6px 16px',
      textAlign: 'center',
      fontSize: '13px',
      fontWeight: 'bold',
    }}>
      WebSocket disconnected — reconnecting...
    </div>
  );
}

function PausedOverlay() {
  const status = useSimulationStore((s) => s.status);
  if (status !== 'PAUSED') return null;
  return (
    <div style={{
      position: 'absolute',
      top: '50%',
      left: '50%',
      transform: 'translate(-50%, -50%)',
      background: 'rgba(0,0,0,0.6)',
      color: '#fff',
      padding: '12px 28px',
      borderRadius: '8px',
      fontSize: '20px',
      fontWeight: 'bold',
      letterSpacing: '4px',
      pointerEvents: 'none',
      zIndex: 10,
    }}>
      PAUSED
    </div>
  );
}

function App() {
  useWebSocket();

  return (
    <div style={{
      display: 'flex',
      flexDirection: 'column',
      height: '100vh',
      background: '#0d0d1a',
      color: '#e0e0e0',
      fontFamily: 'monospace',
    }}>
      {/* Header */}
      <header style={{
        padding: '12px 24px',
        borderBottom: '1px solid #333',
        fontSize: '18px',
        fontWeight: 'bold',
      }}>
        Traffic Simulator
      </header>

      <DisconnectBanner />

      {/* Main content */}
      <div style={{
        display: 'flex',
        flex: 1,
        overflow: 'hidden',
      }}>
        {/* Canvas area */}
        <main style={{
          flex: 1,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          padding: '16px',
          overflow: 'auto',
          position: 'relative',
        }}>
          <SimulationCanvas />
          <PausedOverlay />
        </main>

        {/* Sidebar */}
        <aside style={{
          width: '260px',
          minWidth: '260px',
          borderLeft: '1px solid #333',
          display: 'flex',
          flexDirection: 'column',
          overflow: 'auto',
        }}>
          <ControlsPanel />
          <div style={{ borderTop: '1px solid #333' }} />
          <StatsPanel />
        </aside>
      </div>
    </div>
  );
}

export default App;
