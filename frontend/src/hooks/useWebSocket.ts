import { useEffect, useRef } from 'react';
import { Client, IMessage } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { useSimulationStore } from '../store/useSimulationStore';
import type { CommandDto, RoadDto } from '../types/simulation';

export function useWebSocket() {
  const clientRef = useRef<Client | null>(null);

  useEffect(() => {
    // Fetch road geometry once via REST
    fetch('/api/roads')
      .then((res) => res.json())
      .then((roads: RoadDto[]) => {
        useSimulationStore.getState().setRoads(roads);
      })
      .catch((err) => console.error('[REST] Failed to fetch roads:', err));

    const client = new Client({
      webSocketFactory: () => new SockJS('/ws'),
      reconnectDelay: 5000,
      heartbeatIncoming: 4000,
      heartbeatOutgoing: 4000,
      onConnect: () => {
        console.log('[WS] Connected to simulation broker');
        client.subscribe('/topic/simulation', (message: IMessage) => {
          const data = JSON.parse(message.body);
          useSimulationStore.getState().setTick(data);
        });

        // Wire sendCommand into Zustand store
        const sendCommand = (cmd: CommandDto) => {
          client.publish({
            destination: '/app/command',
            body: JSON.stringify(cmd),
          });
        };
        useSimulationStore.getState().setSendCommand(sendCommand);
      },
      onDisconnect: () => {
        console.warn('[WS] Disconnected from simulation broker');
        useSimulationStore.getState().setSendCommand(null);
      },
      onStompError: (frame) => {
        console.error('[WS] STOMP error', frame);
      },
    });

    clientRef.current = client;
    client.activate();

    return () => {
      client.deactivate();
    };
  }, []);

  return clientRef;
}
