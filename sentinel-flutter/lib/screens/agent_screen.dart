import 'dart:developer' as developer;
import 'dart:math';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import '../models/models.dart';
import '../services/api_service.dart';
import '../services/agent_websocket_service.dart';

void _dbg(String msg) {
  // kDebugMode only fires in `flutter run` (debug) builds — release builds stay silent.
  if (kDebugMode) developer.log(msg, name: 'agent');
}

/// FR-3 conversational console — Flutter parity with React's AgentConsole.
///
/// Streams via /ws/agent when reachable, falls back to the synchronous
/// /api/agent/ask. Model picker (top-right) calls /api/agent/model when
/// provider=ollama.
class AgentScreen extends StatefulWidget {
  const AgentScreen({super.key});

  @override
  State<AgentScreen> createState() => _AgentScreenState();
}

class _AgentScreenState extends State<AgentScreen> {
  late final ApiService _api;
  late final AgentWebSocketService _ws;
  late final String _sessionId;

  AgentStatus _status = AgentStatus.disabled;
  List<OllamaModel> _models = const [];
  bool _switchingModel = false;
  bool _sending = false;
  bool _wsConnected = false;

  final TextEditingController _inputCtl = TextEditingController();
  final ScrollController _scrollCtl = ScrollController();
  final List<AgentChatTurn> _turns = [];
  AgentSubscription? _currentSub;

  @override
  void initState() {
    super.initState();
    _api = ApiService();
    _ws = AgentWebSocketService();
    _ws.connectionStream.listen((c) {
      if (!mounted) return;
      setState(() => _wsConnected = c);
    });
    _ws.connect();

    final rand = Random();
    _sessionId =
        'ui-${List.generate(8, (_) => 'abcdefghijklmnopqrstuvwxyz0123456789'[rand.nextInt(36)]).join()}';

    _refreshStatus();
  }

  @override
  void dispose() {
    _currentSub?.unsubscribe();
    _inputCtl.dispose();
    _scrollCtl.dispose();
    _ws.dispose();
    _api.dispose();
    super.dispose();
  }

  Future<void> _refreshStatus() async {
    _dbg('GET /api/agent/status');
    try {
      final status = await _api.fetchAgentStatus();
      _dbg('status ← enabled=${status.enabled} provider=${status.provider} model=${status.model}');
      if (!mounted) return;
      setState(() => _status = status);
      if (status.enabled && status.modelSwitchSupported) {
        _refreshModels();
      }
    } catch (e) {
      _dbg('status fetch failed: $e');
      if (!mounted) return;
      setState(() => _status = const AgentStatus(
            enabled: false,
            message: 'Agent status endpoint unreachable',
          ));
    }
  }

  Future<void> _refreshModels() async {
    _dbg('GET /api/agent/models');
    try {
      final res = await _api.fetchModels();
      _dbg('models ← ${res.models.length} model(s): ${res.models.map((m) => m.name).join(", ")}');
      if (!mounted) return;
      setState(() => _models = res.models);
    } catch (e) {
      _dbg('models fetch failed: $e');
      if (!mounted) return;
      setState(() => _models = const []);
    }
  }

