import 'package:flutter/material.dart';
import 'package:fl_chart/fl_chart.dart';

class ErrorRateDataPoint {
  final DateTime timestamp;
  final double errorRate;
  final double? anomalyUpper;
  final double? anomalyLower;

  const ErrorRateDataPoint({
    required this.timestamp,
    required this.errorRate,
    this.anomalyUpper,
    this.anomalyLower,
  });
}

class ErrorRateTrend extends StatelessWidget {
  final String serviceName;
  final List<ErrorRateDataPoint> data;

  const ErrorRateTrend({
    super.key,
    required this.serviceName,
    required this.data,
  });

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
          Text(
            'Error Rate — $serviceName',
            style: const TextStyle(
              color: Colors.white,
              fontSize: 18,
              fontWeight: FontWeight.w600,
            ),
          ),
          const SizedBox(height: 16),
          SizedBox(
            height: 220,
            child: data.isEmpty
                ? const Center(
                    child: Text(
                      'No data available.',
                      style: TextStyle(color: Color(0xFF6B7280)),
                    ),
                  )
                : _buildChart(),
          ),
        ],
      ),
    );
  }

  Widget _buildChart() {
    final spots = <FlSpot>[];
    final anomalyUpperSpots = <FlSpot>[];
    final anomalyLowerSpots = <FlSpot>[];

    for (int i = 0; i < data.length; i++) {
      spots.add(FlSpot(i.toDouble(), data[i].errorRate));
      if (data[i].anomalyUpper != null) {
        anomalyUpperSpots.add(FlSpot(i.toDouble(), data[i].anomalyUpper!));
      }
      if (data[i].anomalyLower != null) {
        anomalyLowerSpots.add(FlSpot(i.toDouble(), data[i].anomalyLower!));
      }
    }

    final lines = <LineChartBarData>[
      LineChartBarData(
        spots: spots,
        isCurved: true,
        color: const Color(0xFFF87171),
        barWidth: 2,
        dotData: const FlDotData(show: false),
        belowBarData: BarAreaData(
          show: true,
          color: const Color(0xFFF87171).withOpacity(0.1),
        ),
      ),
    ];

    if (anomalyUpperSpots.length == data.length) {
      lines.add(LineChartBarData(
        spots: anomalyUpperSpots,
        isCurved: true,
        color: const Color(0xFF6B7280).withOpacity(0.5),
        barWidth: 1,
        dashArray: [4, 4],
        dotData: const FlDotData(show: false),
      ));
    }
    if (anomalyLowerSpots.length == data.length) {
      lines.add(LineChartBarData(
        spots: anomalyLowerSpots,
        isCurved: true,
        color: const Color(0xFF6B7280).withOpacity(0.5),
        barWidth: 1,
        dashArray: [4, 4],
        dotData: const FlDotData(show: false),
      ));
    }

    return LineChart(
      LineChartData(
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
              reservedSize: 40,
              getTitlesWidget: (value, meta) => Text(
                '${value.toStringAsFixed(1)}%',
                style: const TextStyle(
                  color: Color(0xFF9CA3AF),
                  fontSize: 10,
                ),
              ),
            ),
          ),
          bottomTitles: const AxisTitles(
            sideTitles: SideTitles(showTitles: false),
          ),
        ),
        borderData: FlBorderData(show: false),
        extraLinesData: ExtraLinesData(
          horizontalLines: [
            HorizontalLine(
              y: 5,
              color: const Color(0xFFEF4444).withOpacity(0.5),
              strokeWidth: 1,
              dashArray: [4, 4],
              label: HorizontalLineLabel(
                show: true,
                alignment: Alignment.topRight,
                style: const TextStyle(
                  color: Color(0xFFEF4444),
                  fontSize: 10,
                ),
                labelResolver: (_) => '5% threshold',
              ),
            ),
          ],
        ),
        lineBarsData: lines,
        lineTouchData: LineTouchData(
          touchTooltipData: LineTouchTooltipData(
            getTooltipColor: (_) => const Color(0xFF1F2937),
            getTooltipItems: (spots) => spots.map((spot) {
              return LineTooltipItem(
                '${spot.y.toStringAsFixed(2)}%',
                const TextStyle(color: Colors.white, fontSize: 12),
              );
            }).toList(),
          ),
        ),
      ),
    );
  }
}
