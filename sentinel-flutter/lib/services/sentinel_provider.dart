import 'dart:async';
import 'package:flutter/foundation.dart';
import '../models/models.dart';
import 'api_service.dart';
import 'websocket_service.dart';

class SentinelProvider extends ChangeNotifier {
  final ApiService _api;
  final WebSocketService _ws;

  List<ServiceHealth> services = [];
  List<Incident> incidents = [];
  List<RemediationLog> remediationLogs = [];
  List<AlertRule> rules = [];
  DashboardStats stats = DashboardStats.empty;
  bool wsConnected = false;
  String? error;

  Timer? _pollTimer;
  StreamSubscription<Incident>? _incidentSub;
  StreamSubscription<bool>? _connectionSub;

  SentinelProvider({ApiService? api, WebSocketService? ws})
      : _api = api ?? ApiService(),
        _ws = ws ?? WebSocketService();

  void initialize() {
    _ws.connect();

    _connectionSub = _ws.connectionStream.listen((connected) {
      wsConnected = connected;
      notifyListeners();
    });

    _incidentSub = _ws.incidentStream.listen((incident) {
      final idx = incidents.indexWhere((i) => i.id == incident.id);
      if (idx >= 0) {
        incidents[idx] = incident;
      } else {
        incidents.insert(0, incident);
      }
      notifyListeners();
    });

    fetchAll();
    _pollTimer = Timer.periodic(const Duration(seconds: 30), (_) => fetchAll());
  }

  Future<void> fetchAll() async {
    try {
      final results = await Future.wait([
        _api.fetchServices(),
        _api.fetchActiveIncidents(),
        _api.fetchRemediationLogs(),
        _api.fetchStats(),
      ]);
      services = results[0] as List<ServiceHealth>;
      incidents = results[1] as List<Incident>;
      remediationLogs = results[2] as List<RemediationLog>;
      stats = results[3] as DashboardStats;
      error = null;
    } catch (e) {
      error = e.toString();
    }
    notifyListeners();
  }

  Future<void> fetchRules() async {
    try {
      rules = await _api.fetchRules();
      error = null;
    } catch (e) {
      error = e.toString();
    }
    notifyListeners();
  }

  Future<void> resolveIncident(String id) async {
    try {
      await _api.resolveIncident(id);
      incidents = incidents
          .map((i) => i.id == id
              ? Incident(
                  id: i.id,
                  serviceName: i.serviceName,
                  title: i.title,
                  severity: i.severity,
                  status: IncidentStatus.resolved,
                  correlationRule: i.correlationRule,
                  detectedAt: i.detectedAt,
                  resolvedAt: DateTime.now(),
                  autoRemediated: i.autoRemediated,
                  correlatedSignalIds: i.correlatedSignalIds,
                )
              : i)
          .toList();
      notifyListeners();
    } catch (e) {
      error = e.toString();
      notifyListeners();
    }
  }

  Future<void> createRule(AlertRule rule) async {
    await _api.createRule(rule);
    await fetchRules();
  }

  Future<void> deleteRule(String id) async {
    await _api.deleteRule(id);
    await fetchRules();
  }

  @override
  void dispose() {
    _pollTimer?.cancel();
    _incidentSub?.cancel();
    _connectionSub?.cancel();
    _ws.dispose();
    _api.dispose();
    super.dispose();
  }
}
