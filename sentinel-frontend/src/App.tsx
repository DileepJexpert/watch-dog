import React, { useEffect, useState } from 'react';
import axios from 'axios';
import { ServiceHealthMap } from './components/ServiceHealthMap';
import { ActiveIncidents } from './components/ActiveIncidents';
import { ErrorRateTrend } from './components/ErrorRateTrend';
import { LatencyHeatmap } from './components/LatencyHeatmap';
import { RemediationLog } from './components/RemediationLog';
import { RuleBuilder } from './components/RuleBuilder';
import { AgentConsole } from './components/AgentConsole';
import { useWebSocket } from './hooks/useWebSocket';
import {
  ServiceHealth, Incident, RemediationLog as RemediationLogType, DashboardStats
} from './types';

const SENTINEL_API = import.meta.env.VITE_API_URL || 'http://localhost:8080';
const POLL_INTERVAL = 30_000;

type Tab = 'dashboard' | 'incidents' | 'remediation' | 'rules' | 'agent';

export default function App() {
  const { connected, latestIncident } = useWebSocket();
  const [activeTab, setActiveTab] = useState<Tab>('dashboard');
  const [services, setServices] = useState<ServiceHealth[]>([]);
  const [incidents, setIncidents] = useState<Incident[]>([]);
  const [remediationLogs, setRemediationLogs] = useState<RemediationLogType[]>([]);
  const [stats, setStats] = useState<DashboardStats | null>(null);

  // Polling for data
  useEffect(() => {
    const fetchAll = async () => {
      try {
        const [servicesRes, incidentsRes, logsRes, statsRes] = await Promise.all([
          axios.get(`${SENTINEL_API}/api/dashboard/summary`),
          axios.get(`${SENTINEL_API}/api/dashboard/incidents/active`),
          axios.get(`${SENTINEL_API}/api/remediation/log?size=20`),
          axios.get(`${SENTINEL_API}/api/dashboard/stats`),
        ]);
        setServices(servicesRes.data);
        setIncidents(incidentsRes.data);
        setRemediationLogs(logsRes.data.content || []);
        setStats(statsRes.data);
      } catch (e) {
        console.error('Failed to fetch dashboard data', e);
      }
    };

    fetchAll();
    const interval = setInterval(fetchAll, POLL_INTERVAL);
    return () => clearInterval(interval);
  }, []);

  // Real-time incident injection via WebSocket
  useEffect(() => {
    if (latestIncident) {
      setIncidents(prev => {
        const exists = prev.find(i => i.id === latestIncident.id);
        if (exists) return prev.map(i => i.id === latestIncident.id ? latestIncident : i);
        return [latestIncident, ...prev];
      });
    }
  }, [latestIncident]);

  const handleResolve = async (id: string) => {
    try {
      await axios.post(`${SENTINEL_API}/api/incidents/${id}/resolve`);
      setIncidents(prev => prev.filter(i => i.id !== id));
    } catch (e) {
      console.error('Failed to resolve incident', e);
    }
  };

  // Mock latency data for demo
  const latencyData = services.slice(0, 8).map(s => ({
    service: s.serviceName,
    p50: Math.random() * 200 + 50,
    p95: s.latencyP95 || Math.random() * 1000 + 200,
    p99: s.latencyP99 || Math.random() * 2000 + 500,
  }));

  return (
    <div className="min-h-screen bg-gray-950 text-white">
      {/* Header */}
      <header className="bg-gray-900 border-b border-gray-800 px-6 py-3 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="w-8 h-8 bg-blue-600 rounded flex items-center justify-center font-bold text-sm">W</div>
          <h1 className="text-white font-semibold text-lg">SENTINEL</h1>
          <span className="text-gray-500 text-sm">Unified API Monitoring Platform</span>
        </div>
        <div className="flex items-center gap-4">
          {stats && (
            <div className="flex gap-4 text-sm">
              <span className={`font-medium ${stats.openIncidents > 0 ? 'text-red-400' : 'text-green-400'}`}>
                {stats.openIncidents} open
              </span>
              <span className="text-gray-400">{stats.serviceCount} services</span>
            </div>
          )}
          <div className={`flex items-center gap-1.5 text-xs ${connected ? 'text-green-400' : 'text-red-400'}`}>
            <div className={`w-2 h-2 rounded-full ${connected ? 'bg-green-400' : 'bg-red-400'}`} />
            {connected ? 'Live' : 'Disconnected'}
          </div>
        </div>
      </header>

      {/* Navigation */}
      <nav className="bg-gray-900 border-b border-gray-800 px-6">
        <div className="flex gap-1">
          {(['dashboard', 'incidents', 'remediation', 'rules', 'agent'] as Tab[]).map(tab => (
            <button
              key={tab}
              onClick={() => setActiveTab(tab)}
              className={`px-4 py-3 text-sm capitalize transition-colors border-b-2 ${
                activeTab === tab
                  ? 'text-white border-blue-500'
                  : 'text-gray-400 border-transparent hover:text-gray-300'
              }`}
            >
              {tab === 'agent' ? 'AI Copilot' : tab}
            </button>
          ))}
        </div>
      </nav>

      {/* Main Content */}
      <main className="p-6">
        {activeTab === 'dashboard' && (
          <div className="space-y-6">
            {/* Stats row */}
            {stats && (
              <div className="grid grid-cols-4 gap-4">
                {[
                  { label: 'Open Incidents', value: stats.openIncidents, color: stats.openIncidents > 0 ? 'text-red-400' : 'text-green-400' },
                  { label: 'Last 24h', value: stats.incidentsLast24h, color: 'text-yellow-400' },
                  { label: 'Last 7 days', value: stats.incidentsLast7d, color: 'text-blue-400' },
                  { label: 'Services', value: stats.serviceCount, color: 'text-gray-300' },
                ].map(stat => (
                  <div key={stat.label} className="bg-gray-900 rounded-lg p-4">
                    <p className="text-gray-500 text-xs uppercase tracking-wider">{stat.label}</p>
                    <p className={`text-3xl font-bold mt-1 ${stat.color}`}>{stat.value}</p>
                  </div>
                ))}
              </div>
            )}
            <ServiceHealthMap services={services} />
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
              <ActiveIncidents incidents={incidents.slice(0, 5)} onResolve={handleResolve} />
              <RemediationLog logs={remediationLogs} />
            </div>
            <LatencyHeatmap data={latencyData} />
          </div>
        )}

        {activeTab === 'incidents' && (
          <ActiveIncidents incidents={incidents} onResolve={handleResolve} />
        )}

        {activeTab === 'remediation' && (
          <RemediationLog logs={remediationLogs} />
        )}

        {activeTab === 'rules' && (
          <div className="max-w-2xl">
            <RuleBuilder />
          </div>
        )}

        {activeTab === 'agent' && (
          <AgentConsole />
        )}
      </main>
    </div>
  );
}
