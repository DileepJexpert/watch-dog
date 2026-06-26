import 'package:flutter/material.dart';
import '../models/models.dart';

class RemediationLogWidget extends StatelessWidget {
  final List<RemediationLog> logs;

  const RemediationLogWidget({super.key, required this.logs});

  Color _outcomeColor(String outcome) {
    switch (outcome) {
      case 'SUCCESS':
        return const Color(0xFF34D399);
      case 'FAILED':
        return const Color(0xFFF87171);
      case 'SKIPPED':
        return const Color(0xFFFBBF24);
      case 'DRY_RUN':
        return const Color(0xFF60A5FA);
      default:
        return const Color(0xFF9CA3AF);
    }
  }

  IconData _outcomeIcon(String outcome) {
    switch (outcome) {
      case 'SUCCESS':
        return Icons.check_circle_outline;
      case 'FAILED':
        return Icons.cancel_outlined;
      case 'SKIPPED':
        return Icons.block_outlined;
      case 'DRY_RUN':
        return Icons.science_outlined;
      default:
        return Icons.help_outline;
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
          const Text(
            'Remediation Log',
            style: TextStyle(
              color: Colors.white,
              fontSize: 18,
              fontWeight: FontWeight.w600,
            ),
          ),
          const SizedBox(height: 12),
          if (logs.isEmpty)
            const Center(
              child: Padding(
                padding: EdgeInsets.all(32),
                child: Text(
                  'No auto-remediation actions taken yet.',
                  style: TextStyle(color: Color(0xFF6B7280)),
                ),
              ),
            )
          else
            ConstrainedBox(
              constraints: const BoxConstraints(maxHeight: 320),
              child: ListView.separated(
                shrinkWrap: true,
                itemCount: logs.length,
                separatorBuilder: (_, __) => const Divider(
                  color: Color(0xFF1F2937),
                  height: 1,
                ),
                itemBuilder: (context, index) {
                  final log = logs[index];
                  final color = _outcomeColor(log.outcome);
                  return InkWell(
                    onTap: () => _showRemediationDetail(context, log, color),
                    borderRadius: BorderRadius.circular(6),
                    child: Padding(
                      padding:
                          const EdgeInsets.symmetric(vertical: 8, horizontal: 4),
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Icon(
                            _outcomeIcon(log.outcome),
                            color: color,
                            size: 20,
                          ),
                          const SizedBox(width: 12),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Row(
                                  mainAxisAlignment:
                                      MainAxisAlignment.spaceBetween,
                                  children: [
                                    Flexible(
                                      child: Text(
                                        log.actionType.replaceAll('_', ' '),
                                        style: const TextStyle(
                                          color: Colors.white,
                                          fontSize: 13,
                                          fontWeight: FontWeight.w500,
                                        ),
                                      ),
                                    ),
                                    Row(
                                      children: [
                                        Text(
                                          log.outcome,
                                          style: TextStyle(
                                            color: color,
                                            fontSize: 11,
                                            fontWeight: FontWeight.bold,
                                          ),
                                        ),
                                        const SizedBox(width: 6),
                                        Icon(Icons.chevron_right,
                                            size: 16,
                                            color: color.withOpacity(0.6)),
                                      ],
                                    ),
                                  ],
                                ),
                                const SizedBox(height: 2),
                                Text(
                                  'Service: ${log.serviceName} · By: ${log.executedBy}'
                                  '${log.failureReason != null ? ' · ${log.failureReason}' : ''}',
                                  style: const TextStyle(
                                    color: Color(0xFF9CA3AF),
                                    fontSize: 11,
                                  ),
                                ),
                                Text(
                                  _timeAgo(log.executedAt),
                                  style: const TextStyle(
                                    color: Color(0xFF4B5563),
                                    fontSize: 10,
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ],
                      ),
                    ),
                  );
                },
              ),
            ),
        ],
      ),
    );
  }

  void _showRemediationDetail(
      BuildContext context, RemediationLog log, Color outcomeColor) {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: const Color(0xFF111827),
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
      ),
      builder: (_) => _RemediationDetailSheet(log: log, outcomeColor: outcomeColor),
    );
  }
}

/// Bottom-sheet detail view for a remediation log entry. Explains what the
/// action would have done, the parameters that would have been passed, and —
/// for DRY_RUN entries — why nothing actually executed.
class _RemediationDetailSheet extends StatelessWidget {
  final RemediationLog log;
  final Color outcomeColor;

  const _RemediationDetailSheet({required this.log, required this.outcomeColor});

  static const _actionDescriptions = <String, String>{
    'POD_RESTART':
        'Delete the pod for this service so its ReplicaSet brings up a fresh '
            'instance. Equivalent to: kubectl delete pod <pod-name>',
    'POD_SCALE_HORIZONTAL':
        'Increase the replica count on the Deployment to absorb load. '
            'Equivalent to: kubectl scale deployment/<svc> --replicas=N',
    'DEPLOYMENT_ROLLBACK':
        'Roll the Deployment back to the previous ReplicaSet revision. '
            'Equivalent to: kubectl rollout undo deployment/<svc>',
    'CIRCUIT_BREAKER_OPEN':
        'Mark the service as failing in Redis so upstream callers stop hitting '
            'it for a cooldown period. No K8s mutation.',
    'CACHE_FLUSH':
        'Invalidate the caching layer entries for this service so subsequent '
            'requests rebuild from source.',
  };