  Future<void> _onModelChange(String? newModel) async {
    if (newModel == null || newModel.isEmpty) return;
    if (newModel == _status.model || _switchingModel) return;
    _dbg('POST /api/agent/model {from: ${_status.model}, to: $newModel}');
    setState(() => _switchingModel = true);
    try {
      await _api.setActiveModel(newModel);
      _dbg('model switch OK');
      await _refreshStatus();
    } catch (e) {
      _dbg('model switch FAILED: $e');
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Failed to switch model: $e')),
      );
    } finally {
      if (mounted) setState(() => _switchingModel = false);
    }
  }

  Future<void> _send() async {
    final q = _inputCtl.text.trim();
    if (q.isEmpty || _sending) return;

    final history = _turns
        .where((t) => t.text.isNotEmpty)
        .map((t) => {
              'role': t.role == AgentTurnRole.user ? 'USER' : 'ASSISTANT',
              'content': t.text,
            })
        .toList();

    setState(() {
      _inputCtl.clear();
      _sending = true;
      _turns.add(AgentChatTurn(role: AgentTurnRole.user, text: q));
      _turns.add(AgentChatTurn(
        role: AgentTurnRole.assistant,
        text: '',
        status: AgentTurnStatus.streaming,
      ));
    });
    _scrollToBottom();
    final placeholderIdx = _turns.length - 1;

    _dbg('send session=$_sessionId stream=$_wsConnected historyLen=${history.length} question="$q"');
    if (_wsConnected) {
      _currentSub?.unsubscribe();
      _currentSub = _ws.subscribeToSession(_sessionId, (env) {
        if (!mounted) return;
        setState(() {
          if (placeholderIdx >= _turns.length) return;
          final turn = _turns[placeholderIdx];
          if (env.isStep && env.payload is Map) {
            try {
              final step = AgentStep.fromJson(
                  Map<String, dynamic>.from(env.payload as Map));
              _dbg('stream step ${step.step} tools=${step.toolCalls.map((t) => t.name).toList()}');
              turn.steps.add(step);
            } catch (e) {
              _dbg('step parse failed: $e');
            }
          } else if (env.isFinal && env.payload is Map) {
            try {
              final answer = AgentAnswer.fromJson(
                  Map<String, dynamic>.from(env.payload as Map));
              _dbg('stream final summaryLen=${answer.summary.length} evidence=${answer.evidence.length}');
              turn.answer = answer;
              turn.text = answer.summary;
              turn.status = AgentTurnStatus.done;
              _sending = false;
            } catch (e) {
              _dbg('final parse failed: $e');
              turn.text = 'Failed to parse final answer: $e';
              turn.status = AgentTurnStatus.error;
              _sending = false;
            }
          } else if (env.isError) {
            final msg = env.payload is Map
                ? (env.payload as Map)['message']?.toString() ?? 'Agent error'
                : 'Agent error';
            _dbg('stream error: $msg');
            turn.text = msg;
            turn.status = AgentTurnStatus.error;
            _sending = false;
          }
        });
        _scrollToBottom();
      });

      try {
        _dbg('POST /api/agent/ask/stream');
        await _api.startAgentStream(
          question: q,
          sessionId: _sessionId,
          history: history,
        );
        Future.delayed(const Duration(minutes: 5), () => _currentSub?.unsubscribe());
        return;
      } catch (e) {
        _dbg('stream start failed, falling back to sync: $e');
      }
    }

    try {
      _dbg('POST /api/agent/ask (sync)');
      final answer = await _api.askAgent(
        question: q,
        sessionId: _sessionId,
        history: history,
      );
      _dbg('sync ← summaryLen=${answer.summary.length} evidence=${answer.evidence.length}');
      if (!mounted) return;
      setState(() {
        if (placeholderIdx < _turns.length) {
          _turns[placeholderIdx]
            ..answer = answer
            ..text = answer.summary
            ..status = AgentTurnStatus.done;
        }
        _sending = false;
      });
    } catch (e) {
      _dbg('sync ask FAILED: $e');
      if (!mounted) return;
      setState(() {
        if (placeholderIdx < _turns.length) {
          _turns[placeholderIdx]
            ..text = e.toString()
            ..status = AgentTurnStatus.error;
        }
        _sending = false;
      });
    }
    _scrollToBottom();
  }

  void _scrollToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!_scrollCtl.hasClients) return;
      _scrollCtl.animateTo(
        _scrollCtl.position.maxScrollExtent,
        duration: const Duration(milliseconds: 200),
        curve: Curves.easeOut,
      );
    });
  }

  @override
  Widget build(BuildContext context) {
    if (!_status.enabled) {
      return _DisabledView(status: _status, onRetry: _refreshStatus);
    }

    return Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _Header(
            status: _status,
            models: _models,
            switching: _switchingModel,
            wsConnected: _wsConnected,
            disabled: _sending,
            onModelChange: _onModelChange,
          ),
          const SizedBox(height: 12),
          Expanded(
            child: Container(
              decoration: BoxDecoration(
                color: const Color(0xFF111827),
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: const Color(0xFF1F2937)),
              ),
              padding: const EdgeInsets.all(12),
              child: _turns.isEmpty
                  ? const _EmptyState()
                  : ListView.separated(
                      controller: _scrollCtl,
                      itemBuilder: (_, i) => _TurnView(turn: _turns[i]),
                      separatorBuilder: (_, __) => const SizedBox(height: 12),
                      itemCount: _turns.length,
                    ),
            ),
          ),
          const SizedBox(height: 12),
          _InputBar(
            controller: _inputCtl,
            sending: _sending,
            onSend: _send,
          ),
        ],
      ),
    );
  }
}

