import 'package:flutter/material.dart';
import '../models/models.dart';
import '../services/sentinel_provider.dart';
import 'package:provider/provider.dart';

class RuleBuilder extends StatefulWidget {
  const RuleBuilder({super.key});

  @override
  State<RuleBuilder> createState() => _RuleBuilderState();
}

class _RuleBuilderState extends State<RuleBuilder> {
  final _nameController = TextEditingController();
  final _serviceController = TextEditingController();
  final _yamlController = TextEditingController();
  Severity _severity = Severity.p3Medium;
  String? _message;
  bool _isError = false;
  bool _saving = false;

  static const _defaultYaml = '''conditions:
  - metricName: error_rate_percent
    signalType: METRIC
    comparator: GT
    threshold: 5.0
    durationMinutes: 5
operator: AND''';

  @override
  void initState() {
    super.initState();
    _yamlController.text = _defaultYaml;
  }

  @override
  void dispose() {
    _nameController.dispose();
    _serviceController.dispose();
    _yamlController.dispose();
    super.dispose();
  }

  Future<void> _saveRule() async {
    if (_nameController.text.trim().isEmpty) {
      setState(() {
        _message = 'Rule name is required.';
        _isError = true;
      });
      return;
    }

    setState(() {
      _saving = true;
      _message = null;
    });

    try {
      final rule = AlertRule(
        id: '',
        name: _nameController.text.trim(),
        serviceName: _serviceController.text.trim().isEmpty
            ? null
            : _serviceController.text.trim(),
        conditionYaml: _yamlController.text,
        severity: _severity,
        enabled: true,
        createdBy: 'dashboard',
        createdAt: DateTime.now(),
      );

      await context.read<SentinelProvider>().createRule(rule);

      setState(() {
        _message = 'Rule created successfully!';
        _isError = false;
        _nameController.clear();
        _serviceController.clear();
        _yamlController.text = _defaultYaml;
      });
    } catch (e) {
      setState(() {
        _message = 'Failed to create rule: $e';
        _isError = true;
      });
    } finally {
      setState(() => _saving = false);
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
            'Create Alert Rule',
            style: TextStyle(
              color: Colors.white,
              fontSize: 18,
              fontWeight: FontWeight.w600,
            ),
          ),
          const SizedBox(height: 16),
          _buildTextField('Rule Name', _nameController, 'e.g., High Error Rate'),
          const SizedBox(height: 12),
          _buildTextField('Service Name (optional)', _serviceController,
              'e.g., payment-service'),
          const SizedBox(height: 12),
          _buildSeverityDropdown(),
          const SizedBox(height: 12),
          const Text(
            'Condition (YAML)',
            style: TextStyle(color: Color(0xFF9CA3AF), fontSize: 13),
          ),
          const SizedBox(height: 4),
          TextField(
            controller: _yamlController,
            maxLines: 8,
            style: const TextStyle(
              color: Colors.white,
              fontFamily: 'monospace',
              fontSize: 12,
            ),
            decoration: InputDecoration(
              filled: true,
              fillColor: const Color(0xFF1F2937),
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(8),
                borderSide: const BorderSide(color: Color(0xFF374151)),
              ),
              enabledBorder: OutlineInputBorder(
                borderRadius: BorderRadius.circular(8),
                borderSide: const BorderSide(color: Color(0xFF374151)),
              ),
              focusedBorder: OutlineInputBorder(
                borderRadius: BorderRadius.circular(8),
                borderSide: const BorderSide(color: Color(0xFF60A5FA)),
              ),
            ),
          ),
          const SizedBox(height: 16),
          if (_message != null)
            Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: Text(
                _message!,
                style: TextStyle(
                  color: _isError
                      ? const Color(0xFFF87171)
                      : const Color(0xFF34D399),
                  fontSize: 13,
                ),
              ),
            ),
          SizedBox(
            width: double.infinity,
            child: ElevatedButton(
              onPressed: _saving ? null : _saveRule,
              style: ElevatedButton.styleFrom(
                backgroundColor: const Color(0xFF3B82F6),
                foregroundColor: Colors.white,
                padding: const EdgeInsets.symmetric(vertical: 12),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(8),
                ),
              ),
              child: _saving
                  ? const SizedBox(
                      height: 18,
                      width: 18,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        color: Colors.white,
                      ),
                    )
                  : const Text('Save Rule'),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildTextField(
      String label, TextEditingController controller, String hint) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          label,
          style: const TextStyle(color: Color(0xFF9CA3AF), fontSize: 13),
        ),
        const SizedBox(height: 4),
        TextField(
          controller: controller,
          style: const TextStyle(color: Colors.white, fontSize: 14),
          decoration: InputDecoration(
            hintText: hint,
            hintStyle: const TextStyle(color: Color(0xFF4B5563)),
            filled: true,
            fillColor: const Color(0xFF1F2937),
            contentPadding:
                const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
            border: OutlineInputBorder(
              borderRadius: BorderRadius.circular(8),
              borderSide: const BorderSide(color: Color(0xFF374151)),
            ),
            enabledBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(8),
              borderSide: const BorderSide(color: Color(0xFF374151)),
            ),
            focusedBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(8),
              borderSide: const BorderSide(color: Color(0xFF60A5FA)),
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildSeverityDropdown() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text(
          'Severity',
          style: TextStyle(color: Color(0xFF9CA3AF), fontSize: 13),
        ),
        const SizedBox(height: 4),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 12),
          decoration: BoxDecoration(
            color: const Color(0xFF1F2937),
            borderRadius: BorderRadius.circular(8),
            border: Border.all(color: const Color(0xFF374151)),
          ),
          child: DropdownButtonHideUnderline(
            child: DropdownButton<Severity>(
              value: _severity,
              isExpanded: true,
              dropdownColor: const Color(0xFF1F2937),
              style: const TextStyle(color: Colors.white, fontSize: 14),
              items: Severity.values.map((s) {
                return DropdownMenuItem(
                  value: s,
                  child: Text(s.value),
                );
              }).toList(),
              onChanged: (value) {
                if (value != null) setState(() => _severity = value);
              },
            ),
          ),
        ),
      ],
    );
  }
}
