import { useEffect, useRef } from 'react';
import { Client, IMessage } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { useSimulationStore } from '../store/useSimulationStore';
import type { CommandDto, IntersectionDto, MapInfo, RoadDto } from '../types/simulation';

export function useWebSocket() {
  const clientRef = useRef<Client | null>(null);

  useEffect(() => {
    // Reusable road + intersection fetch function
    const fetchRoads = () => {
      fetch('/api/roads')
        .then((res) => res.json())
        .then((roads: RoadDto[]) => {
          useSimulationStore.getState().setRoads(roads);
        })
        .catch((err) => console.error('[REST] Failed to fetch roads:', err));

      fetch('/api/intersections')
        .then((res) => res.json())
        .then((intersections: IntersectionDto[]) => {
          useSimulationStore.getState().setIntersections(intersections);
        })
        .catch((err) => console.error('[REST] Failed to fetch intersections:', err));
    };

    // Fetch road geometry on mount
    fetchRoads();

    // Store fetchRoads so ControlsPanel can re-fetch after map load
    useSimulationStore.getState().setRefetchRoads(fetchRoads);

    // Fetch available maps list
    fetch('/api/maps')
      .then((res) => res.json())
      .then((maps: MapInfo[]) => {
        useSimulationStore.getState().setAvailableMaps(maps);
      })
      .catch((err) => console.error('[REST] Failed to fetch maps:', err));

    const client = new Client({
      webSocketFactory: () => new SockJS('/ws'),
      reconnectDelay: 5000,
      heartbeatIncoming: 4000,
      heartbeatOutgoing: 4000,
      onConnect: () => {
        console.log('[WS] Connected to simulation broker');
        useSimulationStore.getState().setConnected(true);
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
        useSimulationStore.getState().setConnected(false);
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
