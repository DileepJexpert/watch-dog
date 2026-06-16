import 'enums.dart';

class Incident {
  final String id;
  final String serviceName;
  final String title;
  final Severity severity;
  final IncidentStatus status;
  final String? correlationRule;
  final DateTime detectedAt;
  final DateTime? resolvedAt;
  final bool autoRemediated;
  final List<String> correlatedSignalIds;

  const Incident({
    required this.id,
    required this.serviceName,
    required this.title,
    required this.severity,
    required this.status,
    this.correlationRule,
    required this.detectedAt,
    this.resolvedAt,
    this.autoRemediated = false,
    this.correlatedSignalIds = const [],
  });

  factory Incident.fromJson(Map<String, dynamic> json) {
    return Incident(
      id: json['id'] as String,
      serviceName: json['serviceName'] as String,
      title: json['title'] as String,
      severity: Severity.fromString(json['severity'] as String),
      status: IncidentStatus.fromString(json['status'] as String),
      correlationRule: json['correlationRule'] as String?,
      detectedAt: DateTime.parse(json['detectedAt'] as String),
      resolvedAt: json['resolvedAt'] != null
          ? DateTime.parse(json['resolvedAt'] as String)
          : null,
      autoRemediated: json['autoRemediated'] as bool? ?? false,
      correlatedSignalIds: (json['correlatedSignalIds'] as List<dynamic>?)
              ?.map((e) => e as String)
              .toList() ??
          [],
    );
  }

  Map<String, dynamic> toJson() => {
        'id': id,
        'serviceName': serviceName,
        'title': title,
        'severity': severity.value,
        'status': status.value,
        'correlationRule': correlationRule,
        'detectedAt': detectedAt.toIso8601String(),
        'resolvedAt': resolvedAt?.toIso8601String(),
        'autoRemediated': autoRemediated,
        'correlatedSignalIds': correlatedSignalIds,
      };
}
