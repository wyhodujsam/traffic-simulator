import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';

import { DiagnosticsPanel } from '../DiagnosticsPanel';
import { useSimulationStore } from '../../store/useSimulationStore';

describe('DiagnosticsPanel — collapsed-by-default (UI-01, UI-02)', () => {
  beforeEach(() => {
    // Reset to default before each test
    useSimulationStore.setState({ diagnosticsOpen: false });
  });

  it('UI-01: defaults to collapsed — only button visible', () => {
    render(<DiagnosticsPanel />);
    expect(screen.getByRole('button', { name: /show diagnostics/i })).toBeTruthy();
    // UI-02: zero render cost — no canvas mounted
    expect(screen.queryByTestId('space-time-canvas')).toBeNull();
    expect(screen.queryByTestId('fundamental-canvas')).toBeNull();
  });

  it('UI-01: expands when toggle clicked — both canvases mount', () => {
    render(<DiagnosticsPanel />);
    fireEvent.click(screen.getByRole('button', { name: /show diagnostics/i }));
    expect(screen.getByTestId('space-time-canvas')).toBeTruthy();
    expect(screen.getByTestId('fundamental-canvas')).toBeTruthy();
    expect(screen.getByRole('button', { name: /hide diagnostics/i })).toBeTruthy();
  });

  it('UI-02: collapses on second click — canvases unmount (zero render cost per D-09)', () => {
    render(<DiagnosticsPanel />);
    const btn = screen.getByRole('button', { name: /show diagnostics/i });
    fireEvent.click(btn);
    expect(screen.queryByTestId('space-time-canvas')).not.toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /hide diagnostics/i }));
    expect(screen.queryByTestId('space-time-canvas')).toBeNull();
    expect(screen.queryByTestId('fundamental-canvas')).toBeNull();
  });

  it('button label toggles between Show and Hide', () => {
    render(<DiagnosticsPanel />);
    expect(screen.getByRole('button', { name: /show diagnostics/i })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /show diagnostics/i }));
    expect(screen.getByRole('button', { name: /hide diagnostics/i })).toBeTruthy();
  });
});
