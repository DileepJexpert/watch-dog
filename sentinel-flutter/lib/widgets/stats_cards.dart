import 'package:flutter/material.dart';
import '../models/models.dart';

class StatsCards extends StatelessWidget {
  final DashboardStats stats;
  final bool wsConnected;

  const StatsCards({
    super.key,
    required this.stats,
    required this.wsConnected,
  });

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        _StatCard(
          label: 'Open Incidents',
          value: '${stats.openIncidents}',
          color: stats.openIncidents > 0
              ? const Color(0xFFEF4444)
              : const Color(0xFF10B981),
          icon: Icons.warning_amber_rounded,
        ),
        const SizedBox(width: 12),
        _StatCard(
          label: 'Last 24h',
          value: '${stats.incidentsLast24h}',
          color: const Color(0xFFF97316),
          icon: Icons.schedule,
        ),
        const SizedBox(width: 12),
        _StatCard(
          label: 'Last 7d',
          value: '${stats.incidentsLast7d}',
          color: const Color(0xFFFBBF24),
          icon: Icons.calendar_today,
        ),
        const SizedBox(width: 12),
        _StatCard(
          label: 'Services',
          value: '${stats.serviceCount}',
          color: const Color(0xFF60A5FA),
          icon: Icons.dns_outlined,
        ),
        const SizedBox(width: 12),
        _ConnectionIndicator(connected: wsConnected),
      ],
    );
  }
}

class _StatCard extends StatelessWidget {
  final String label;
  final String value;
  final Color color;
  final IconData icon;

  const _StatCard({
    required this.label,
    required this.value,
    required this.color,
    required this.icon,
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
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(icon, color: color, size: 18),
                const SizedBox(width: 8),
                Text(
                  label,
                  style: const TextStyle(
                    color: Color(0xFF9CA3AF),
                    fontSize: 12,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              value,
              style: TextStyle(
                color: color,
                fontSize: 28,
                fontWeight: FontWeight.bold,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _ConnectionIndicator extends StatelessWidget {
  final bool connected;

  const _ConnectionIndicator({required this.connected});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: const Color(0xFF111827),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: const Color(0xFF1F2937)),
      ),
      child: Column(
        children: [
          Icon(
            connected ? Icons.wifi : Icons.wifi_off,
            color: connected
                ? const Color(0xFF10B981)
                : const Color(0xFFEF4444),
            size: 20,
          ),
          const SizedBox(height: 4),
          Text(
            connected ? 'LIVE' : 'OFF',
            style: TextStyle(
              color: connected
                  ? const Color(0xFF10B981)
                  : const Color(0xFFEF4444),
              fontSize: 10,
              fontWeight: FontWeight.bold,
            ),
          ),
        ],
      ),
    );
  }
}
