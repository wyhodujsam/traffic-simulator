import { BrowserRouter, Routes, Route, Link, useLocation } from 'react-router-dom';
import { useWebSocket } from './hooks/useWebSocket';
import { SimulationPage } from './pages/SimulationPage';

function MapPagePlaceholder() {
  return (
    <div style={{
      flex: 1,
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      color: '#888',
      fontFamily: 'monospace',
      fontSize: '18px',
    }}>
      Map page — coming in Plan 02
    </div>
  );
}

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
        <Routes>
          <Route path="/" element={<SimulationPage />} />
          <Route path="/map" element={<MapPagePlaceholder />} />
        </Routes>
      </div>
    </BrowserRouter>
  );
}

export default App;
