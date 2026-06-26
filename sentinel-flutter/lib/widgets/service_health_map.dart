import 'package:flutter/material.dart';
import '../models/models.dart';

/// Compact, scalable service-health panel.
///
/// Designed to stay readable from 1 service up to 60+ (e.g. 20 apps × 3 pods):
///   - A summary strip up top: down / degraded / healthy counts
///   - A search box to jump to a service by name
///   - A responsive dense grid of small status chips, sorted RED -> YELLOW ->
///     GREEN so anything broken is always at the top-left
///   - Healthy services collapse behind a "show N healthy" toggle so a wall
///     of green never buries the few red ones
class ServiceHealthMap extends StatefulWidget {
  final List<ServiceHealth> services;

  const ServiceHealthMap({super.key, required this.services});

  @override
  State<ServiceHealthMap> createState() => _ServiceHealthMapState();
}

class _ServiceHealthMapState extends State<ServiceHealthMap> {
  String _filter = '';
  bool _showHealthy = false;

  Color _statusColor(ServiceStatus s) => switch (s) {
        ServiceStatus.green => const Color(0xFF10B981),
        ServiceStatus.yellow => const Color(0xFFF59E0B),
        ServiceStatus.red => const Color(0xFFEF4444),
      };

  int _rank(ServiceStatus s) => switch (s) {
        ServiceStatus.red => 0,
        ServiceStatus.yellow => 1,
        ServiceStatus.green => 2,
      };

  @override
  Widget build(BuildContext context) {
    final all = widget.services;
    final down = all.where((s) => s.status == ServiceStatus.red).length;
    final degraded = all.where((s) => s.status == ServiceStatus.yellow).length;
    final healthy = all.where((s) => s.status == ServiceStatus.green).length;

    // Filter + sort (severity, then name).
    var visible = all.where((s) {
      if (_filter.isEmpty) return true;
      return s.serviceName.toLowerCase().contains(_filter.toLowerCase());
    }).toList()
      ..sort((a, b) {
        final r = _rank(a.status).compareTo(_rank(b.status));
        return r != 0 ? r : a.serviceName.compareTo(b.serviceName);
      });

    // Collapse healthy unless toggled (or actively searched).
    final hiddenHealthy = <ServiceHealth>[];
    if (!_showHealthy && _filter.isEmpty) {
      visible = visible.where((s) {
        if (s.status == ServiceStatus.green) {
          hiddenHealthy.add(s);
          return false;
        }
        return true;
      }).toList();
    }

    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: const Color(0xFF111827),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // ── Header row: title + summary pills + search ──────────────────
          Row(
            children: [
              const Text(
                'Service Health',
                style: TextStyle(
                  color: Colors.white,
                  fontSize: 18,
                  fontWeight: FontWeight.w600,
                ),
              ),
              const SizedBox(width: 16),
              if (all.isNotEmpty) ...[
                _SummaryPill(
                    count: down,
                    label: 'down',
                    color: const Color(0xFFEF4444)),
                const SizedBox(width: 6),
                _SummaryPill(
                    count: degraded,
                    label: 'degraded',
                    color: const Color(0xFFF59E0B)),
                const SizedBox(width: 6),
                _SummaryPill(
                    count: healthy,
                    label: 'healthy',
                    color: const Color(0xFF10B981)),
              ],
              const Spacer(),
              if (all.length > 8)
                SizedBox(
                  width: 180,
                  height: 32,
                  child: TextField(
                    onChanged: (v) => setState(() => _filter = v),
                    style: const TextStyle(color: Colors.white, fontSize: 12),
                    decoration: InputDecoration(
                      hintText: 'Filter services…',
                      hintStyle: const TextStyle(
                          color: Color(0xFF6B7280), fontSize: 12),
                      prefixIcon: const Icon(Icons.search,
                          size: 16, color: Color(0xFF6B7280)),
                      prefixIconConstraints:
                          const BoxConstraints(minWidth: 32, minHeight: 32),
                      isDense: true,
                      contentPadding:
                          const EdgeInsets.symmetric(vertical: 8, horizontal: 4),
                      filled: true,
                      fillColor: const Color(0xFF0B1220),
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(6),
                        borderSide:
                            const BorderSide(color: Color(0xFF1F2937)),
                      ),
                      enabledBorder: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(6),
                        borderSide:
                            const BorderSide(color: Color(0xFF1F2937)),
                      ),
                      focusedBorder: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(6),
                        borderSide:
                            const BorderSide(color: Color(0xFF3B82F6)),
                      ),
                    ),
                  ),
                ),
            ],
          ),
          const SizedBox(height: 14),

          // ── Body ─────────────────────────────────────────────────────────
          if (all.isEmpty)
            const Center(
              child: Padding(
                padding: EdgeInsets.all(32),
                child: Text(
                  'No services registered.',
                  style: TextStyle(color: Color(0xFF6B7280)),
                ),
              ),
            )
          else if (visible.isEmpty && hiddenHealthy.isNotEmpty)
            // All services are healthy and collapsed.
            _AllHealthyState(
              count: hiddenHealthy.length,
              onShow: () => setState(() => _showHealthy = true),
            )
          else if (visible.isEmpty)
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 24),
              child: Text('No services match the filter.',
                  style: TextStyle(color: Color(0xFF6B7280))),
            )
          else
            LayoutBuilder(
              builder: (context, constraints) {
                // Responsive column count — chips are ~210px wide.
                const chipWidth = 210.0;
                final cols =
                    (constraints.maxWidth / chipWidth).floor().clamp(1, 8);
                final spacing = 8.0;
                final itemWidth =
                    (constraints.maxWidth - spacing * (cols - 1)) / cols;

                return Wrap(
                  spacing: spacing,
                  runSpacing: spacing,
                  children: visible
                      .map((svc) => SizedBox(
                            width: itemWidth,
                            child: _ServiceChip(
                              service: svc,
                              statusColor: _statusColor(svc.status),
                            ),
                          ))
                      .toList(),
                );
              },
            ),

          // ── "show N healthy" toggle ──────────────────────────────────────
          if (hiddenHealthy.isNotEmpty && visible.isNotEmpty) ...[
            const SizedBox(height: 10),
            Center(
              child: TextButton.icon(
                onPressed: () => setState(() => _showHealthy = true),
                icon: const Icon(Icons.expand_more,
                    size: 16, color: Color(0xFF10B981)),
                label: Text(
                  'show ${hiddenHealthy.length} healthy service${hiddenHealthy.length == 1 ? '' : 's'}',
                  style: const TextStyle(
                      color: Color(0xFF10B981), fontSize: 12),
                ),
              ),
            ),
          ],
          if (_showHealthy && _filter.isEmpty && healthy > 0) ...[
            const SizedBox(height: 6),
            Center(
              child: TextButton.icon(
                onPressed: () => setState(() => _showHealthy = false),
                icon: const Icon(Icons.expand_less,
                    size: 16, color: Color(0xFF6B7280)),
                label: const Text('hide healthy',
                    style: TextStyle(color: Color(0xFF6B7280), fontSize: 12)),
              ),
            ),
          ],
        ],
      ),
    );
  }
}