class _Header extends StatelessWidget {
  final AgentStatus status;
  final List<OllamaModel> models;
  final bool switching;
  final bool wsConnected;
  final bool disabled;
  final ValueChanged<String?> onModelChange;

  const _Header({
    required this.status,
    required this.models,
    required this.switching,
    required this.wsConnected,
    required this.disabled,
    required this.onModelChange,
  });

  @override
  Widget build(BuildContext context) {
    final showDropdown = status.modelSwitchSupported && models.isNotEmpty;
    final activeModel = status.model ?? '';
    final modelInDropdown = models.any((m) => m.name == activeModel);

    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: const Color(0xFF111827),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: const Color(0xFF1F2937)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'AI Copilot',
                  style: TextStyle(
                    color: Colors.white,
                    fontSize: 16,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                const SizedBox(height: 4),
                Wrap(
                  spacing: 12,
                  runSpacing: 4,
                  children: [
                    _kv('Provider', status.provider ?? '—'),
                    _kv('Mode', status.mode ?? '—'),
                    Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        const Text(
                          'Stream: ',
                          style: TextStyle(color: Color(0xFF6B7280), fontSize: 12),
                        ),
                        Text(
                          wsConnected ? 'live' : 'sync',
                          style: TextStyle(
                            color: wsConnected
                                ? const Color(0xFF10B981)
                                : const Color(0xFF6B7280),
                            fontSize: 12,
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ],
            ),
          ),
          if (showDropdown) ...[
            const Text('Model: ',
                style: TextStyle(color: Color(0xFF9CA3AF), fontSize: 12)),
            const SizedBox(width: 6),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 10),
              decoration: BoxDecoration(
                color: const Color(0xFF1F2937),
                borderRadius: BorderRadius.circular(6),
                border: Border.all(color: const Color(0xFF374151)),
              ),
              child: DropdownButton<String>(
                value: modelInDropdown ? activeModel : null,
                hint: const Text('select',
                    style: TextStyle(color: Color(0xFF9CA3AF), fontSize: 12)),
                dropdownColor: const Color(0xFF1F2937),
                underline: const SizedBox.shrink(),
                style: const TextStyle(color: Colors.white, fontSize: 12),
                onChanged: (disabled || switching) ? null : onModelChange,
                items: models
                    .map((m) => DropdownMenuItem<String>(
                          value: m.name,
                          child: Text(_modelLabel(m)),
                        ))
                    .toList(),
              ),
            ),
            if (switching) ...[
              const SizedBox(width: 8),
              const SizedBox(
                width: 14,
                height: 14,
                child: CircularProgressIndicator(strokeWidth: 2),
              ),
            ],
          ] else
            _kv('Model', status.model ?? '—'),
          const SizedBox(width: 12),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
            decoration: BoxDecoration(
              color: const Color(0xFF7C2D12).withOpacity(0.4),
              borderRadius: BorderRadius.circular(4),
            ),
            child: const Text(
              'Advisory only',
              style: TextStyle(color: Color(0xFFFB923C), fontSize: 11),
            ),
          ),
        ],
      ),
    );
  }

  String _modelLabel(OllamaModel m) {
    final parts = <String>[m.name];
    if ((m.parameterSize ?? '').isNotEmpty) parts.add('(${m.parameterSize})');
    if (m.sizeLabel.isNotEmpty) parts.add('· ${m.sizeLabel}');
    return parts.join(' ');
  }

  Widget _kv(String k, String v) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Text('$k: ',
            style: const TextStyle(color: Color(0xFF6B7280), fontSize: 12)),
        Text(v,
            style: const TextStyle(color: Color(0xFFD1D5DB), fontSize: 12)),
      ],
    );
  }
}

