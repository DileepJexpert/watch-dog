import React, { useState } from 'react';
import axios from 'axios';
import { AlertRule, Severity } from '../types';

const SENTINEL_API = import.meta.env.VITE_API_URL || 'http://localhost:8080';

/**
 * Self-service rule builder for creating custom alert rules.
 */
export const RuleBuilder: React.FC = () => {
  const [name, setName] = useState('');
  const [serviceName, setServiceName] = useState('');
  const [severity, setSeverity] = useState<Severity>('P3_MEDIUM');
  const [conditionYaml, setConditionYaml] = useState(
    `conditions:
  - metricName: error_rate_percent
    signalType: METRIC
    comparator: GT
    threshold: 5.0
    durationMinutes: 3`
  );
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState('');

  const handleSave = async () => {
    setSaving(true);
    setMessage('');
    try {
      await axios.post(`${SENTINEL_API}/api/rules`, {
        name,
        serviceName: serviceName || null,
        severity,
        conditionYaml,
        enabled: true,
        createdBy: 'dashboard-user',
      });
      setMessage('Rule created successfully!');
      setName('');
      setServiceName('');
    } catch (e) {
      setMessage('Failed to create rule. Check YAML syntax.');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="p-4 bg-gray-900 rounded-lg">
      <h2 className="text-white text-lg font-semibold mb-4">Rule Builder</h2>
      <div className="space-y-3">
        <div>
          <label className="text-gray-400 text-sm block mb-1">Rule Name *</label>
          <input
            className="w-full bg-gray-800 text-white rounded px-3 py-2 text-sm border border-gray-700 focus:outline-none focus:border-blue-500"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="e.g. Payment Service Latency Alert"
          />
        </div>
        <div>
          <label className="text-gray-400 text-sm block mb-1">Service (optional, leave blank for all)</label>
          <input
            className="w-full bg-gray-800 text-white rounded px-3 py-2 text-sm border border-gray-700 focus:outline-none focus:border-blue-500"
            value={serviceName}
            onChange={(e) => setServiceName(e.target.value)}
            placeholder="e.g. payment-service"
          />
        </div>
        <div>
          <label className="text-gray-400 text-sm block mb-1">Severity</label>
          <select
            className="w-full bg-gray-800 text-white rounded px-3 py-2 text-sm border border-gray-700"
            value={severity}
            onChange={(e) => setSeverity(e.target.value as Severity)}
          >
            <option value="P1_CRITICAL">P1 Critical</option>
            <option value="P2_HIGH">P2 High</option>
            <option value="P3_MEDIUM">P3 Medium</option>
            <option value="P4_INFO">P4 Info</option>
          </select>
        </div>
        <div>
          <label className="text-gray-400 text-sm block mb-1">Condition YAML</label>
          <textarea
            className="w-full bg-gray-800 text-white rounded px-3 py-2 text-sm font-mono border border-gray-700 focus:outline-none focus:border-blue-500"
            rows={8}
            value={conditionYaml}
            onChange={(e) => setConditionYaml(e.target.value)}
          />
        </div>
        <button
          onClick={handleSave}
          disabled={saving || !name}
          className="w-full bg-blue-600 hover:bg-blue-700 disabled:bg-gray-700 text-white py-2 rounded text-sm font-medium transition-colors"
        >
          {saving ? 'Saving...' : 'Create Rule'}
        </button>
        {message && (
          <p className={`text-sm ${message.includes('success') ? 'text-green-400' : 'text-red-400'}`}>
            {message}
          </p>
        )}
      </div>
    </div>
  );
};
