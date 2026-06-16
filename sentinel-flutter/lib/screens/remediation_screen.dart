import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../services/sentinel_provider.dart';
import '../widgets/remediation_log_widget.dart';

class RemediationScreen extends StatelessWidget {
  const RemediationScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Consumer<SentinelProvider>(
      builder: (context, provider, _) {
        return SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'Auto-Remediation',
                style: TextStyle(
                  color: Colors.white,
                  fontSize: 22,
                  fontWeight: FontWeight.bold,
                ),
              ),
              const SizedBox(height: 8),
              const Text(
                'Automated actions taken by SENTINEL to resolve incidents.',
                style: TextStyle(
                  color: Color(0xFF9CA3AF),
                  fontSize: 14,
                ),
              ),
              const SizedBox(height: 16),
              // Summary cards
              Row(
                children: [
                  _SummaryCard(
                    label: 'Total Actions',
                    value: '${provider.remediationLogs.length}',
                    icon: Icons.auto_fix_high,
                    color: const Color(0xFF60A5FA),
                  ),
                  const SizedBox(width: 12),
                  _SummaryCard(
                    label: 'Successful',
                    value:
                        '${provider.remediationLogs.where((l) => l.outcome == 'SUCCESS').length}',
                    icon: Icons.check_circle_outline,
                    color: const Color(0xFF10B981),
                  ),
                  const SizedBox(width: 12),
                  _SummaryCard(
                    label: 'Failed',
                    value:
                        '${provider.remediationLogs.where((l) => l.outcome == 'FAILED').length}',
                    icon: Icons.error_outline,
                    color: const Color(0xFFEF4444),
                  ),
                  const SizedBox(width: 12),
                  _SummaryCard(
                    label: 'Dry Run',
                    value:
                        '${provider.remediationLogs.where((l) => l.outcome == 'DRY_RUN').length}',
                    icon: Icons.science_outlined,
                    color: const Color(0xFFFBBF24),
                  ),
                ],
              ),
              const SizedBox(height: 16),
              RemediationLogWidget(logs: provider.remediationLogs),
            ],
          ),
        );
      },
    );
  }
}

class _SummaryCard extends StatelessWidget {
  final String label;
  final String value;
  final IconData icon;
  final Color color;

  const _SummaryCard({
    required this.label,
    required this.value,
    required this.icon,
    required this.color,
  });

  @override
  Widget build(BuildContext context) {
    return Expanded(
      child: Container(
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: const Color(0xFF111827),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: const Color(0xFF1F2937)),
        ),
        child: Column(
          children: [
            Icon(icon, color: color, size: 24),
            const SizedBox(height: 8),
            Text(
              value,
              style: TextStyle(
                color: color,
                fontSize: 24,
                fontWeight: FontWeight.bold,
              ),
            ),
            const SizedBox(height: 4),
            Text(
              label,
              style: const TextStyle(
                color: Color(0xFF9CA3AF),
                fontSize: 11,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
