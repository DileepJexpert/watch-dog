import React from 'react';
import { RemediationLog as RemediationLogType } from '../types';
import { formatDistanceToNow } from 'date-fns';

interface Props {
  logs: RemediationLogType[];
}

const outcomeColor: Record<string, string> = {
  SUCCESS: 'text-green-400',
  FAILED: 'text-red-400',
  SKIPPED: 'text-yellow-400',
  DRY_RUN: 'text-blue-400',
};

const outcomeIcon: Record<string, string> = {
  SUCCESS: '✓',
  FAILED: '✗',
  SKIPPED: '⊘',
  DRY_RUN: '⬡',
};

/**
 * Timeline of automated remediation actions with outcomes.
 */
export const RemediationLog: React.FC<Props> = ({ logs }) => {
  return (
    <div className="p-4 bg-gray-900 rounded-lg">
      <h2 className="text-white text-lg font-semibold mb-4">Remediation Log</h2>
      <div className="space-y-2 max-h-80 overflow-y-auto">
        {logs.length === 0 && (
          <div className="text-gray-500 text-center py-8">
            No auto-remediation actions taken yet.
          </div>
        )}
        {logs.map((log) => {
          const color = outcomeColor[log.outcome] || 'text-gray-400';
          const icon = outcomeIcon[log.outcome] || '?';
          return (
            <div key={log.id} className="flex items-start gap-3 py-2 border-b border-gray-800">
              <span className={`text-lg mt-0.5 ${color}`}>{icon}</span>
              <div className="flex-1">
                <div className="flex items-center justify-between">
                  <span className="text-white text-sm font-medium">
                    {log.actionType.replace(/_/g, ' ')}
                  </span>
                  <span className={`text-xs font-bold ${color}`}>{log.outcome}</span>
                </div>
                <p className="text-gray-400 text-xs">
                  Service: {log.serviceName} · By: {log.executedBy}
                  {log.failureReason && ` · ${log.failureReason}`}
                </p>
                <p className="text-gray-600 text-xs">
                  {formatDistanceToNow(new Date(log.executedAt), { addSuffix: true })}
                </p>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};
