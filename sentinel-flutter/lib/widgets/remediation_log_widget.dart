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
                  return Padding(
                    padding: const EdgeInsets.symmetric(vertical: 8),
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
                                  Text(
                                    log.outcome,
                                    style: TextStyle(
                                      color: color,
                                      fontSize: 11,
                                      fontWeight: FontWeight.bold,
                                    ),
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
                  );
                },
              ),
            ),
        ],
      ),
    );
  }
}
