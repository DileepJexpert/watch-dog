import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../models/models.dart';
import '../services/sentinel_provider.dart';

class IncidentsScreen extends StatelessWidget {
  const IncidentsScreen({super.key});

  Color _severityColor(Severity severity) {
    switch (severity) {
      case Severity.p1Critical:
        return const Color(0xFFEF4444);
      case Severity.p2High:
        return const Color(0xFFF97316);
      case Severity.p3Medium:
        return const Color(0xFFFBBF24);
      case Severity.p4Info:
        return const Color(0xFF60A5FA);
    }
  }

  Color _statusColor(IncidentStatus status) {
    switch (status) {
      case IncidentStatus.open:
        return const Color(0xFFEF4444);
      case IncidentStatus.investigating:
        return const Color(0xFFF97316);
      case IncidentStatus.autoRemediated:
        return const Color(0xFF60A5FA);
      case IncidentStatus.resolved:
        return const Color(0xFF10B981);
      case IncidentStatus.escalated:
        return const Color(0xFFA855F7);
    }
  }

  String _timeAgo(DateTime dt) {
    final diff = DateTime.now().difference(dt);
    if (diff.inDays > 0) return '${diff.inDays}d ago';
    if (diff.inHours > 0) return '${diff.inHours}h ago';
    if (diff.inMinutes > 0) return '${diff.inMinutes}m ago';
    return 'just now';
  }

  @override
  Widget build(BuildContext context) {
    return Consumer<SentinelProvider>(
      builder: (context, provider, _) {
        final incidents = provider.incidents;
        return Container(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  const Text(
                    'All Incidents',
                    style: TextStyle(
                      color: Colors.white,
                      fontSize: 22,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  Text(
                    '${incidents.length} total',
                    style: const TextStyle(
                      color: Color(0xFF9CA3AF),
                      fontSize: 14,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 16),
              Expanded(
                child: incidents.isEmpty
                    ? const Center(
                        child: Text(
                          'No incidents found.',
                          style: TextStyle(color: Color(0xFF6B7280)),
                        ),
                      )
                    : ListView.builder(
                        itemCount: incidents.length,
                        itemBuilder: (context, index) {
                          final incident = incidents[index];
                          return Container(
                            margin: const EdgeInsets.only(bottom: 8),
                            padding: const EdgeInsets.all(16),
                            decoration: BoxDecoration(
                              color: const Color(0xFF111827),
                              borderRadius: BorderRadius.circular(12),
                              border: Border.all(
                                color: const Color(0xFF1F2937),
                              ),
                            ),
                            child: Row(
                              children: [
                                // Severity badge
                                Container(
                                  width: 48,
                                  padding: const EdgeInsets.symmetric(
                                      vertical: 4),
                                  decoration: BoxDecoration(
                                    color: _severityColor(incident.severity)
                                        .withOpacity(0.2),
                                    borderRadius: BorderRadius.circular(4),
                                  ),
                                  child: Text(
                                    incident.severity.label,
                                    textAlign: TextAlign.center,
                                    style: TextStyle(
                                      color:
                                          _severityColor(incident.severity),
                                      fontSize: 12,
                                      fontWeight: FontWeight.bold,
                                    ),
                                  ),
                                ),
                                const SizedBox(width: 16),
                                // Content
                                Expanded(
                                  child: Column(
                                    crossAxisAlignment:
                                        CrossAxisAlignment.start,
                                    children: [
                                      Text(
                                        incident.title,
                                        style: const TextStyle(
                                          color: Colors.white,
                                          fontSize: 14,
                                          fontWeight: FontWeight.w500,
                                        ),
                                      ),
                                      const SizedBox(height: 4),
                                      Text(
                                        '${incident.serviceName} · ${incident.correlationRule ?? 'manual'} · ${_timeAgo(incident.detectedAt)}',
                                        style: const TextStyle(
                                          color: Color(0xFF9CA3AF),
                                          fontSize: 12,
                                        ),
                                      ),
                                    ],
                                  ),
                                ),
                                // Status
                                Container(
                                  padding: const EdgeInsets.symmetric(
                                      horizontal: 8, vertical: 4),
                                  decoration: BoxDecoration(
                                    color: _statusColor(incident.status)
                                        .withOpacity(0.15),
                                    borderRadius: BorderRadius.circular(12),
                                  ),
                                  child: Text(
                                    incident.status.value,
                                    style: TextStyle(
                                      color:
                                          _statusColor(incident.status),
                                      fontSize: 11,
                                      fontWeight: FontWeight.w600,
                                    ),
                                  ),
                                ),
                                // Auto-remediated badge
                                if (incident.autoRemediated) ...[
                                  const SizedBox(width: 8),
                                  Container(
                                    padding: const EdgeInsets.symmetric(
                                        horizontal: 6, vertical: 4),
                                    decoration: BoxDecoration(
                                      color: const Color(0xFF1E3A5F),
                                      borderRadius:
                                          BorderRadius.circular(4),
                                    ),
                                    child: const Text(
                                      'AUTO',
                                      style: TextStyle(
                                        color: Color(0xFF60A5FA),
                                        fontSize: 10,
                                        fontWeight: FontWeight.bold,
                                      ),
                                    ),
                                  ),
                                ],
                                // Resolve button
                                if (incident.status ==
                                        IncidentStatus.open ||
                                    incident.status ==
                                        IncidentStatus.investigating) ...[
                                  const SizedBox(width: 12),
                                  TextButton(
                                    onPressed: () =>
                                        provider.resolveIncident(
                                            incident.id),
                                    style: TextButton.styleFrom(
                                      backgroundColor:
                                          const Color(0xFF064E3B),
                                      padding: const EdgeInsets.symmetric(
                                          horizontal: 16, vertical: 8),
                                      shape: RoundedRectangleBorder(
                                        borderRadius:
                                            BorderRadius.circular(6),
                                      ),
                                    ),
                                    child: const Text(
                                      'Resolve',
                                      style: TextStyle(
                                        color: Color(0xFF10B981),
                                        fontSize: 12,
                                      ),
                                    ),
                                  ),
                                ],
                              ],
                            ),
                          );
                        },
                      ),
              ),
            ],
          ),
        );
      },
    );
  }
}
