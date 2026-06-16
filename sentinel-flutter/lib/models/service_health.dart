import 'enums.dart';

class ServiceHealth {
  final String serviceName;
  final ServiceStatus status;
  final double errorRate;
  final double latencyP95;
  final double latencyP99;
  final double requestRate;
  final DateTime lastUpdated;
  final String? activeIncidentId;

  const ServiceHealth({
    required this.serviceName,
    required this.status,
    required this.errorRate,
    required this.latencyP95,
    required this.latencyP99,
    required this.requestRate,
    required this.lastUpdated,
    this.activeIncidentId,
  });

  factory ServiceHealth.fromJson(Map<String, dynamic> json) {
    return ServiceHealth(
      serviceName: json['serviceName'] as String,
      status: ServiceStatus.fromString(json['status'] as String),
      errorRate: (json['errorRate'] as num).toDouble(),
      latencyP95: (json['latencyP95'] as num).toDouble(),
      latencyP99: (json['latencyP99'] as num).toDouble(),
      requestRate: (json['requestRate'] as num).toDouble(),
      lastUpdated: DateTime.parse(json['lastUpdated'] as String),
      activeIncidentId: json['activeIncidentId'] as String?,
    );
  }
}
