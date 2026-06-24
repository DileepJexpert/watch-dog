import 'dart:convert';
import 'package:http/http.dart' as http;
import '../models/models.dart';

class ApiService {
  static const defaultBaseUrl = String.fromEnvironment(
    'SENTINEL_API_URL',
    defaultValue: 'http://localhost:8080',
  );

  final String baseUrl;
  final http.Client _client;

  ApiService({String? baseUrl})
      : baseUrl = baseUrl ?? defaultBaseUrl,
        _client = http.Client();

  Future<List<ServiceHealth>> fetchServices() async {
    final response = await _client.get(
      Uri.parse('$baseUrl/api/dashboard/summary'),
    );
    if (response.statusCode == 200) {
      final List<dynamic> data = jsonDecode(response.body);
      return data.map((e) => ServiceHealth.fromJson(e)).toList();
    }
    throw ApiException('Failed to fetch services: ${response.statusCode}');
  }

  Future<List<Incident>> fetchActiveIncidents() async {
    final response = await _client.get(
      Uri.parse('$baseUrl/api/dashboard/incidents/active'),
    );
    if (response.statusCode == 200) {
      final List<dynamic> data = jsonDecode(response.body);
      return data.map((e) => Incident.fromJson(e)).toList();
    }
    throw ApiException('Failed to fetch incidents: ${response.statusCode}');
  }

  Future<List<RemediationLog>> fetchRemediationLogs({int size = 20}) async {
    final response = await _client.get(
      Uri.parse('$baseUrl/api/remediation/log?size=$size'),
    );
    if (response.statusCode == 200) {
      // This endpoint returns a Spring Page object ({content: [...], ...}),
      // not a bare list. Read .content; tolerate a plain list too.
      final decoded = jsonDecode(response.body);
      final List<dynamic> data =
          decoded is List ? decoded : (decoded['content'] as List<dynamic>);
      return data.map((e) => RemediationLog.fromJson(e)).toList();
    }
    throw ApiException(
        'Failed to fetch remediation logs: ${response.statusCode}');
  }

  Future<DashboardStats> fetchStats() async {
    final response = await _client.get(
      Uri.parse('$baseUrl/api/dashboard/stats'),
    );
    if (response.statusCode == 200) {
      return DashboardStats.fromJson(jsonDecode(response.body));
    }
    throw ApiException('Failed to fetch stats: ${response.statusCode}');
  }

  Future<void> resolveIncident(String id) async {
    final response = await _client.post(
      Uri.parse('$baseUrl/api/incidents/$id/resolve'),
    );
    if (response.statusCode != 200) {
      throw ApiException('Failed to resolve incident: ${response.statusCode}');
    }
  }

  Future<List<AlertRule>> fetchRules() async {
    final response = await _client.get(
      Uri.parse('$baseUrl/api/rules'),
    );
    if (response.statusCode == 200) {
      final List<dynamic> data = jsonDecode(response.body);
      return data.map((e) => AlertRule.fromJson(e)).toList();
    }
    throw ApiException('Failed to fetch rules: ${response.statusCode}');
  }

  Future<void> createRule(AlertRule rule) async {
    final response = await _client.post(
      Uri.parse('$baseUrl/api/rules'),
      headers: {'Content-Type': 'application/json'},
      body: jsonEncode(rule.toJson()),
    );
    if (response.statusCode != 200 && response.statusCode != 201) {
      throw ApiException('Failed to create rule: ${response.statusCode}');
    }
  }

  Future<void> deleteRule(String id) async {
    final response = await _client.delete(
      Uri.parse('$baseUrl/api/rules/$id'),
    );
    if (response.statusCode != 200 && response.statusCode != 204) {
      throw ApiException('Failed to delete rule: ${response.statusCode}');
    }
  }

  // ── AI Copilot endpoints (FR-3) ─────────────────────────────────────────

  Future<AgentStatus> fetchAgentStatus() async {
    final response = await _client.get(Uri.parse('$baseUrl/api/agent/status'));
    if (response.statusCode == 200) {
      return AgentStatus.fromJson(jsonDecode(response.body) as Map<String, dynamic>);
    }
    if (response.statusCode == 404) {
      return const AgentStatus(enabled: false, message: 'Agent endpoint not present');
    }
    throw ApiException('Failed to fetch agent status: ${response.statusCode}');
  }

  Future<ModelsResponse> fetchModels() async {
    final response = await _client.get(Uri.parse('$baseUrl/api/agent/models'));
    if (response.statusCode == 200) {
      return ModelsResponse.fromJson(jsonDecode(response.body) as Map<String, dynamic>);
    }
    throw ApiException('Failed to fetch models: ${response.statusCode}');
  }

  Future<void> setActiveModel(String model) async {
    final response = await _client.post(
      Uri.parse('$baseUrl/api/agent/model'),
      headers: {'Content-Type': 'application/json'},
      body: jsonEncode({'model': model}),
    );
    if (response.statusCode != 200) {
      throw ApiException('Failed to set model: ${response.statusCode} ${response.body}');
    }
  }

  Future<AgentAnswer> askAgent({
    required String question,
    required String sessionId,
    List<Map<String, String>> history = const [],
  }) async {
    final response = await _client.post(
      Uri.parse('$baseUrl/api/agent/ask'),
      headers: {'Content-Type': 'application/json'},
      body: jsonEncode({
        'question': question,
        'sessionId': sessionId,
        'history': history,
      }),
    );
    if (response.statusCode == 200) {
      return AgentAnswer.fromJson(jsonDecode(response.body) as Map<String, dynamic>);
    }
    throw ApiException('Agent ask failed: ${response.statusCode}');
  }

  /// Fire-and-forget — backend pushes step/final/error envelopes to
  /// /topic/agent/{sessionId} which AgentWebSocketService subscribes to.
  Future<void> startAgentStream({
    required String question,
    required String sessionId,
    List<Map<String, String>> history = const [],
  }) async {
    final response = await _client.post(
      Uri.parse('$baseUrl/api/agent/ask/stream'),
      headers: {'Content-Type': 'application/json'},
      body: jsonEncode({
        'question': question,
        'sessionId': sessionId,
        'history': history,
      }),
    );
    if (response.statusCode != 202 && response.statusCode != 200) {
      throw ApiException('Agent stream start failed: ${response.statusCode}');
    }
  }

  Future<void> ingestKnowledge({required String title, required String content}) async {
    final response = await _client.post(
      Uri.parse('$baseUrl/api/agent/knowledge'),
      headers: {'Content-Type': 'application/json'},
      body: jsonEncode({'title': title, 'content': content}),
    );
    if (response.statusCode != 200) {
      throw ApiException('Knowledge ingest failed: ${response.statusCode}');
    }
  }

  void dispose() {
    _client.close();
  }
}

class ApiException implements Exception {
  final String message;
  const ApiException(this.message);

  @override
  String toString() => 'ApiException: $message';
}