/// Small count+label pill in the header summary.
class _SummaryPill extends StatelessWidget {
  final int count;
  final String label;
  final Color color;

  const _SummaryPill({
    required this.count,
    required this.label,
    required this.color,
  });

  @override
  Widget build(BuildContext context) {
    final dim = count == 0;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: dim ? const Color(0xFF1F2937) : color.withOpacity(0.12),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(
          color: dim ? const Color(0xFF1F2937) : color.withOpacity(0.4),
        ),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 7,
            height: 7,
            decoration: BoxDecoration(
              color: dim ? const Color(0xFF4B5563) : color,
              shape: BoxShape.circle,
            ),
          ),
          const SizedBox(width: 6),
          Text(
            '$count $label',
            style: TextStyle(
              color: dim ? const Color(0xFF6B7280) : color,
              fontSize: 11.5,
              fontWeight: FontWeight.w600,
            ),
          ),
        ],
      ),
    );
  }
}

/// One compact service chip — status dot, name, err% and p95 inline.
/// ~40px tall so dozens fit without scrolling.
class _ServiceChip extends StatelessWidget {
  final ServiceHealth service;
  final Color statusColor;

  const _ServiceChip({required this.service, required this.statusColor});

  @override
  Widget build(BuildContext context) {
    final err = service.errorRate;
    final p95 = service.latencyP95;
    final isProblem = service.status != ServiceStatus.green;

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
      decoration: BoxDecoration(
        color: const Color(0xFF0B1220),
        borderRadius: BorderRadius.circular(7),
        border: Border.all(
          color: isProblem
              ? statusColor.withOpacity(0.45)
              : const Color(0xFF1F2937),
        ),
      ),
      child: Row(
        children: [
          Container(
            width: 9,
            height: 9,
            decoration: BoxDecoration(
              color: statusColor,
              shape: BoxShape.circle,
              boxShadow: isProblem
                  ? [
                      BoxShadow(
                          color: statusColor.withOpacity(0.5), blurRadius: 4)
                    ]
                  : null,
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  service.serviceName,
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 12.5,
                    fontWeight: FontWeight.w500,
                  ),
                  overflow: TextOverflow.ellipsis,
                ),
                const SizedBox(height: 1),
                Text(
                  'err ${err.toStringAsFixed(err >= 10 ? 0 : 1)}%  ·  p95 ${p95.toStringAsFixed(0)}ms',
                  style: const TextStyle(
                    color: Color(0xFF9CA3AF),
                    fontSize: 10.5,
                  ),
                  overflow: TextOverflow.ellipsis,
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// Shown when every registered service is healthy + collapsed.
class _AllHealthyState extends StatelessWidget {
  final int count;
  final VoidCallback onShow;

  const _AllHealthyState({required this.count, required this.onShow});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 20),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          const Icon(Icons.check_circle,
              color: Color(0xFF10B981), size: 18),
          const SizedBox(width: 8),
          Text(
            'All $count services healthy.',
            style: const TextStyle(color: Color(0xFF10B981), fontSize: 13),
          ),
          const SizedBox(width: 12),
          TextButton(
            onPressed: onShow,
            child: const Text('view all',
                style: TextStyle(color: Color(0xFF6B7280), fontSize: 12)),
          ),
        ],
      ),
    );
  }
}
