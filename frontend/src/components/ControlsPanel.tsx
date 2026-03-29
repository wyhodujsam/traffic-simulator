import { useState, useCallback } from 'react';
import { useSimulationStore } from '../store/useSimulationStore';
import { useDebouncedCallback } from '../hooks/useDebouncedCallback';
import type { SimulationStatus } from '../types/simulation';

const DEBOUNCE_MS = 200;

export function ControlsPanel() {
  const status = useSimulationStore((s) => s.status);
  const sendCommand = useSimulationStore((s) => s.sendCommand);

  const [speedMultiplier, setSpeedMultiplier] = useState(1.0);
  const [spawnRate, setSpawnRate] = useState(1.0);
  const [maxSpeed, setMaxSpeed] = useState(120); // km/h display

  const send = useCallback(
    (cmd: Parameters<NonNullable<typeof sendCommand>>[0]) => {
      if (sendCommand) sendCommand(cmd);
    },
    [sendCommand]
  );

  const debouncedSpeedMultiplier = useDebouncedCallback(
    ((value: number) => send({ type: 'SET_SPEED_MULTIPLIER', multiplier: value })) as (...args: unknown[]) => void,
    DEBOUNCE_MS
  );

  const debouncedSpawnRate = useDebouncedCallback(
    ((value: number) => send({ type: 'SET_SPAWN_RATE', spawnRate: value })) as (...args: unknown[]) => void,
    DEBOUNCE_MS
  );

  const debouncedMaxSpeed = useDebouncedCallback(
    ((kmh: number) => send({ type: 'SET_MAX_SPEED', maxSpeed: kmh / 3.6 })) as (...args: unknown[]) => void,
    DEBOUNCE_MS
  );

  const handleStart = () => {
    if (status === 'STOPPED') send({ type: 'START' });
    else if (status === 'PAUSED') send({ type: 'RESUME' });
  };

  const handlePause = () => {
    if (status === 'RUNNING') send({ type: 'PAUSE' });
  };

  const handleStop = () => {
    if (status !== 'STOPPED') send({ type: 'STOP' });
  };

  const buttonStyle = (enabled: boolean): React.CSSProperties => ({
    padding: '8px 16px',
    margin: '4px',
    cursor: enabled ? 'pointer' : 'not-allowed',
    opacity: enabled ? 1 : 0.4,
    border: '1px solid #555',
    borderRadius: '4px',
    background: '#2a2a3e',
    color: '#e0e0e0',
    fontFamily: 'monospace',
    fontSize: '14px',
  });

  const startLabel: Record<SimulationStatus, string> = {
    STOPPED: 'Start',
    RUNNING: 'Start',
    PAUSED: 'Resume',
  };

  return (
    <div style={{ padding: '16px', fontFamily: 'monospace', color: '#e0e0e0' }}>
      <h3 style={{ margin: '0 0 12px 0' }}>Controls</h3>

      {/* Simulation state buttons */}
      <div style={{ marginBottom: '16px' }}>
        <button
          style={buttonStyle(status === 'STOPPED' || status === 'PAUSED')}
          onClick={handleStart}
          disabled={status === 'RUNNING'}
        >
          {startLabel[status]}
        </button>
        <button
          style={buttonStyle(status === 'RUNNING')}
          onClick={handlePause}
          disabled={status !== 'RUNNING'}
        >
          Pause
        </button>
        <button
          style={buttonStyle(status !== 'STOPPED')}
          onClick={handleStop}
          disabled={status === 'STOPPED'}
        >
          Stop
        </button>
      </div>

      <div style={{ fontSize: '12px', marginBottom: '16px', color: '#888' }}>
        Status: <span style={{ color: '#e0e0e0' }}>{status}</span>
      </div>

      {/* Speed multiplier slider */}
      <div style={{ marginBottom: '12px' }}>
        <label>
          Speed: {speedMultiplier.toFixed(1)}x
          <br />
          <input
            type="range"
            min="0.5"
            max="5"
            step="0.5"
            value={speedMultiplier}
            onChange={(e) => {
              const v = parseFloat(e.target.value);
              setSpeedMultiplier(v);
              debouncedSpeedMultiplier(v);
            }}
            style={{ width: '100%' }}
          />
        </label>
      </div>

      {/* Spawn rate slider */}
      <div style={{ marginBottom: '12px' }}>
        <label>
          Spawn rate: {spawnRate.toFixed(1)} veh/s
          <br />
          <input
            type="range"
            min="0.5"
            max="5"
            step="0.5"
            value={spawnRate}
            onChange={(e) => {
              const v = parseFloat(e.target.value);
              setSpawnRate(v);
              debouncedSpawnRate(v);
            }}
            style={{ width: '100%' }}
          />
        </label>
      </div>

      {/* Debug dump */}
      <div style={{ marginBottom: '16px' }}>
        <button
          style={{ ...buttonStyle(true), background: '#3a2a2a', fontSize: '11px' }}
          onClick={async () => {
            try {
              const res = await fetch('/api/debug/dump');
              const data = await res.json();
              console.log('%c[DUMP]', 'color: #ff6b6b; font-weight: bold', JSON.stringify(data, null, 2));
              // Also copy to clipboard
              await navigator.clipboard.writeText(JSON.stringify(data, null, 2));
              alert('State dumped to console + clipboard');
            } catch (e) {
              console.error('Dump failed:', e);
            }
          }}
        >
          📋 Dump State
        </button>
      </div>

      {/* Max speed input */}
      <div style={{ marginBottom: '12px' }}>
        <label>
          Max speed: {maxSpeed} km/h
          <br />
          <input
            type="range"
            min="30"
            max="200"
            step="10"
            value={maxSpeed}
            onChange={(e) => {
              const v = parseInt(e.target.value, 10);
              setMaxSpeed(v);
              debouncedMaxSpeed(v);
            }}
            style={{ width: '100%' }}
          />
        </label>
      </div>
    </div>
  );
}