class _DisabledView extends StatelessWidget {
  final AgentStatus status;
  final VoidCallback onRetry;

  const _DisabledView({required this.status, required this.onRetry});

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Container(
        constraints: const BoxConstraints(maxWidth: 600),
        padding: const EdgeInsets.all(24),
        decoration: BoxDecoration(
          color: const Color(0xFF111827),
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: const Color(0xFF1F2937)),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'AI Copilot',
              style: TextStyle(
                color: Colors.white,
                fontSize: 18,
                fontWeight: FontWeight.w600,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              status.message ?? 'Agent layer disabled.',
              style: const TextStyle(color: Color(0xFF9CA3AF), fontSize: 13),
            ),
            const SizedBox(height: 16),
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: const Color(0xFF0B0F19),
                borderRadius: BorderRadius.circular(6),
              ),
              child: const Text(
                '# enable the AI layer\n'
                'AGENT_ENABLED=true\n'
                'AGENT_MODE=advisory\n'
                'LLM_PROVIDER=ollama\n'
                'LLM_BASE_URL=http://host.docker.internal:11434\n'
                'LLM_MODEL=qwen2.5-coder:14b',
                style: TextStyle(
                  color: Color(0xFF9CA3AF),
                  fontFamily: 'monospace',
                  fontSize: 12,
                ),
              ),
            ),
            const SizedBox(height: 12),
            Align(
              alignment: Alignment.centerRight,
              child: TextButton(
                onPressed: onRetry,
                child: const Text('Retry'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _EmptyState extends StatelessWidget {
  const _EmptyState();

  @override
  Widget build(BuildContext context) {
    return const Center(
      child: Text(
        'Ask anything.  e.g. "why is payments slow right now?"',
        style: TextStyle(color: Color(0xFF6B7280), fontSize: 13),
      ),
    );
  }
}

class _InputBar extends StatelessWidget {
  final TextEditingController controller;
  final bool sending;
  final VoidCallback onSend;

  const _InputBar({
    required this.controller,
    required this.sending,
    required this.onSend,
  });

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Expanded(
          child: TextField(
            controller: controller,
            enabled: !sending,
            onSubmitted: (_) => onSend(),
            style: const TextStyle(color: Colors.white, fontSize: 14),
            decoration: InputDecoration(
              hintText: 'Ask about a service, an incident, or a metric…',
              hintStyle: const TextStyle(color: Color(0xFF6B7280), fontSize: 13),
              filled: true,
              fillColor: const Color(0xFF1F2937),
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(6),
                borderSide: BorderSide.none,
              ),
              contentPadding:
                  const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
            ),
          ),
        ),
        const SizedBox(width: 8),
        ElevatedButton(
          onPressed: sending ? null : onSend,
          style: ElevatedButton.styleFrom(
            backgroundColor: const Color(0xFF3B82F6),
            disabledBackgroundColor: const Color(0xFF374151),
            foregroundColor: Colors.white,
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 14),
          ),
          child: Text(sending ? 'Working…' : 'Ask'),
        ),
      ],
    );
  }
}

