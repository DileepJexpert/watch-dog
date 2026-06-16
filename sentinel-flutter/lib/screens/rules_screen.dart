import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../models/models.dart';
import '../services/sentinel_provider.dart';
import '../widgets/rule_builder.dart';

class RulesScreen extends StatefulWidget {
  const RulesScreen({super.key});

  @override
  State<RulesScreen> createState() => _RulesScreenState();
}

class _RulesScreenState extends State<RulesScreen> {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      context.read<SentinelProvider>().fetchRules();
    });
  }

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
                'Alert Rules',
                style: TextStyle(
                  color: Colors.white,
                  fontSize: 22,
                  fontWeight: FontWeight.bold,
                ),
              ),
              const SizedBox(height: 16),
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  // Rule list
                  Expanded(
                    flex: 3,
                    child: Container(
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
                                'Existing Rules',
                                style: TextStyle(
                                  color: Colors.white,
                                  fontSize: 16,
                                  fontWeight: FontWeight.w600,
                                ),
                              ),
                              Text(
                                '${provider.rules.length} rules',
                                style: const TextStyle(
                                  color: Color(0xFF9CA3AF),
                                  fontSize: 12,
                                ),
                              ),
                            ],
                          ),
                          const SizedBox(height: 12),
                          if (provider.rules.isEmpty)
                            const Padding(
                              padding: EdgeInsets.all(32),
                              child: Center(
                                child: Text(
                                  'No custom rules configured.',
                                  style: TextStyle(color: Color(0xFF6B7280)),
                                ),
                              ),
                            )
                          else
                            ...provider.rules.map((rule) => _RuleCard(
                                  rule: rule,
                                  severityColor: _severityColor(rule.severity),
                                  onDelete: () =>
                                      provider.deleteRule(rule.id),
                                )),
                        ],
                      ),
                    ),
                  ),
                  const SizedBox(width: 16),
                  // Rule builder
                  const Expanded(
                    flex: 2,
                    child: RuleBuilder(),
                  ),
                ],
              ),
            ],
          ),
        );
      },
    );
  }
}

class _RuleCard extends StatelessWidget {
  final AlertRule rule;
  final Color severityColor;
  final VoidCallback onDelete;

  const _RuleCard({
    required this.rule,
    required this.severityColor,
    required this.onDelete,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: const Color(0xFF1F2937),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        children: [
          Container(
            width: 4,
            height: 40,
            decoration: BoxDecoration(
              color: severityColor,
              borderRadius: BorderRadius.circular(2),
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
                        rule.name,
                        style: const TextStyle(
                          color: Colors.white,
                          fontSize: 14,
                          fontWeight: FontWeight.w500,
                        ),
                      ),
                    ),
                    const SizedBox(width: 8),
                    Container(
                      padding: const EdgeInsets.symmetric(
                          horizontal: 6, vertical: 2),
                      decoration: BoxDecoration(
                        color: rule.enabled
                            ? const Color(0xFF064E3B)
                            : const Color(0xFF374151),
                        borderRadius: BorderRadius.circular(4),
                      ),
                      child: Text(
                        rule.enabled ? 'ACTIVE' : 'DISABLED',
                        style: TextStyle(
                          color: rule.enabled
                              ? const Color(0xFF10B981)
                              : const Color(0xFF6B7280),
                          fontSize: 9,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 4),
                Text(
                  '${rule.severity.value} · ${rule.serviceName ?? 'all services'} · by ${rule.createdBy}',
                  style: const TextStyle(
                    color: Color(0xFF9CA3AF),
                    fontSize: 11,
                  ),
                ),
              ],
            ),
          ),
          IconButton(
            icon: const Icon(Icons.delete_outline,
                color: Color(0xFF6B7280), size: 18),
            onPressed: onDelete,
          ),
        ],
      ),
    );
  }
}