  static const _outcomeMeanings = <String, String>{
    'DRY_RUN':
        'sentinel.remediation.dry-run is true in this profile, so the action '
            'was logged for audit but never sent to Kubernetes. Flip the flag '
            '(or set REMEDIATION_DRY_RUN=false at the env var level) and point '
            'KUBECONFIG at a real cluster to make this fire for real.',
    'SUCCESS':
        'Action executed and Kubernetes acknowledged it. Pod was restarted / '
            'scaled / rolled back as described.',
    'FAILED':
        'Kubernetes rejected the action — usually a permissions error, the '
            'target resource no longer exists, or a guardrail tripped post-check.',
    'SKIPPED':
        'GuardrailService refused the action: typically in cooldown after a '
            'previous attempt, or the max-restarts-per-hour cap was hit.',
  };

  String _isoOrDash(DateTime? d) => d == null
      ? '—'
      : d.toIso8601String().replaceFirst('T', ' ').split('.').first;

  @override
  Widget build(BuildContext context) {
    final description = _actionDescriptions[log.actionType] ??
        'Action type ${log.actionType} — no detailed description registered.';
    final meaning = _outcomeMeanings[log.outcome] ?? '';

    return DraggableScrollableSheet(
      initialChildSize: 0.6,
      minChildSize: 0.4,
      maxChildSize: 0.95,
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

            // Header — action type + outcome pill
            Row(
              children: [
                Icon(Icons.auto_fix_high,
                    color: outcomeColor, size: 22),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    log.actionType.replaceAll('_', ' '),
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 18,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
                Container(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
                  decoration: BoxDecoration(
                    color: outcomeColor.withOpacity(0.15),
                    border: Border.all(color: outcomeColor.withOpacity(0.5)),
                    borderRadius: BorderRadius.circular(4),
                  ),
                  child: Text(
                    log.outcome,
                    style: TextStyle(
                      color: outcomeColor,
                      fontSize: 11,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ),
              ],
            ),

            const SizedBox(height: 16),
            const Divider(color: Color(0xFF1F2937), height: 1),
            const SizedBox(height: 12),

            // What would have happened
            _sectionLabel('What this action does'),
            const SizedBox(height: 4),
            Text(
              description,
              style: const TextStyle(
                color: Color(0xFFD1D5DB),
                fontSize: 13,
                height: 1.4,
              ),
            ),

            const SizedBox(height: 16),

            // Why outcome
            if (meaning.isNotEmpty) ...[
              _sectionLabel('Why ${log.outcome}'),
              const SizedBox(height: 4),
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: outcomeColor.withOpacity(0.06),
                  border: Border.all(color: outcomeColor.withOpacity(0.3)),
                  borderRadius: BorderRadius.circular(6),
                ),
                child: Text(
                  meaning,
                  style: TextStyle(
                    color: outcomeColor.withOpacity(0.95),
                    fontSize: 12,
                    height: 1.4,
                  ),
                ),
              ),
              const SizedBox(height: 16),
            ],

            // Metadata
            _sectionLabel('Details'),
            const SizedBox(height: 4),
            _kv('Service', log.serviceName),
            _kv('Triggered by', log.executedBy),
            _kv('Executed at', _isoOrDash(log.executedAt)),
            if (log.incidentId != null)
              _kv('Incident ID', log.incidentId!, mono: true),
            _kv('Remediation ID', log.id, mono: true),
            if (log.failureReason != null && log.failureReason!.isNotEmpty)
              _kv('Failure reason', log.failureReason!),

            // Parameters
            if (log.parameters.isNotEmpty) ...[
              const SizedBox(height: 16),
              _sectionLabel('Parameters that would have been passed'),
              const SizedBox(height: 4),
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: const Color(0xFF0B1220),
                  border: Border.all(color: const Color(0xFF1F2937)),
                  borderRadius: BorderRadius.circular(6),
                ),
                child: SelectableText(
                  _formatParams(log.parameters),
                  style: const TextStyle(
                    color: Color(0xFFE5E7EB),
                    fontSize: 12,
                    fontFamily: 'monospace',
                    height: 1.5,
                  ),
                ),
              ),
            ],

            const SizedBox(height: 16),

            // Footer hint for DRY_RUN cards
            if (log.outcome == 'DRY_RUN')
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: const Color(0xFF111827),
                  border: Border.all(color: const Color(0xFF1F2937)),
                  borderRadius: BorderRadius.circular(6),
                ),
                child: const Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Icon(Icons.info_outline,
                        size: 16, color: Color(0xFF60A5FA)),
                    SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        'To make this action fire for real: point KUBECONFIG '
                        'at a live cluster (minikube / kind / EKS), then set '
                        'REMEDIATION_DRY_RUN=false in the IntelliJ run config. '
                        'The same audit record will then be written with '
                        'outcome=SUCCESS or FAILED.',
                        style: TextStyle(
                          color: Color(0xFF9CA3AF),
                          fontSize: 11,
                          height: 1.5,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
          ],
        ),
      ),
    );
  }

  String _formatParams(Map<String, dynamic> params) {
    if (params.isEmpty) return '{}';
    final lines = <String>['{'];
    final entries = params.entries.toList();
    for (var i = 0; i < entries.length; i++) {
      final e = entries[i];
      final v = e.value is String ? '"${e.value}"' : '${e.value}';
      final trailing = i == entries.length - 1 ? '' : ',';
      lines.add('  "${e.key}": $v$trailing');
    }
    lines.add('}');
    return lines.join('\n');
  }

  Widget _sectionLabel(String text) {
    return Text(
      text.toUpperCase(),
      style: const TextStyle(
        color: Color(0xFF9CA3AF),
        fontSize: 10,
        fontWeight: FontWeight.w700,
        letterSpacing: 1.0,
      ),
    );
  }

  Widget _kv(String k, String v, {bool mono = false}) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
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
