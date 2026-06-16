import React from 'react';
import { Incident, Severity } from '../types';
import { formatDistanceToNow } from 'date-fns';

interface Props {
  incidents: Incident[];
  onResolve?: (id: string) => void;
}

const severityBadge: Record<Severity, string> = {
  P1_CRITICAL: 'bg-red-700 text-white',
  P2_HIGH: 'bg-orange-600 text-white',
  P3_MEDIUM: 'bg-yellow-500 text-black',
  P4_INFO: 'bg-blue-600 text-white',
};

const severityLabel: Record<Severity, string> = {
  P1_CRITICAL: 'P1 CRITICAL',
  P2_HIGH: 'P2 HIGH',
  P3_MEDIUM: 'P3 MEDIUM',
  P4_INFO: 'P4 INFO',
};

/**
 * Active incidents panel showing correlated incidents with severity, service, and actions.
 */
export const ActiveIncidents: React.FC<Props> = ({ incidents, onResolve }) => {
  return (
    <div className="p-4 bg-gray-900 rounded-lg">
      <h2 className="text-white text-lg font-semibold mb-4">
        Active Incidents
        {incidents.length > 0 && (
          <span className="ml-2 bg-red-600 text-white text-xs px-2 py-0.5 rounded-full">
            {incidents.length}
          </span>
        )}
      </h2>
      <div className="space-y-3">
        {incidents.length === 0 && (
          <div className="text-green-400 text-center py-8">
            ✓ No active incidents
          </div>
        )}
        {incidents.map((incident) => (
          <div key={incident.id} className="bg-gray-800 rounded-lg p-4 border border-gray-700">
            <div className="flex items-start justify-between">
              <div className="flex-1">
                <div className="flex items-center gap-2 mb-1">
                  <span className={`text-xs px-2 py-0.5 rounded font-bold ${severityBadge[incident.severity]}`}>
                    {severityLabel[incident.severity]}
                  </span>
                  <span className="text-gray-400 text-xs">{incident.serviceName}</span>
                  {incident.autoRemediated && (
                    <span className="text-xs bg-blue-800 text-blue-200 px-2 py-0.5 rounded">
                      AUTO-REMEDIATED
                    </span>
                  )}
                </div>
                <p className="text-white text-sm font-medium">{incident.title}</p>
                <p className="text-gray-500 text-xs mt-1">
                  Rule: {incident.correlationRule} · Detected{' '}
                  {formatDistanceToNow(new Date(incident.detectedAt), { addSuffix: true })}
                </p>
              </div>
              {onResolve && (
                <button
                  onClick={() => onResolve(incident.id)}
                  className="ml-3 text-xs bg-gray-700 hover:bg-gray-600 text-gray-300 px-3 py-1.5 rounded transition-colors"
                >
                  Resolve
                </button>
              )}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
};
