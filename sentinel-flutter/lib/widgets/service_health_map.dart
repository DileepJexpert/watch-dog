import 'package:flutter/material.dart';
import '../models/models.dart';

class ServiceHealthMap extends StatelessWidget {
  final List<ServiceHealth> services;

  const ServiceHealthMap({super.key, required this.services});

  Color _statusColor(ServiceStatus status) {
    switch (status) {
      case ServiceStatus.green:
        return const Color(0xFF10B981);
      case ServiceStatus.yellow:
        return const Color(0xFFF59E0B);
      case ServiceStatus.red:
        return const Color(0xFFEF4444);
    }
  }

  Color _statusBgColor(ServiceStatus status) {
    switch (status) {
      case ServiceStatus.green:
        return const Color(0xFF064E3B);
      case ServiceStatus.yellow:
        return const Color(0xFF78350F);
      case ServiceStatus.red:
        return const Color(0xFF7F1D1D);
    }
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
            'Service Health',
            style: TextStyle(
              color: Colors.white,
              fontSize: 18,
              fontWeight: FontWeight.w600,
            ),
          ),
          const SizedBox(height: 16),
          if (services.isEmpty)
            const Center(
              child: Padding(
                padding: EdgeInsets.all(32),
                child: Text(
                  'No services registered.',
                  style: TextStyle(color: Color(0xFF6B7280)),
                ),
              ),
            )
          else
            Wrap(
              spacing: 12,
              runSpacing: 12,
              children: services.map((svc) => _ServiceTile(
                service: svc,
                statusColor: _statusColor(svc.status),
                bgColor: _statusBgColor(svc.status),
              )).toList(),
            ),
        ],
      ),
    );
  }
}

class _ServiceTile extends StatelessWidget {
  final ServiceHealth service;
  final Color statusColor;
  final Color bgColor;

  const _ServiceTile({
    required this.service,
    required this.statusColor,
    required this.bgColor,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 180,
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: bgColor,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: statusColor.withOpacity(0.5)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                width: 10,
                height: 10,
                decoration: BoxDecoration(
                  color: statusColor,
                  shape: BoxShape.circle,
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  service.serviceName,
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 13,
                    fontWeight: FontWeight.w500,
                  ),
                  overflow: TextOverflow.ellipsis,
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Text(
            'Err: ${service.errorRate.toStringAsFixed(2)}%',
            style: TextStyle(
              color: Colors.white.withOpacity(0.7),
              fontSize: 11,
            ),
          ),
          Text(
            'P95: ${service.latencyP95.toStringAsFixed(0)}ms',
            style: TextStyle(
              color: Colors.white.withOpacity(0.7),
              fontSize: 11,
            ),
          ),
        ],
      ),
    );
  }
}
