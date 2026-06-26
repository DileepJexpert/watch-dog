import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../models/models.dart';
import '../services/sentinel_provider.dart';

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

  Future<void> _confirmResolve(BuildContext context) async {
    final yes = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: const Color(0xFF111827),
        title: const Text('Resolve incident?',
            style: TextStyle(color: Colors.white)),
        content: Text(
          'Mark "${incident.title}" on ${incident.serviceName} as resolved?\n\n'
          'This stops alerts for this incident and clears it from the active '
          'list. You can re-open it from the Incidents tab.',
          style: const TextStyle(color: Color(0xFF9CA3AF)),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: const Text('Cancel',
                style: TextStyle(color: Color(0xFF6B7280))),
          ),
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(true),
            child: const Text('Resolve',
                style: TextStyle(color: Color(0xFF10B981))),
          ),
        ],
      ),
    );
    if (yes == true && onResolve != null) {
      onResolve!(incident.id);
    }
  }

  void _openDetail(BuildContext context) {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: const Color(0xFF111827),
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
      ),
      builder: (ctx) => _IncidentDetailSheet(
        incident: incident,
        severityColor: severityColor,
        severityBg: severityBg,
        timeAgo: timeAgo,
        onResolve: onResolve == null
            ? null
            : () async {
                Navigator.of(ctx).pop();
                await _confirmResolve(context);
              },
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final canResolve = onResolve != null &&
        incident.status != IncidentStatus.resolved &&
        incident.status != IncidentStatus.autoRemediated;

    return InkWell(
      onTap: () => _openDetail(context),
      borderRadius: BorderRadius.circular(6),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 8, horizontal: 4),
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
            const Icon(Icons.chevron_right,
                size: 18, color: Color(0xFF6B7280)),
            if (canResolve) ...[
              const SizedBox(width: 4),
              SizedBox(
                height: 28,
                child: TextButton(
                  onPressed: () => _confirmResolve(context),
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
          ],
        ),
      ),
    );
  }
}

/// Bottom-sheet detail view for a single incident — full metadata, correlated
/// signal IDs, and the two action buttons (Ask Copilot, Resolve).
class _IncidentDetailSheet extends StatelessWidget {
  final Incident incident;
  final Color severityColor;
  final Color severityBg;
  final String timeAgo;
  final VoidCallback? onResolve;

  const _IncidentDetailSheet({
    required this.incident,
    required this.severityColor,
    required this.severityBg,
    required this.timeAgo,
    this.onResolve,
  });

  String _isoOrDash(DateTime? d) =>
      d == null ? '—' : d.toIso8601String().replaceFirst('T', ' ').split('.').first;

  void _askCopilot(BuildContext context) {
    // Build a question the agent can act on: identifies the incident, hints
    // which tools to use. Tightened prompt + good model = clean RCA.
    final question = StringBuffer()
      ..writeln('Explain incident ${incident.id} on ${incident.serviceName}.')
      ..writeln('Title: ${incident.title}')
      ..writeln('Severity: ${incident.severity.label}')
      ..writeln('Correlation rule: ${incident.correlationRule ?? "(none)"}')
      ..writeln('Detected: ${_isoOrDash(incident.detectedAt)}')
      ..writeln()
      ..writeln('Use query_database to fetch the incident row from the '
          '`sentinel` target, search_logs and get_traces filtered by the '
          'service, and search_knowledge for matching runbooks. '
          'Cite evidence and give recommended actions.');

    Navigator.of(context).pop(); // close the sheet
    context.read<SentinelProvider>().switchToCopilot(
          withQuestion: question.toString().trim(),
        );
  }

