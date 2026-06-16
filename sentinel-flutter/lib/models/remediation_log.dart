class RemediationLog {
  final String id;
  final String? incidentId;
  final String actionType;
  final String serviceName;
  final Map<String, dynamic> parameters;
  final String outcome;
  final DateTime executedAt;
  final String executedBy;
  final String? failureReason;

  const RemediationLog({
    required this.id,
    this.incidentId,
    required this.actionType,
    required this.serviceName,
    required this.parameters,
    required this.outcome,
    required this.executedAt,
    required this.executedBy,
    this.failureReason,
  });

  factory RemediationLog.fromJson(Map<String, dynamic> json) {
    return RemediationLog(
      id: json['id'] as String,
      incidentId: json['incidentId'] as String?,
      actionType: json['actionType'] as String,
      serviceName: json['serviceName'] as String,
      parameters: json['parameters'] as Map<String, dynamic>? ?? {},
      outcome: json['outcome'] as String,
      executedAt: DateTime.parse(json['executedAt'] as String),
      executedBy: json['executedBy'] as String,
      failureReason: json['failureReason'] as String?,
    );
  }
}