class _TurnView extends StatefulWidget {
  final AgentChatTurn turn;
  const _TurnView({required this.turn});

  @override
  State<_TurnView> createState() => _TurnViewState();
}

class _TurnViewState extends State<_TurnView> {
  bool _showEvidence = false;

  @override
  Widget build(BuildContext context) {
    final turn = widget.turn;
    if (turn.role == AgentTurnRole.user) {
      return Align(
        alignment: Alignment.centerRight,
        child: Container(
          constraints: const BoxConstraints(maxWidth: 500),
          padding:
              const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
          decoration: BoxDecoration(
            color: const Color(0xFF2563EB),
            borderRadius: BorderRadius.circular(8),
          ),
          child: Text(
            turn.text,
            style: const TextStyle(color: Colors.white, fontSize: 14),
          ),
        ),
      );
    }

    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: const Color(0xFF1F2937),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (turn.status == AgentTurnStatus.streaming && turn.answer == null)
            _StreamingIndicator(steps: turn.steps),
          if (turn.status == AgentTurnStatus.error)
            Text(
              turn.text,
              style: const TextStyle(color: Color(0xFFEF4444), fontSize: 13),
            ),
          if (turn.answer != null) ..._answerWidgets(turn.answer!),
        ],
      ),
    );
  }

  List<Widget> _answerWidgets(AgentAnswer answer) {
    return [
      if (answer.summary.isNotEmpty)
        Text(
          answer.summary,
          style: const TextStyle(color: Colors.white, fontSize: 14, height: 1.4),
        ),
      if (answer.rootCause != null) ...[
        const SizedBox(height: 10),
        _RootCauseCard(rc: answer.rootCause!),
      ],
      if (answer.recommendedActions.isNotEmpty) ...[
        const SizedBox(height: 10),
        const Text('Recommended actions',
            style: TextStyle(
                color: Color(0xFF9CA3AF),
                fontSize: 11,
                letterSpacing: 1.2)),
        const SizedBox(height: 4),
        ...answer.recommendedActions.map((a) => _ActionCard(action: a)),
      ],
      const SizedBox(height: 8),
      TextButton(
        onPressed: () => setState(() => _showEvidence = !_showEvidence),
        style: TextButton.styleFrom(
          padding: EdgeInsets.zero,
          minimumSize: const Size(40, 24),
          tapTargetSize: MaterialTapTargetSize.shrinkWrap,
        ),
        child: Text(
          '${_showEvidence ? 'Hide' : 'Show'} evidence (${answer.evidence.length})',
          style: const TextStyle(color: Color(0xFF60A5FA), fontSize: 12),
        ),
      ),
      if (_showEvidence) _EvidenceList(items: answer.evidence),
    ];
  }
}

class _StreamingIndicator extends StatelessWidget {
  final List<AgentStep> steps;
  const _StreamingIndicator({required this.steps});

  @override
  Widget build(BuildContext context) {
    final lastTools = steps.isNotEmpty
        ? steps.last.toolCalls.map((t) => t.name).join(', ')
        : '';
    return Row(
      crossAxisAlignment: CrossAxisAlignment.center,
      children: [
        const SizedBox(
          width: 14,
          height: 14,
          child: CircularProgressIndicator(strokeWidth: 2),
        ),
        const SizedBox(width: 10),
        Expanded(
          child: Text(
            lastTools.isEmpty ? 'Investigating…' : 'Investigating: $lastTools',
            style: const TextStyle(color: Color(0xFF9CA3AF), fontSize: 13),
          ),
        ),
      ],
    );
  }
}

class _RootCauseCard extends StatelessWidget {
  final RootCause rc;
  const _RootCauseCard({required this.rc});