  @override
  Widget build(BuildContext context) {
    return DraggableScrollableSheet(
      initialChildSize: 0.55,
      minChildSize: 0.35,
      maxChildSize: 0.92,
      expand: false,
      builder: (_, scrollCtl) => SingleChildScrollView(
        controller: scrollCtl,
        padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            // Drag handle
            Center(
              child: Container(
                width: 36,
                height: 4,
                margin: const EdgeInsets.only(bottom: 16),
                decoration: BoxDecoration(
                  color: const Color(0xFF374151),
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
            ),
            // Severity badge + title
            Row(
              children: [
                Container(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
                  decoration: BoxDecoration(
                    color: severityBg,
                    borderRadius: BorderRadius.circular(4),
                  ),
                  child: Text(
                    incident.severity.label,
                    style: TextStyle(
                      color: severityColor,
                      fontSize: 12,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    incident.title,
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 16,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 16),
            const Divider(color: Color(0xFF1F2937), height: 1),
            const SizedBox(height: 12),

            // Metadata grid
            _kv('Service', incident.serviceName),
            _kv('Correlation rule', incident.correlationRule ?? '(none)'),
            _kv('Status', incident.status.label),
            _kv('Detected', _isoOrDash(incident.detectedAt)),
            if (incident.resolvedAt != null)
              _kv('Resolved', _isoOrDash(incident.resolvedAt)),
            _kv('Auto-remediated', incident.autoRemediated ? 'Yes' : 'No'),
            _kv('Incident ID', incident.id, mono: true),

            const SizedBox(height: 12),
            const Divider(color: Color(0xFF1F2937), height: 1),
            const SizedBox(height: 12),

            // Correlated signals
            const Text(
              'Correlated signals',
              style: TextStyle(
                color: Color(0xFF9CA3AF),
                fontSize: 11,
                fontWeight: FontWeight.w600,
                letterSpacing: 0.5,
              ),
            ),
            const SizedBox(height: 8),
            if (incident.correlatedSignalIds.isEmpty)
              const Text(
                'No correlated signal IDs recorded.',
                style: TextStyle(color: Color(0xFF6B7280), fontSize: 12),
              )
            else
              Wrap(
                spacing: 6,
                runSpacing: 6,
                children: incident.correlatedSignalIds
                    .take(15)
                    .map((id) => Container(
                          padding: const EdgeInsets.symmetric(
                              horizontal: 8, vertical: 4),
                          decoration: BoxDecoration(
                            color: const Color(0xFF1F2937),
                            borderRadius: BorderRadius.circular(4),
                          ),
                          child: Text(
                            id,
                            style: const TextStyle(
                              color: Color(0xFF9CA3AF),
                              fontFamily: 'monospace',
                              fontSize: 10,
                            ),
                          ),
                        ))
                    .toList(),
              ),

            const SizedBox(height: 20),

            // Actions
            Row(
              children: [
                Expanded(
                  child: OutlinedButton.icon(
                    icon: const Icon(Icons.smart_toy, size: 16),
                    label: const Text('Ask Copilot about this'),
                    style: OutlinedButton.styleFrom(
                      foregroundColor: const Color(0xFF60A5FA),
                      side: const BorderSide(color: Color(0xFF1E3A5F)),
                      padding: const EdgeInsets.symmetric(vertical: 14),
                    ),
                    onPressed: () => _askCopilot(context),
                  ),
                ),
                if (onResolve != null) ...[
                  const SizedBox(width: 12),
                  Expanded(
                    child: OutlinedButton.icon(
                      icon: const Icon(Icons.check_circle_outline, size: 16),
                      label: const Text('Resolve'),
                      style: OutlinedButton.styleFrom(
                        foregroundColor: const Color(0xFF10B981),
                        side: const BorderSide(color: Color(0xFF064E3B)),
                        padding: const EdgeInsets.symmetric(vertical: 14),
                      ),
                      onPressed: onResolve,
                    ),
                  ),
                ],
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _kv(String k, String v, {bool mono = false}) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 130,
            child: Text(
              k,
              style: const TextStyle(
                color: Color(0xFF6B7280),
                fontSize: 12,
              ),
            ),
          ),
          Expanded(
            child: SelectableText(
              v,
              style: TextStyle(
                color: Colors.white,
                fontSize: 12,
                fontFamily: mono ? 'monospace' : null,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
