import { useEffect, useRef, useState } from 'react';
import { Client, IMessage } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { Incident } from '../types';

const SENTINEL_API = import.meta.env.VITE_API_URL || 'http://localhost:8080';

interface WebSocketState {
  connected: boolean;
  latestIncident: Incident | null;
}

/**
 * Subscribes to the SENTINEL WebSocket for real-time incident updates.
 */
export function useWebSocket(): WebSocketState {
  const [connected, setConnected] = useState(false);
  const [latestIncident, setLatestIncident] = useState<Incident | null>(null);
  const clientRef = useRef<Client | null>(null);

  useEffect(() => {
    const client = new Client({
      webSocketFactory: () => new SockJS(`${SENTINEL_API}/ws/events`),
      reconnectDelay: 5000,
      onConnect: () => {
        setConnected(true);
        client.subscribe('/topic/incidents', (message: IMessage) => {
          const incident: Incident = JSON.parse(message.body);
          setLatestIncident(incident);
        });
      },
      onDisconnect: () => {
        setConnected(false);
      },
      onStompError: (frame) => {
        console.error('STOMP error:', frame);
      },
    });

    client.activate();
    clientRef.current = client;

    return () => {
      client.deactivate();
    };
  }, []);

  return { connected, latestIncident };
}