  Color get _confColor {
    switch (rc.confidence) {
      case 'high':
        return const Color(0xFF10B981);
      case 'medium':
        return const Color(0xFFFBBF24);
      case 'low':
        return const Color(0xFFFB923C);
      default:
        return const Color(0xFF9CA3AF);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: const Color(0xFF111827),
        borderRadius: BorderRadius.circular(6),
        border: Border.all(color: const Color(0xFF374151)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('Root cause hypothesis',
              style: TextStyle(
                  color: Color(0xFF6B7280),
                  fontSize: 11,
                  letterSpacing: 1.2)),
          const SizedBox(height: 4),
          Text(rc.hypothesis,
              style: const TextStyle(color: Colors.white, fontSize: 13)),
          const SizedBox(height: 4),
          Text('confidence: ${rc.confidence}',
              style: TextStyle(color: _confColor, fontSize: 11)),
        ],
      ),
    );
  }
}

class _ActionCard extends StatelessWidget {
  final RecommendedAction action;
  const _ActionCard({required this.action});

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(top: 6),
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: const Color(0xFF111827),
        borderRadius: BorderRadius.circular(6),
        border: Border.all(color: const Color(0xFF374151)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(action.action,
              style: const TextStyle(color: Colors.white, fontSize: 13)),
          if (action.rationale.isNotEmpty) ...[
            const SizedBox(height: 4),
            Text(action.rationale,
                style: const TextStyle(color: Color(0xFF9CA3AF), fontSize: 12)),
          ],
          if (action.requiresApproval) ...[
            const SizedBox(height: 4),
            const Text(
              'Requires human approval before execution',
              style: TextStyle(color: Color(0xFFFB923C), fontSize: 11),
            ),
          ],
        ],
      ),
    );
  }
}

class _EvidenceList extends StatelessWidget {
  final List<AgentEvidence> items;
  const _EvidenceList({required this.items});

  static const Map<String, Color> _badgeColors = {
    'logs': Color(0xFF1D4ED8),
    'traces': Color(0xFF7E22CE),
    'metrics': Color(0xFF15803D),
    'db': Color(0xFFCA8A04),
    'runtime': Color(0xFFDB2777),
    'knowledge': Color(0xFF4F46E5),
    'correlation': Color(0xFFB91C1C),
    'anomaly': Color(0xFFEA580C),
    'pod': Color(0xFF0E7490),
  };

  @override
  Widget build(BuildContext context) {
    if (items.isEmpty) {
      return const Padding(
        padding: EdgeInsets.only(top: 6),
        child: Text(
          'No evidence collected.',
          style: TextStyle(color: Color(0xFF6B7280), fontSize: 12),
        ),
      );
    }
    return Padding(
      padding: const EdgeInsets.only(top: 6),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: items
            .map((ev) => Padding(
                  padding: const EdgeInsets.only(bottom: 4),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Container(
                        padding: const EdgeInsets.symmetric(
                            horizontal: 6, vertical: 2),
                        decoration: BoxDecoration(
                          color: _badgeColors[ev.source] ?? const Color(0xFF374151),
                          borderRadius: BorderRadius.circular(3),
                        ),
                        child: Text(
                          ev.source,
                          style: const TextStyle(
                              color: Colors.white,
                              fontFamily: 'monospace',
                              fontSize: 11),
                        ),
                      ),
                      const SizedBox(width: 6),
                      Expanded(
                        child: RichText(
                          text: TextSpan(
                            style: const TextStyle(fontSize: 12),
                            children: [
                              TextSpan(
                                text: '${ev.ref}  ',
                                style: const TextStyle(
                                    color: Color(0xFF9CA3AF),
                                    fontFamily: 'monospace'),
                              ),
                              if (ev.excerpt != null && ev.excerpt!.isNotEmpty)
                                TextSpan(
                                  text: ev.excerpt!,
                                  style: const TextStyle(color: Color(0xFF6B7280)),
                                ),
                            ],
                          ),
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                    ],
                  ),
                ))
            .toList(),
      ),
    );
  }
}
