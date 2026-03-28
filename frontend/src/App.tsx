import { useWebSocket } from './hooks/useWebSocket';
import { SimulationCanvas } from './components/SimulationCanvas';
import { ControlsPanel } from './components/ControlsPanel';
import { StatsPanel } from './components/StatsPanel';

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
        }}>
          <SimulationCanvas />
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
