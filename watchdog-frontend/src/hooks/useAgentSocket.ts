import { useEffect, useRef, useState, useCallback } from 'react';
import { Client, IMessage, StompSubscription } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { AgentStreamEnvelope } from '../types/agent';

const WATCHDOG_API = import.meta.env.VITE_API_URL || 'http://localhost:8080';
const DEBUG = import.meta.env.DEV || import.meta.env.VITE_DEBUG_AGENT === 'true';
const dbg = (...args: unknown[]) => { if (DEBUG) console.debug('[agent-ws]', ...args); };

/**
 * Mirrors the pattern in useWebSocket but talks to /ws/agent and lets the
 * caller subscribe to a per-session topic on demand (/topic/agent/{sessionId}).
 *
 * Verbose console.debug tracing under `import.meta.env.DEV` — visible in the
 * browser devtools when running `npm run dev`.
 */
export function useAgentSocket() {
  const clientRef = useRef<Client | null>(null);
  const [connected, setConnected] = useState(false);

  useEffect(() => {
    dbg('connecting →', `${WATCHDOG_API}/ws/agent`);
    const client = new Client({
      webSocketFactory: () => new SockJS(`${WATCHDOG_API}/ws/agent`),
      reconnectDelay: 5000,
      onConnect: () => { dbg('connected'); setConnected(true); },
      onDisconnect: () => { dbg('disconnected'); setConnected(false); },
      onStompError: (frame) => console.error('[agent-ws] STOMP error:', frame),
      onWebSocketError: (e) => console.error('[agent-ws] socket error:', e),
      onWebSocketClose: () => dbg('socket closed'),
    });
    client.activate();
    clientRef.current = client;
    return () => { dbg('deactivating'); client.deactivate(); };
  }, []);

  const subscribeToSession = useCallback((sessionId: string, onMessage: (env: AgentStreamEnvelope) => void): StompSubscription | undefined => {
    const client = clientRef.current;
    if (!client || !client.connected) {
      dbg('subscribe rejected — client not connected', { sessionId, connected: client?.connected });
      return undefined;
    }
    const topic = `/topic/agent/${sessionId}`;
    dbg('subscribe →', topic);
    return client.subscribe(topic, (m: IMessage) => {
      try {
        const env: AgentStreamEnvelope = JSON.parse(m.body);
        dbg('frame ←', env.type, env.payload);
        onMessage(env);
      } catch (e) {
        console.error('[agent-ws] parse error', e, m.body);
      }
    });
  }, []);

  return { connected, subscribeToSession };
}
