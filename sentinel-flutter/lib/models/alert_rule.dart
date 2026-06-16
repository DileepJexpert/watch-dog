import 'enums.dart';

class AlertRule {
  final String id;
  final String name;
  final String? serviceName;
  final String conditionYaml;
  final Severity severity;
  final bool enabled;
  final String createdBy;
  final DateTime createdAt;

  const AlertRule({
    required this.id,
    required this.name,
    this.serviceName,
    required this.conditionYaml,
    required this.severity,
    required this.enabled,
    required this.createdBy,
    required this.createdAt,
  });

  factory AlertRule.fromJson(Map<String, dynamic> json) {
    return AlertRule(
      id: json['id'] as String,
      name: json['name'] as String,
      serviceName: json['serviceName'] as String?,
      conditionYaml: json['conditionYaml'] as String,
      severity: Severity.fromString(json['severity'] as String),
      enabled: json['enabled'] as bool? ?? true,
      createdBy: json['createdBy'] as String? ?? 'unknown',
      createdAt: DateTime.parse(json['createdAt'] as String),
    );
  }

  Map<String, dynamic> toJson() => {
        'name': name,
        'serviceName': serviceName,
        'conditionYaml': conditionYaml,
        'severity': severity.value,
        'enabled': enabled,
      };
}
