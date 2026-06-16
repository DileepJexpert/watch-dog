import 'dart:async';
import 'dart:convert';
import 'package:stomp_dart_client/stomp_dart_client.dart';
import '../models/models.dart';

class WebSocketService {
  final String url;
  StompClient? _client;
  final _incidentController = StreamController<Incident>.broadcast();
  final _connectionController = StreamController<bool>.broadcast();
  bool _connected = false;
  Timer? _reconnectTimer;

  WebSocketService({this.url = 'ws://localhost:8080/ws/events'});

  Stream<Incident> get incidentStream => _incidentController.stream;
  Stream<bool> get connectionStream => _connectionController.stream;
  bool get isConnected => _connected;

  void connect() {
    _client = StompClient(
      config: StompConfig.sockJS(
        url: url,
        onConnect: _onConnect,
        onDisconnect: _onDisconnect,
        onWebSocketError: (error) {
          print('WebSocket error: $error');
          _setConnected(false);
          _scheduleReconnect();
        },
        onStompError: (frame) {
          print('STOMP error: ${frame.body}');
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

    _client!.subscribe(
      destination: '/topic/incidents',
      callback: (frame) {
        if (frame.body != null) {
          try {
            final json = jsonDecode(frame.body!);
            final incident = Incident.fromJson(json);
            _incidentController.add(incident);
          } catch (e) {
            print('Failed to parse incident: $e');
          }
        }
      },
    );
  }

  void _onDisconnect(StompFrame frame) {
    _setConnected(false);
    _scheduleReconnect();
  }

  void _setConnected(bool value) {
    _connected = value;
    _connectionController.add(value);
  }

  void _scheduleReconnect() {
    _reconnectTimer?.cancel();
    _reconnectTimer = Timer(const Duration(seconds: 5), () {
      print('Attempting WebSocket reconnect...');
      connect();
    });
  }

  void disconnect() {
    _reconnectTimer?.cancel();
    _client?.deactivate();
    _setConnected(false);
  }

  void dispose() {
    disconnect();
    _incidentController.close();
    _connectionController.close();
  }
}
