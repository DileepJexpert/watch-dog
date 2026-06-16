import 'dart:async';
import 'dart:convert';
import 'package:stomp_dart_client/stomp_dart_client.dart';
import '../models/models.dart';

/// FR-3 streaming consumer — subscribes to /topic/agent/{sessionId}
/// for live tool-call updates while the agent works.
class AgentWebSocketService {
  final String url;
  StompClient? _client;
  bool _connected = false;
  Timer? _reconnectTimer;
  final _connectionController = StreamController<bool>.broadcast();
  final Map<String, _ActiveSubscription> _subs = {};

  AgentWebSocketService({this.url = 'ws://localhost:8080/ws/agent'});

  Stream<bool> get connectionStream => _connectionController.stream;
  bool get isConnected => _connected;

  void connect() {
    if (_client != null) return;
    _client = StompClient(
      config: StompConfig.sockJS(
        url: url,
        onConnect: _onConnect,
        onDisconnect: _onDisconnect,
        onWebSocketError: (e) {
          _setConnected(false);
          _scheduleReconnect();
        },
        onStompError: (frame) {
          _setConnected(false);
          _scheduleReconnect();
        },
      ),
    );
    _client!.activate();
  }

  void _onConnect(StompFrame frame) {
    _setConnected(true);
    _reconnectTimer?.cancel();
    for (final sub in _subs.values) {
      sub.attach(_client!);
    }
  }

  void _onDisconnect(StompFrame frame) {
    _setConnected(false);
    _scheduleReconnect();
  }

  void _setConnected(bool value) {
    if (_connected == value) return;
    _connected = value;
    _connectionController.add(value);
  }

  void _scheduleReconnect() {
    _reconnectTimer?.cancel();
    _reconnectTimer = Timer(const Duration(seconds: 5), () {
      _client?.deactivate();
      _client = null;
      connect();
    });
  }

  /// Subscribe to envelopes for one agent session. Returns a handle the caller
  /// can use to unsubscribe when the chat turn ends.
  AgentSubscription subscribeToSession(
    String sessionId,
    void Function(AgentStreamEnvelope) onEnvelope,
  ) {
    final destination = '/topic/agent/$sessionId';
    final sub = _ActiveSubscription(destination, onEnvelope);
    _subs[destination] = sub;
    if (_connected && _client != null) {
      sub.attach(_client!);
    }
    return AgentSubscription._(this, destination);
  }

  void _unsubscribe(String destination) {
    final sub = _subs.remove(destination);
    sub?.detach();
  }

  void disconnect() {
    _reconnectTimer?.cancel();
    for (final sub in _subs.values) {
      sub.detach();
    }
    _subs.clear();
    _client?.deactivate();
    _client = null;
    _setConnected(false);
  }

  void dispose() {
    disconnect();
    _connectionController.close();
  }
}

class AgentSubscription {
  final AgentWebSocketService _service;
  final String _destination;
  bool _disposed = false;

  AgentSubscription._(this._service, this._destination);

  void unsubscribe() {
    if (_disposed) return;
    _disposed = true;
    _service._unsubscribe(_destination);
  }
}

class _ActiveSubscription {
  final String destination;
  final void Function(AgentStreamEnvelope) onEnvelope;
  void Function()? _detach;

  _ActiveSubscription(this.destination, this.onEnvelope);

  void attach(StompClient client) {
    _detach?.call();
    _detach = client.subscribe(
      destination: destination,
      callback: (frame) {
        if (frame.body == null) return;
        try {
          final json = jsonDecode(frame.body!) as Map<String, dynamic>;
          onEnvelope(AgentStreamEnvelope.fromJson(json));
        } catch (_) {
          // Bad payload — ignore so one malformed frame doesn't kill the stream.
        }
      },
    );
  }

  void detach() {
    _detach?.call();
    _detach = null;
  }
}
