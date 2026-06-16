import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import '../models/models.dart';

class ActiveIncidents extends StatelessWidget {
  final List<Incident> incidents;
  final void Function(String id)? onResolve;

  const ActiveIncidents({
    super.key,
    required this.incidents,
    this.onResolve,
  });

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

  Color _severityBg(Severity severity) {
    switch (severity) {
      case Severity.p1Critical:
        return const Color(0xFF7F1D1D);
      case Severity.p2High:
        return const Color(0xFF7C2D12);
      case Severity.p3Medium:
        return const Color(0xFF78350F);
      case Severity.p4Info:
        return const Color(0xFF1E3A5F);
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
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: const Color(0xFF111827),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              const Text(
                'Active Incidents',
                style: TextStyle(
                  color: Colors.white,
                  fontSize: 18,
                  fontWeight: FontWeight.w600,
                ),
              ),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                decoration: BoxDecoration(
                  color: incidents.isEmpty
                      ? const Color(0xFF064E3B)
                      : const Color(0xFF7F1D1D),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Text(
                  '${incidents.length}',
                  style: TextStyle(
                    color: incidents.isEmpty
                        ? const Color(0xFF10B981)
                        : const Color(0xFFEF4444),
                    fontSize: 12,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          if (incidents.isEmpty)
            const Center(
              child: Padding(
                padding: EdgeInsets.all(32),
                child: Text(
                  'No active incidents. All systems operational.',
                  style: TextStyle(color: Color(0xFF6B7280)),
                ),
              ),
            )
          else
            ConstrainedBox(
              constraints: const BoxConstraints(maxHeight: 320),
              child: ListView.separated(
                shrinkWrap: true,
                itemCount: incidents.length,
                separatorBuilder: (_, __) => const Divider(
                  color: Color(0xFF1F2937),
                  height: 1,
                ),
                itemBuilder: (context, index) {
                  final incident = incidents[index];
                  return _IncidentRow(
                    incident: incident,
                    severityColor: _severityColor(incident.severity),
                    severityBg: _severityBg(incident.severity),
                    timeAgo: _timeAgo(incident.detectedAt),
                    onResolve: onResolve,
                  );
                },
              ),
            ),
        ],
      ),
    );
  }
}

class _IncidentRow extends StatelessWidget {
  final Incident incident;
  final Color severityColor;
  final Color severityBg;
  final String timeAgo;
  final void Function(String id)? onResolve;

  const _IncidentRow({
    required this.incident,
    required this.severityColor,
    required this.severityBg,
    required this.timeAgo,
    this.onResolve,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Row(
        children: [
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
            decoration: BoxDecoration(
              color: severityBg,
              borderRadius: BorderRadius.circular(4),
            ),
            child: Text(
              incident.severity.label,
              style: TextStyle(
                color: severityColor,
                fontSize: 11,
                fontWeight: FontWeight.bold,
              ),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Flexible(
                      child: Text(
                        incident.title,
                        style: const TextStyle(
                          color: Colors.white,
                          fontSize: 13,
                          fontWeight: FontWeight.w500,
                        ),
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                    if (incident.autoRemediated) ...[
                      const SizedBox(width: 8),
                      Container(
                        padding: const EdgeInsets.symmetric(
                            horizontal: 6, vertical: 2),
                        decoration: BoxDecoration(
                          color: const Color(0xFF1E3A5F),
                          borderRadius: BorderRadius.circular(4),
                        ),
                        child: const Text(
                          'AUTO',
                          style: TextStyle(
                            color: Color(0xFF60A5FA),
                            fontSize: 9,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                      ),
                    ],
                  ],
                ),
                const SizedBox(height: 2),
                Text(
                  '${incident.serviceName} · $timeAgo',
                  style: const TextStyle(
                    color: Color(0xFF9CA3AF),
                    fontSize: 11,
                  ),
                ),
              ],
            ),
          ),
          if (onResolve != null &&
              incident.status != IncidentStatus.resolved &&
              incident.status != IncidentStatus.autoRemediated)
            SizedBox(
              height: 28,
              child: TextButton(
                onPressed: () => onResolve!(incident.id),
                style: TextButton.styleFrom(
                  padding: const EdgeInsets.symmetric(horizontal: 12),
                  backgroundColor: const Color(0xFF1F2937),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(6),
                  ),
                ),
                child: const Text(
                  'Resolve',
                  style: TextStyle(
                    color: Color(0xFF10B981),
                    fontSize: 11,
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}
