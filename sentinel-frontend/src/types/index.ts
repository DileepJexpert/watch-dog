export type Severity = 'P1_CRITICAL' | 'P2_HIGH' | 'P3_MEDIUM' | 'P4_INFO';
export type IncidentStatus = 'OPEN' | 'INVESTIGATING' | 'AUTO_REMEDIATED' | 'RESOLVED' | 'ESCALATED';
export type ServiceStatus = 'GREEN' | 'YELLOW' | 'RED';
export type SignalType = 'LOG' | 'TRACE' | 'METRIC' | 'PROBE';

export interface Incident {
  id: string;
  serviceName: string;
  title: string;
  severity: Severity;
  status: IncidentStatus;
  correlationRule: string;
  detectedAt: string;
  resolvedAt?: string;
  autoRemediated: boolean;
  correlatedSignalIds: string[];
}

export interface ServiceHealth {
  serviceName: string;
  status: ServiceStatus;
  errorRate: number;
  latencyP95: number;
  latencyP99: number;
  requestRate: number;
  lastUpdated: string;
  activeIncidentId?: string;
}

export interface RemediationLog {
  id: string;
  incidentId: string;
  actionType: string;
  serviceName: string;
  parameters: Record<string, unknown>;
  outcome: string;
  executedAt: string;
  executedBy: string;
  failureReason?: string;
}

export interface AlertRule {
  id: string;
  name: string;
  serviceName?: string;
  conditionYaml: string;
  severity: Severity;
  enabled: boolean;
  createdBy: string;
  createdAt: string;
}

export interface DashboardStats {
  openIncidents: number;
  incidentsLast24h: number;
  incidentsLast7d: number;
  serviceCount: number;
}
