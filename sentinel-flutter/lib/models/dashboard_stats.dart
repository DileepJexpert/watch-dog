class DashboardStats {
  final int openIncidents;
  final int incidentsLast24h;
  final int incidentsLast7d;
  final int serviceCount;

  const DashboardStats({
    required this.openIncidents,
    required this.incidentsLast24h,
    required this.incidentsLast7d,
    required this.serviceCount,
  });

  factory DashboardStats.fromJson(Map<String, dynamic> json) {
    return DashboardStats(
      openIncidents: json['openIncidents'] as int? ?? 0,
      incidentsLast24h: json['incidentsLast24h'] as int? ?? 0,
      incidentsLast7d: json['incidentsLast7d'] as int? ?? 0,
      serviceCount: json['serviceCount'] as int? ?? 0,
    );
  }

  static const empty = DashboardStats(
    openIncidents: 0,
    incidentsLast24h: 0,
    incidentsLast7d: 0,
    serviceCount: 0,
  );
}
