import React, { useEffect, useMemo, useRef, useState } from 'react';
import axios from 'axios';
import { useAgentSocket } from '../hooks/useAgentSocket';
import {
  AgentAnswer, AgentChatTurn, AgentStatus, AgentStep, AgentStreamEnvelope
} from '../types/agent';

const WATCHDOG_API = import.meta.env.VITE_API_URL || 'http://localhost:8080';

const confidenceColor = (c?: string) => {
  switch (c) {
    case 'high': return 'text-green-400';
    case 'medium': return 'text-yellow-400';
    case 'low': return 'text-orange-400';
    default: return 'text-gray-400';
  }
};

const sourceBadge: Record<string, string> = {
  logs: 'bg-blue-700 text-blue-100',
  traces: 'bg-purple-700 text-purple-100',
  metrics: 'bg-green-700 text-green-100',
  db: 'bg-yellow-700 text-yellow-100',
  runtime: 'bg-pink-700 text-pink-100',
  knowledge: 'bg-indigo-700 text-indigo-100',
  correlation: 'bg-red-700 text-red-100',
  anomaly: 'bg-orange-700 text-orange-100',
  pod: 'bg-cyan-700 text-cyan-100',
};

/**
 * FR-3 agent console.
 *
 * Synchronous fallback works without a websocket; when the socket is connected
 * we use the streaming endpoint so the user sees tool calls as they happen.
 */
export const AgentConsole: React.FC = () => {
  const [status, setStatus] = useState<AgentStatus | null>(null);
  const [turns, setTurns] = useState<AgentChatTurn[]>([]);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const [expandedTurns, setExpandedTurns] = useState<Record<number, boolean>>({});
  const { connected, subscribeToSession } = useAgentSocket();
  const scrollerRef = useRef<HTMLDivElement>(null);

  const sessionId = useMemo(
    () => `ui-${Math.random().toString(36).slice(2, 10)}`,
    []
  );

  useEffect(() => {
    axios.get(`${WATCHDOG_API}/api/agent/status`)
      .then(res => setStatus(res.data))
      .catch(() => setStatus({ enabled: false, message: 'Agent status endpoint unavailable' }));
  }, []);

  useEffect(() => {
    if (scrollerRef.current) {
      scrollerRef.current.scrollTop = scrollerRef.current.scrollHeight;
    }
  }, [turns]);

  const send = async () => {
    if (!input.trim() || sending) return;
    const question = input.trim();
    setInput('');
    setSending(true);

    const userTurn: AgentChatTurn = { role: 'user', text: question };
    const placeholder: AgentChatTurn = { role: 'assistant', text: '', status: 'streaming', steps: [] };
    setTurns(prev => [...prev, userTurn, placeholder]);
    const placeholderIndex = turns.length + 1;

    const history = turns
      .filter(t => t.text)
      .map(t => ({ role: t.role.toUpperCase(), content: t.text }));

    const tryStream = connected;
    if (tryStream) {
      try {
        const sub = subscribeToSession(sessionId, (env: AgentStreamEnvelope) => {
          if (env.type === 'step') {
            const step: AgentStep = env.payload;
            setTurns(prev => prev.map((t, i) => i === placeholderIndex
              ? { ...t, steps: [...(t.steps ?? []), step] } : t));
          } else if (env.type === 'final') {
            const answer: AgentAnswer = env.payload;
            setTurns(prev => prev.map((t, i) => i === placeholderIndex
              ? { ...t, answer, text: answer.summary ?? '', status: 'done' } : t));
            setSending(false);
          } else if (env.type === 'error') {
            setTurns(prev => prev.map((t, i) => i === placeholderIndex
              ? { ...t, text: env.payload?.message ?? 'Agent error', status: 'error' } : t));
            setSending(false);
          }
        });

        await axios.post(`${WATCHDOG_API}/api/agent/ask/stream`, {
          question, history, sessionId
        });

        setTimeout(() => sub?.unsubscribe(), 5 * 60_000);
        return;
      } catch (e) {
        console.warn('streaming failed, falling back to sync', e);
      }
    }

    try {
      const res = await axios.post<AgentAnswer>(`${WATCHDOG_API}/api/agent/ask`, {
        question, history, sessionId
      });
      const answer = res.data;
      setTurns(prev => prev.map((t, i) => i === placeholderIndex
        ? { ...t, answer, text: answer.summary ?? '', status: 'done' } : t));
    } catch (e: any) {
      setTurns(prev => prev.map((t, i) => i === placeholderIndex
        ? { ...t, text: e?.message ?? 'Agent error', status: 'error' } : t));
    } finally {
      setSending(false);
    }
  };

  const toggle = (idx: number) =>
    setExpandedTurns(prev => ({ ...prev, [idx]: !prev[idx] }));

  if (status && !status.enabled) {
    return (
      <div className="bg-gray-900 rounded-lg p-6 max-w-3xl">
        <h2 className="text-white text-lg font-semibold mb-2">AI Copilot</h2>
        <p className="text-gray-400 text-sm">
          {status.message ?? 'Agent layer disabled.'}
        </p>
        <pre className="bg-gray-950 text-gray-400 text-xs p-3 mt-3 rounded overflow-x-auto">
{`# enable the AI layer
AGENT_ENABLED=true
AGENT_MODE=advisory
LLM_BASE_URL=https://ai-hub.prod-dev.idfcfirstbank.com/v1
LLM_API_KEY=<...>
LLM_MODEL=<...>`}
        </pre>
      </div>
    );
  }

  return (
    <div className="bg-gray-900 rounded-lg flex flex-col" style={{ height: 'calc(100vh - 200px)' }}>
      <div className="px-4 py-3 border-b border-gray-800 flex items-center justify-between">
        <div>
          <h2 className="text-white font-semibold">AI Copilot</h2>
          <p className="text-xs text-gray-500">
            Mode: <span className="text-gray-300">{status?.mode ?? '—'}</span>
            {' · '}Model: <span className="text-gray-300">{status?.model ?? '—'}</span>
            {' · '}Stream: <span className={connected ? 'text-green-400' : 'text-gray-500'}>{connected ? 'live' : 'sync'}</span>
          </p>
        </div>
        <span className="text-xs text-orange-400 bg-orange-900/30 px-2 py-1 rounded">
          Advisory only — no actions are executed
        </span>
      </div>

      <div ref={scrollerRef} className="flex-1 overflow-y-auto p-4 space-y-4">
        {turns.length === 0 && (
          <div className="text-gray-500 text-sm text-center py-12">
            Ask anything. Example: <span className="text-gray-300">
            "why is the payments API slow right now?"</span>
          </div>
        )}
        {turns.map((turn, idx) => (
          <div key={idx} className={turn.role === 'user' ? 'flex justify-end' : ''}>
            {turn.role === 'user' ? (
              <div className="bg-blue-600 text-white text-sm rounded-lg px-4 py-2 max-w-xl">
                {turn.text}
              </div>
            ) : (
              <div className="bg-gray-800 rounded-lg p-4 max-w-3xl">
                {turn.status === 'streaming' && !turn.answer && (
                  <div className="text-gray-500 text-sm">
                    Investigating
                    <span className="animate-pulse">…</span>
                    {turn.steps && turn.steps.length > 0 && (
                      <div className="mt-2 text-xs text-gray-400">
                        {turn.steps[turn.steps.length - 1].toolCalls?.map(tc => tc.name).join(', ')}
                      </div>
                    )}
                  </div>
                )}
                {turn.status === 'error' && (
                  <div className="text-red-400 text-sm">{turn.text}</div>
                )}
                {turn.answer && (
                  <AgentAnswerView
                    answer={turn.answer}
                    expanded={!!expandedTurns[idx]}
                    onToggle={() => toggle(idx)}
                  />
                )}
              </div>
            )}
          </div>
        ))}
      </div>

      <div className="border-t border-gray-800 p-3 flex gap-2">
        <input
          type="text"
          value={input}
          onChange={e => setInput(e.target.value)}
          onKeyDown={e => { if (e.key === 'Enter') send(); }}
          disabled={sending}
          placeholder="Ask about a service, an incident, or a metric…"
          className="flex-1 bg-gray-800 text-white text-sm rounded px-3 py-2 outline-none border border-gray-700 focus:border-blue-500"
        />
        <button
          onClick={send}
          disabled={sending || !input.trim()}
          className="bg-blue-600 disabled:bg-gray-700 disabled:text-gray-500 text-white text-sm px-4 rounded transition-colors"
        >
          {sending ? 'Working…' : 'Ask'}
        </button>
      </div>
    </div>
  );
};

