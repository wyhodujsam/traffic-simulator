import { describe, it, expect, beforeAll } from 'vitest';
import WebSocket from 'ws';
import { Client } from '@stomp/stompjs';

const BACKEND_URL = 'http://localhost:8086';
const WS_URL = 'ws://localhost:8086/ws/websocket';

let backendAvailable = false;
beforeAll(async () => {
  try {
    const res = await fetch(`${BACKEND_URL}/ws/info`, { signal: AbortSignal.timeout(1000) });
    backendAvailable = res.ok;
  } catch { backendAvailable = false; }
});

describe.skipIf(!backendAvailable)('E2E: Backend health', () => {
  it('should have /ws/info endpoint returning SockJS info', async () => {
    const res = await fetch(`${BACKEND_URL}/ws/info`);
    expect(res.ok).toBe(true);
    const data = await res.json();
    expect(data).toHaveProperty('websocket', true);
    expect(data).toHaveProperty('origins');
  });
});

describe.skipIf(!backendAvailable)('E2E: STOMP over WebSocket', () => {
  it('should connect and receive tick messages within 3 seconds', async () => {
    const ticks: Array<{ tick: number; timestamp: number }> = [];

    await new Promise<void>((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error('No tick received within 3s')), 3000);

      const client = new Client({
        webSocketFactory: () => new WebSocket(WS_URL) as unknown as globalThis.WebSocket,
        reconnectDelay: 0,
        onConnect: () => {
          client.subscribe('/topic/simulation', (message) => {
            const data = JSON.parse(message.body);
            ticks.push(data);
            if (ticks.length >= 3) {
              clearTimeout(timeout);
              client.deactivate();
              resolve();
            }
          });
        },
        onStompError: (frame) => {
          clearTimeout(timeout);
          reject(new Error(`STOMP error: ${frame.body}`));
        },
      });

      client.activate();
    });

    expect(ticks.length).toBeGreaterThanOrEqual(3);
    expect(ticks[0]).toHaveProperty('tick');
    expect(ticks[0]).toHaveProperty('timestamp');
    expect(ticks[1].tick).toBeGreaterThan(ticks[0].tick);
    expect(ticks[2].tick).toBeGreaterThan(ticks[1].tick);
  });

  it('should receive ticks at approximately 20 Hz (50ms interval)', async () => {
    const timestamps: number[] = [];

    await new Promise<void>((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error('Timeout')), 5000);

      const client = new Client({
        webSocketFactory: () => new WebSocket(WS_URL) as unknown as globalThis.WebSocket,
        reconnectDelay: 0,
        onConnect: () => {
          client.subscribe('/topic/simulation', () => {
            timestamps.push(Date.now());
            if (timestamps.length >= 10) {
              clearTimeout(timeout);
              client.deactivate();
              resolve();
            }
          });
        },
        onStompError: (frame) => {
          clearTimeout(timeout);
          reject(new Error(`STOMP error: ${frame.body}`));
        },
      });

      client.activate();
    });

    const intervals: number[] = [];
    for (let i = 1; i < timestamps.length; i++) {
      intervals.push(timestamps[i] - timestamps[i - 1]);
    }
    const avgInterval = intervals.reduce((a, b) => a + b, 0) / intervals.length;

    // ~50ms (20 Hz), allow 20-150ms for jitter
    expect(avgInterval).toBeGreaterThan(20);
    expect(avgInterval).toBeLessThan(150);
  });
});
