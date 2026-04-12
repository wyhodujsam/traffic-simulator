import { BrowserRouter, Routes, Route, Link, useLocation } from 'react-router-dom';
import { useWebSocket } from './hooks/useWebSocket';
import { SimulationPage } from './pages/SimulationPage';
import { MapPage } from './pages/MapPage';

function NavHeader() {
  const { pathname } = useLocation();

  const linkStyle = (path: string): React.CSSProperties => ({
    textDecoration: 'none',
    marginLeft: '16px',
    fontSize: '14px',
    color: pathname === path ? '#4af' : '#888',
  });

  return (
    <header style={{
      padding: '12px 24px',
      borderBottom: '1px solid #333',
      fontSize: '18px',
      fontWeight: 'bold',
      flexShrink: 0,
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'space-between',
    }}>
      <span>Traffic Simulator</span>
      <nav>
        <Link to="/" style={linkStyle('/')}>Simulation</Link>
        <Link to="/map" style={linkStyle('/map')}>Map</Link>
      </nav>
    </header>
  );
}

function App() {
  useWebSocket();
  return (
    <BrowserRouter>
      <div style={{
        display: 'flex',
        flexDirection: 'column',
        height: '100vh',
        background: '#0d0d1a',
        color: '#e0e0e0',
        fontFamily: 'monospace',
      }}>
        <NavHeader />
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minHeight: 0 }}>
          <Routes>
            <Route path="/" element={<SimulationPage />} />
            <Route path="/map" element={<MapPage />} />
          </Routes>
        </div>
      </div>
    </BrowserRouter>
  );
}

export default App;