interface AgentAnswerViewProps {
  answer: AgentAnswer;
  expanded: boolean;
  onToggle: () => void;
}

const AgentAnswerView: React.FC<AgentAnswerViewProps> = ({ answer, expanded, onToggle }) => (
  <div className="space-y-3 text-sm">
    <p className="text-white whitespace-pre-wrap">{answer.summary}</p>
    {answer.rootCause && (
      <div className="bg-gray-900/60 rounded p-3 border border-gray-700">
        <p className="text-xs uppercase tracking-wider text-gray-500 mb-1">Root cause hypothesis</p>
        <p className="text-white">{answer.rootCause.hypothesis}</p>
        <p className={`text-xs mt-1 ${confidenceColor(answer.rootCause.confidence)}`}>
          confidence: {answer.rootCause.confidence}
        </p>
      </div>
    )}
    {answer.recommendedActions && answer.recommendedActions.length > 0 && (
      <div>
        <p className="text-xs uppercase tracking-wider text-gray-500 mb-1">Recommended actions</p>
        <ul className="space-y-2">
          {answer.recommendedActions.map((a, i) => (
            <li key={i} className="bg-gray-900/60 rounded p-3 border border-gray-700">
              <p className="text-white">{a.action}</p>
              <p className="text-gray-400 text-xs mt-1">{a.rationale}</p>
              {a.requiresApproval && (
                <p className="text-orange-400 text-xs mt-1">Requires human approval before execution</p>
              )}
            </li>
          ))}
        </ul>
      </div>
    )}
    <button
      onClick={onToggle}
      className="text-xs text-blue-400 hover:text-blue-300"
    >
      {expanded ? 'Hide' : 'Show'} evidence ({answer.evidence?.length ?? 0})
    </button>
    {expanded && (
      <div className="space-y-1">
        {(answer.evidence ?? []).map((ev, i) => (
          <div key={i} className="text-xs flex gap-2 items-start">
            <span className={`px-1.5 py-0.5 rounded font-mono ${sourceBadge[ev.source] ?? 'bg-gray-700 text-gray-200'}`}>
              {ev.source}
            </span>
            <span className="text-gray-400 font-mono">{ev.ref}</span>
            {ev.excerpt && <span className="text-gray-500 truncate max-w-md">{ev.excerpt}</span>}
          </div>
        ))}
        {answer.trace && answer.trace.length > 0 && (
          <details className="mt-3">
            <summary className="text-xs text-gray-500 cursor-pointer">
              Tool-call trace ({answer.trace.length} step{answer.trace.length === 1 ? '' : 's'})
            </summary>
            <pre className="text-xs text-gray-500 bg-gray-950 p-2 rounded mt-1 overflow-x-auto max-h-60">
{JSON.stringify(answer.trace, null, 2)}
            </pre>
          </details>
        )}
      </div>
    )}
  </div>
);
