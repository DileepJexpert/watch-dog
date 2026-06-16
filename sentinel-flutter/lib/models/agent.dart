// Mirrors the React types in sentinel-frontend/src/types/agent.ts and the
// backend FR-9 contract at com.sentinel.agent.dto.AgentAnswer.

class AgentEvidence {
  final String source;
  final String ref;
  final String? excerpt;

  const AgentEvidence({required this.source, required this.ref, this.excerpt});

  factory AgentEvidence.fromJson(Map<String, dynamic> json) => AgentEvidence(
        source: json['source'] as String? ?? '',
        ref: json['ref'] as String? ?? '',
        excerpt: json['excerpt'] as String?,
      );
}

class RootCause {
  final String hypothesis;
  final String confidence;

  const RootCause({required this.hypothesis, required this.confidence});

  factory RootCause.fromJson(Map<String, dynamic> json) => RootCause(
        hypothesis: json['hypothesis'] as String? ?? '',
        confidence: json['confidence'] as String? ?? 'low',
      );
}

class RecommendedAction {
  final String action;
  final String rationale;
  final bool requiresApproval;

  const RecommendedAction({
    required this.action,
    required this.rationale,
    required this.requiresApproval,
  });

  factory RecommendedAction.fromJson(Map<String, dynamic> json) => RecommendedAction(
        action: json['action'] as String? ?? '',
        rationale: json['rationale'] as String? ?? '',
        requiresApproval: json['requiresApproval'] as bool? ?? true,
      );
}

class AgentToolInvocation {
  final String id;
  final String name;
  final Map<String, dynamic> args;

  const AgentToolInvocation({required this.id, required this.name, required this.args});

  factory AgentToolInvocation.fromJson(Map<String, dynamic> json) => AgentToolInvocation(
        id: json['id'] as String? ?? '',
        name: json['name'] as String? ?? '',
        args: (json['args'] as Map?)?.cast<String, dynamic>() ?? const {},
      );
}

class AgentStep {
  final int step;
  final String? text;
  final List<AgentToolInvocation> toolCalls;

  const AgentStep({required this.step, this.text, required this.toolCalls});

  factory AgentStep.fromJson(Map<String, dynamic> json) {
    final calls = json['toolCalls'];
    return AgentStep(
      step: json['step'] as int? ?? 0,
      text: json['text'] as String?,
      toolCalls: calls is List
          ? calls.map((e) => AgentToolInvocation.fromJson(e as Map<String, dynamic>)).toList()
          : const [],
    );
  }
}

class AgentAnswer {
  final String summary;
  final RootCause? rootCause;
  final List<AgentEvidence> evidence;
  final List<RecommendedAction> recommendedActions;
  final List<AgentStep> trace;

  const AgentAnswer({
    required this.summary,
    this.rootCause,
    required this.evidence,
    required this.recommendedActions,
    required this.trace,
  });

  factory AgentAnswer.fromJson(Map<String, dynamic> json) {
    return AgentAnswer(
      summary: json['summary'] as String? ?? '',
      rootCause: json['rootCause'] is Map
          ? RootCause.fromJson(Map<String, dynamic>.from(json['rootCause'] as Map))
          : null,
      evidence: (json['evidence'] as List?)
              ?.map((e) => AgentEvidence.fromJson(e as Map<String, dynamic>))
              .toList() ??
          const [],
      recommendedActions: (json['recommendedActions'] as List?)
              ?.map((e) => RecommendedAction.fromJson(e as Map<String, dynamic>))
              .toList() ??
          const [],
      trace: (json['trace'] as List?)
              ?.map((e) => AgentStep.fromJson(e as Map<String, dynamic>))
              .toList() ??
          const [],
    );
  }
}

class AgentStatus {
  final bool enabled;
  final String? mode;
  final String? model;
  final String? provider;
  final bool baseUrlConfigured;
  final bool redactionEnabled;
  final bool modelSwitchSupported;
  final String? message;

  const AgentStatus({
    required this.enabled,
    this.mode,
    this.model,
    this.provider,
    this.baseUrlConfigured = false,
    this.redactionEnabled = false,
    this.modelSwitchSupported = false,
    this.message,
  });

  factory AgentStatus.fromJson(Map<String, dynamic> json) => AgentStatus(
        enabled: json['enabled'] as bool? ?? false,
        mode: json['mode'] as String?,
        model: json['model'] as String?,
        provider: json['provider'] as String?,
        baseUrlConfigured: json['baseUrlConfigured'] as bool? ?? false,
        redactionEnabled: json['redactionEnabled'] as bool? ?? false,
        modelSwitchSupported: json['modelSwitchSupported'] as bool? ?? false,
        message: json['message'] as String?,
      );

  static const AgentStatus disabled = AgentStatus(enabled: false);
}

class OllamaModel {
  final String name;
  final int size;
  final String? family;
  final String? parameterSize;
  final String? quantization;

  const OllamaModel({
    required this.name,
    this.size = 0,
    this.family,
    this.parameterSize,
    this.quantization,
  });

  factory OllamaModel.fromJson(Map<String, dynamic> json) => OllamaModel(
        name: json['name'] as String? ?? '',
        size: (json['size'] as num?)?.toInt() ?? 0,
        family: json['family'] as String?,
        parameterSize: json['parameterSize'] as String?,
        quantization: json['quantization'] as String?,
      );

  String get sizeLabel {
    if (size <= 0) return '';
    final gb = size / (1024 * 1024 * 1024);
    if (gb >= 1) return '${gb.toStringAsFixed(1)} GB';
    final mb = size / (1024 * 1024);
    return '${mb.toStringAsFixed(0)} MB';
  }
}

class ModelsResponse {
  final String provider;
  final String active;
  final List<OllamaModel> models;

  const ModelsResponse({
    required this.provider,
    required this.active,
    required this.models,
  });

  factory ModelsResponse.fromJson(Map<String, dynamic> json) => ModelsResponse(
        provider: json['provider'] as String? ?? '',
        active: json['active'] as String? ?? '',
        models: (json['models'] as List?)
                ?.map((e) => OllamaModel.fromJson(e as Map<String, dynamic>))
                .toList() ??
            const [],
      );
}

enum AgentTurnRole { user, assistant }

enum AgentTurnStatus { streaming, done, error }

class AgentChatTurn {
  final AgentTurnRole role;
  String text;
  AgentAnswer? answer;
  List<AgentStep> steps;
  AgentTurnStatus status;

  AgentChatTurn({
    required this.role,
    required this.text,
    this.answer,
    List<AgentStep>? steps,
    this.status = AgentTurnStatus.done,
  }) : steps = steps ?? <AgentStep>[];
}

/// Envelope SENTINEL sends to /topic/agent/{sessionId} during streaming.
///   { "type": "step" | "final" | "error", "payload": {...} }
class AgentStreamEnvelope {
  final String type;
  final dynamic payload;

  const AgentStreamEnvelope({required this.type, required this.payload});

  factory AgentStreamEnvelope.fromJson(Map<String, dynamic> json) =>
      AgentStreamEnvelope(
        type: json['type'] as String? ?? '',
        payload: json['payload'],
      );

  bool get isStep => type == 'step';
  bool get isFinal => type == 'final';
  bool get isError => type == 'error';
}
