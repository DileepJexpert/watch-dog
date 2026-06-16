import 'package:flutter_test/flutter_test.dart';
import 'package:sentinel_flutter/models/models.dart';

void main() {
  group('Severity', () {
    test('fromString parses correctly', () {
      expect(Severity.fromString('P1_CRITICAL'), Severity.p1Critical);
      expect(Severity.fromString('P2_HIGH'), Severity.p2High);
      expect(Severity.fromString('P3_MEDIUM'), Severity.p3Medium);
      expect(Severity.fromString('P4_INFO'), Severity.p4Info);
    });

    test('fromString returns p4Info for unknown', () {
      expect(Severity.fromString('UNKNOWN'), Severity.p4Info);
    });

    test('isHigherThan works', () {
      expect(Severity.p1Critical.isHigherThan(Severity.p2High), true);
      expect(Severity.p3Medium.isHigherThan(Severity.p1Critical), false);
    });
  });

  group('Incident', () {
    test('fromJson parses correctly', () {
      final json = {
        'id': 'test-id',
        'serviceName': 'payment-service',
        'title': 'High Error Rate',
        'severity': 'P1_CRITICAL',
        'status': 'OPEN',
        'correlationRule': 'HighErrorRateRule',
        'detectedAt': '2026-03-17T10:00:00Z',
        'resolvedAt': null,
        'autoRemediated': false,
        'correlatedSignalIds': ['sig-1', 'sig-2'],
      };

      final incident = Incident.fromJson(json);
      expect(incident.id, 'test-id');
      expect(incident.serviceName, 'payment-service');
      expect(incident.severity, Severity.p1Critical);
      expect(incident.status, IncidentStatus.open);
      expect(incident.correlatedSignalIds.length, 2);
    });

    test('toJson round-trips', () {
      final incident = Incident(
        id: 'abc',
        serviceName: 'svc',
        title: 'Test',
        severity: Severity.p2High,
        status: IncidentStatus.resolved,
        detectedAt: DateTime.utc(2026, 3, 17),
      );

      final json = incident.toJson();
      final restored = Incident.fromJson(json);
      expect(restored.id, incident.id);
      expect(restored.severity, incident.severity);
    });
  });

  group('ServiceHealth', () {
    test('fromJson parses correctly', () {
      final json = {
        'serviceName': 'order-service',
        'status': 'YELLOW',
        'errorRate': 2.5,
        'latencyP95': 450.0,
        'latencyP99': 1200.0,
        'requestRate': 100.0,
        'lastUpdated': '2026-03-17T10:00:00Z',
      };

      final health = ServiceHealth.fromJson(json);
      expect(health.status, ServiceStatus.yellow);
      expect(health.errorRate, 2.5);
    });
  });

  group('DashboardStats', () {
    test('fromJson parses correctly', () {
      final json = {
        'openIncidents': 3,
        'incidentsLast24h': 7,
        'incidentsLast7d': 25,
        'serviceCount': 12,
      };

      final stats = DashboardStats.fromJson(json);
      expect(stats.openIncidents, 3);
      expect(stats.serviceCount, 12);
    });

    test('empty constant works', () {
      expect(DashboardStats.empty.openIncidents, 0);
    });
  });

  group('RemediationLog', () {
    test('fromJson parses correctly', () {
      final json = {
        'id': 'rem-1',
        'incidentId': 'inc-1',
        'actionType': 'POD_RESTART',
        'serviceName': 'payment-service',
        'parameters': {'replicas': 3},
        'outcome': 'SUCCESS',
        'executedAt': '2026-03-17T10:00:00Z',
        'executedBy': 'AUTO',
        'failureReason': null,
      };

      final log = RemediationLog.fromJson(json);
      expect(log.actionType, 'POD_RESTART');
      expect(log.outcome, 'SUCCESS');
    });
  });
}
