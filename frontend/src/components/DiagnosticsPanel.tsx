import { useEffect, useRef } from 'react';

import { useSimulationStore } from '../store/useSimulationStore';
import { SpaceTimeRenderer } from '../rendering/spaceTimeDiagram';
import { FundamentalDiagramRenderer } from '../rendering/fundamentalDiagram';

/**
 * Phase 25 D-09: collapsible diagnostics panel mounted below StatsPanel.
 * - Default collapsed: only the toggle button is rendered.
 * - When open: two canvases (space-time + fundamental) render via requestAnimationFrame from
 *   Zustand subscriptions. When closed, canvases are unmounted (zero per-tick render cost).
 */
export function DiagnosticsPanel() {
  const open = useSimulationStore((s) => s.diagnosticsOpen);
  const toggle = useSimulationStore((s) => s.toggleDiagnostics);

  return (
    <div style={{ borderTop: '1px solid #333', padding: '8px', fontFamily: 'monospace' }}>
      <button
        onClick={toggle}
        style={{ width: '100%', padding: 8, background: '#222', color: '#e0e0e0', border: '1px solid #444', cursor: 'pointer' }}
        aria-expanded={open}
      >
        {open ? 'Hide diagnostics' : 'Show diagnostics'}
      </button>
      {open && <DiagnosticsContent />}
    </div>
  );
}

/** Renders the two canvases. Mounted only when panel is open. */
function DiagnosticsContent() {
  const spaceTimeRef = useRef<HTMLCanvasElement>(null);
  const fundamentalRef = useRef<HTMLCanvasElement>(null);
  const spaceTimeRenderer = useRef(new SpaceTimeRenderer());
  const fundamentalRenderer = useRef(new FundamentalDiagramRenderer());

  useEffect(() => {
    let raf = 0;
    const tick = () => {
      const s = useSimulationStore.getState();
      const snapshot = s.currSnapshot;
      const stats = s.stats;
      if (snapshot && spaceTimeRef.current) {
        spaceTimeRenderer.current.ingest(snapshot);
        spaceTimeRenderer.current.render(spaceTimeRef.current);
      }
      if (snapshot && stats?.segmentKpis && fundamentalRef.current) {
        fundamentalRenderer.current.ingest(snapshot.state.tick, stats.segmentKpis);
        fundamentalRenderer.current.render(fundamentalRef.current);
      }
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, []);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginTop: 8 }}>
      <div>
        <div style={{ color: '#888', fontSize: 11, marginBottom: 4 }}>
          Space-time diagram (rolling 30s, color = speed)
        </div>
        <canvas
          ref={spaceTimeRef}
          width={400}
          height={200}
          style={{ width: '100%', maxWidth: 400, background: '#111', border: '1px solid #333' }}
          data-testid="space-time-canvas"
        />
      </div>
      <div>
        <div style={{ color: '#888', fontSize: 11, marginBottom: 4 }}>
          Fundamental diagram (density vs flow, rolling 60s)
        </div>
        <canvas
          ref={fundamentalRef}
          width={400}
          height={200}
          style={{ width: '100%', maxWidth: 400, background: '#111', border: '1px solid #333' }}
          data-testid="fundamental-canvas"
        />
      </div>
    </div>
  );
}
