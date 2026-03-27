import { useEffect, useRef } from 'react';
import { Client, IMessage } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { useSimulationStore } from '../store/useSimulationStore';

export function useWebSocket() {
  const clientRef = useRef<Client | null>(null);

  useEffect(() => {
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
      },
      onDisconnect: () => {
        console.warn('[WS] Disconnected from simulation broker');
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
