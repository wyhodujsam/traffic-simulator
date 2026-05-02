import { useIsMobile } from '../hooks/useIsMobile';
import { SimulationCanvas } from '../components/SimulationCanvas';
import { ControlsPanel } from '../components/ControlsPanel';
import { StatsPanel } from '../components/StatsPanel';
import { DiagnosticsPanel } from '../components/DiagnosticsPanel';
import { useSimulationStore } from '../store/useSimulationStore';

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

export function SimulationPage() {
  const isMobile = useIsMobile();

  return (
    <>
      <DisconnectBanner />

      {/* Main content */}
      <div style={{
        display: 'flex',
        flexDirection: isMobile ? 'column' : 'row',
        flex: 1,
        overflow: isMobile ? 'auto' : 'hidden',
        minHeight: 0,
      }}>
        {/* Canvas area */}
        <main style={{
          flex: isMobile ? 'none' : 1,
          minWidth: 0,
          display: 'flex',
          padding: '16px',
          overflow: 'hidden',
          position: 'relative',
          maxHeight: isMobile ? '50vh' : undefined,
        }}>
          <SimulationCanvas />
          <PausedOverlay />
        </main>

        {/* Sidebar */}
        <aside style={isMobile ? {
          width: '100%',
          borderTop: '1px solid #333',
          display: 'flex',
          flexDirection: 'column',
          overflow: 'auto',
          flexShrink: 0,
        } : {
          width: '260px',
          minWidth: '260px',
          flexShrink: 0,
          borderLeft: '1px solid #333',
          display: 'flex',
          flexDirection: 'column',
          overflow: 'auto',
        }}>
          <ControlsPanel />
          <div style={{ borderTop: '1px solid #333' }} />
          <StatsPanel />
          <DiagnosticsPanel />
        </aside>
      </div>
    </>
  );
}
