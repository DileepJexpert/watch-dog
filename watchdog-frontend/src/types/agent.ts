// FR-9 output contract — must match com.watchdog.agent.dto.AgentAnswer

export interface AgentEvidence {
  source: string;
  ref: string;
  excerpt?: string;
}

export interface RootCause {
  hypothesis: string;
  confidence: 'low' | 'medium' | 'high';
}

export interface RecommendedAction {
  action: string;
  rationale: string;
  requiresApproval: boolean;
}

export interface AgentStep {
  step: number;
  text?: string;
  toolCalls?: Array<{ id: string; name: string; args: Record<string, unknown> }>;
}

export interface AgentAnswer {
  summary: string;
  rootCause?: RootCause | null;
  evidence?: AgentEvidence[];
  recommendedActions?: RecommendedAction[];
  trace?: AgentStep[];
}

export type AgentChatRole = 'user' | 'assistant';

export interface AgentChatTurn {
  role: AgentChatRole;
  text: string;
  answer?: AgentAnswer;
  steps?: AgentStep[];
  status?: 'streaming' | 'done' | 'error';
}

export interface AgentStatus {
  enabled: boolean;
  mode?: string;
  model?: string;
  baseUrlConfigured?: boolean;
  redactionEnabled?: boolean;
  message?: string;
}

export interface AgentStreamEnvelope {
  type: 'step' | 'final' | 'error';
  payload: any;
}
