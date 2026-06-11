import { useEffect, useRef, useState, useCallback } from 'react';
import { Client, IMessage, StompSubscription } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { AgentStreamEnvelope } from '../types/agent';

const WATCHDOG_API = import.meta.env.VITE_API_URL || 'http://localhost:8080';

/**
 * Mirrors the pattern in useWebSocket but talks to /ws/agent and lets the
 * caller subscribe to a per-session topic on demand (/topic/agent/{sessionId}).
 */
export function useAgentSocket() {
  const clientRef = useRef<Client | null>(null);
  const [connected, setConnected] = useState(false);

  useEffect(() => {
    const client = new Client({
      webSocketFactory: () => new SockJS(`${WATCHDOG_API}/ws/agent`),
      reconnectDelay: 5000,
      onConnect: () => setConnected(true),
      onDisconnect: () => setConnected(false),
      onStompError: (frame) => console.error('agent STOMP error:', frame),
    });
    client.activate();
    clientRef.current = client;
    return () => { client.deactivate(); };
  }, []);

  const subscribeToSession = useCallback((sessionId: string, onMessage: (env: AgentStreamEnvelope) => void): StompSubscription | undefined => {
    const client = clientRef.current;
    if (!client || !client.connected) return undefined;
    return client.subscribe(`/topic/agent/${sessionId}`, (m: IMessage) => {
      try {
        const env: AgentStreamEnvelope = JSON.parse(m.body);
        onMessage(env);
      } catch (e) {
        console.error('agent stream parse error', e);
      }
    });
  }, []);

  return { connected, subscribeToSession };
}
