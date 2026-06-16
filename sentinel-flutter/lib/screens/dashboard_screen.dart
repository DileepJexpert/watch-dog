import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../services/sentinel_provider.dart';
import '../widgets/stats_cards.dart';
import '../widgets/service_health_map.dart';
import '../widgets/active_incidents.dart';
import '../widgets/error_rate_trend.dart';
import '../widgets/latency_heatmap.dart';
import '../widgets/remediation_log_widget.dart';

class DashboardScreen extends StatelessWidget {
  const DashboardScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Consumer<SentinelProvider>(
      builder: (context, provider, _) {
        // Generate mock latency data from services for demo
        final latencyData = provider.services.map((s) {
          return LatencyData(
            service: s.serviceName,
            p50: s.latencyP95 * 0.4,
            p95: s.latencyP95,
            p99: s.latencyP99,
          );
        }).toList();

        return SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              StatsCards(
                stats: provider.stats,
                wsConnected: provider.wsConnected,
              ),
              const SizedBox(height: 16),
              ServiceHealthMap(services: provider.services),
              const SizedBox(height: 16),
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Expanded(
                    child: ActiveIncidents(
                      incidents: provider.incidents,
                      onResolve: (id) => provider.resolveIncident(id),
                    ),
                  ),
                  const SizedBox(width: 16),
                  Expanded(
                    child: RemediationLogWidget(
                      logs: provider.remediationLogs,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 16),
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Expanded(
                    child: ErrorRateTrend(
                      serviceName: provider.services.isNotEmpty
                          ? provider.services.first.serviceName
                          : 'all',
                      data: const [],
                    ),
                  ),
                  const SizedBox(width: 16),
                  Expanded(
                    child: LatencyHeatmap(data: latencyData),
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
