import 'package:flutter/material.dart';
import 'package:fl_chart/fl_chart.dart';

class LatencyData {
  final String service;
  final double p50;
  final double p95;
  final double p99;

  const LatencyData({
    required this.service,
    required this.p50,
    required this.p95,
    required this.p99,
  });
}

class LatencyHeatmap extends StatelessWidget {
  final List<LatencyData> data;

  const LatencyHeatmap({super.key, required this.data});

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
            'Latency Percentiles (ms)',
            style: TextStyle(
              color: Colors.white,
              fontSize: 18,
              fontWeight: FontWeight.w600,
            ),
          ),
          const SizedBox(height: 8),
          _buildLegend(),
          const SizedBox(height: 12),
          SizedBox(
            height: 260,
            child: data.isEmpty
                ? const Center(
                    child: Text(
                      'No latency data available.',
                      style: TextStyle(color: Color(0xFF6B7280)),
                    ),
                  )
                : _buildChart(),
          ),
        ],
      ),
    );
  }

  Widget _buildLegend() {
    return Row(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        _legendDot(const Color(0xFF34D399), 'P50'),
        const SizedBox(width: 16),
        _legendDot(const Color(0xFFFBBF24), 'P95'),
        const SizedBox(width: 16),
        _legendDot(const Color(0xFFF87171), 'P99'),
      ],
    );
  }

  Widget _legendDot(Color color, String label) {
    return Row(
      children: [
        Container(
          width: 10,
          height: 10,
          decoration: BoxDecoration(
            color: color,
            borderRadius: BorderRadius.circular(2),
          ),
        ),
        const SizedBox(width: 4),
        Text(
          label,
          style: const TextStyle(color: Color(0xFF9CA3AF), fontSize: 12),
        ),
      ],
    );
  }

  Widget _buildChart() {
    return BarChart(
      BarChartData(
        alignment: BarChartAlignment.spaceAround,
        maxY: _maxY(),
        gridData: FlGridData(
          show: true,
          drawVerticalLine: false,
          getDrawingHorizontalLine: (value) => FlLine(
            color: const Color(0xFF374151),
            strokeWidth: 0.5,
          ),
        ),
        titlesData: FlTitlesData(
          rightTitles: const AxisTitles(sideTitles: SideTitles(showTitles: false)),
          topTitles: const AxisTitles(sideTitles: SideTitles(showTitles: false)),
          leftTitles: AxisTitles(
            sideTitles: SideTitles(
              showTitles: true,
              reservedSize: 48,
              getTitlesWidget: (value, meta) => Text(
                '${value.toInt()}ms',
                style: const TextStyle(
                  color: Color(0xFF9CA3AF),
                  fontSize: 10,
                ),
              ),
            ),
          ),
          bottomTitles: AxisTitles(
            sideTitles: SideTitles(
              showTitles: true,
              reservedSize: 40,
              getTitlesWidget: (value, meta) {
                final idx = value.toInt();
                if (idx < 0 || idx >= data.length) return const SizedBox.shrink();
                return Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: RotatedBox(
                    quarterTurns: -1,
                    child: Text(
                      data[idx].service,
                      style: const TextStyle(
                        color: Color(0xFF9CA3AF),
                        fontSize: 10,
                      ),
                    ),
                  ),
                );
              },
            ),
          ),
        ),
        borderData: FlBorderData(show: false),
        extraLinesData: ExtraLinesData(
          horizontalLines: [
            HorizontalLine(
              y: 2000,
              color: const Color(0xFFEF4444).withOpacity(0.5),
              strokeWidth: 1,
              dashArray: [4, 4],
            ),
          ],
        ),
        barGroups: List.generate(data.length, (i) {
          final d = data[i];
          return BarChartGroupData(
            x: i,
            barRods: [
              BarChartRodData(
                toY: d.p50,
                color: const Color(0xFF34D399),
                width: 10,
                borderRadius: const BorderRadius.vertical(top: Radius.circular(2)),
              ),
              BarChartRodData(
                toY: d.p95,
                color: const Color(0xFFFBBF24),
                width: 10,
                borderRadius: const BorderRadius.vertical(top: Radius.circular(2)),
              ),
              BarChartRodData(
                toY: d.p99,
                color: d.p99 > 2000
                    ? const Color(0xFFEF4444)
                    : const Color(0xFFF87171),
                width: 10,
                borderRadius: const BorderRadius.vertical(top: Radius.circular(2)),
              ),
            ],
          );
        }),
        barTouchData: BarTouchData(
          touchTooltipData: BarTouchTooltipData(
            getTooltipColor: (_) => const Color(0xFF1F2937),
            getTooltipItem: (group, groupIndex, rod, rodIndex) {
              final labels = ['P50', 'P95', 'P99'];
              return BarTooltipItem(
                '${labels[rodIndex]}: ${rod.toY.toStringAsFixed(0)}ms',
                const TextStyle(color: Colors.white, fontSize: 12),
              );
            },
          ),
        ),
      ),
    );
  }

  double _maxY() {
    double max = 0;
    for (final d in data) {
      if (d.p99 > max) max = d.p99;
      if (d.p95 > max) max = d.p95;
    }
    return (max * 1.2).ceilToDouble();
  }
}
