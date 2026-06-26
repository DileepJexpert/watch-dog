import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'services/sentinel_provider.dart';
import 'screens/dashboard_screen.dart';
import 'screens/incidents_screen.dart';
import 'screens/remediation_screen.dart';
import 'screens/rules_screen.dart';
import 'screens/agent_screen.dart';

void main() {
  runApp(const SentinelApp());
}

class SentinelApp extends StatelessWidget {
  const SentinelApp({super.key});

  @override
  Widget build(BuildContext context) {
    return ChangeNotifierProvider(
      create: (_) => SentinelProvider()..initialize(),
      child: MaterialApp(
        title: 'SENTINEL',
        debugShowCheckedModeBanner: false,
        theme: ThemeData.dark().copyWith(
          scaffoldBackgroundColor: const Color(0xFF0D1117),
          colorScheme: const ColorScheme.dark(
            primary: Color(0xFF3B82F6),
            surface: Color(0xFF111827),
          ),
          appBarTheme: const AppBarTheme(
            backgroundColor: Color(0xFF111827),
            elevation: 0,
          ),
        ),
        home: const SentinelHome(),
      ),
    );
  }
}

class SentinelHome extends StatefulWidget {
  const SentinelHome({super.key});

  @override
  State<SentinelHome> createState() => _SentinelHomeState();
}

class _SentinelHomeState extends State<SentinelHome> {
  static const _tabs = [
    _TabInfo(icon: Icons.dashboard, label: 'Dashboard'),
    _TabInfo(icon: Icons.warning_amber, label: 'Incidents'),
    _TabInfo(icon: Icons.auto_fix_high, label: 'Remediation'),
    _TabInfo(icon: Icons.rule, label: 'Rules'),
    _TabInfo(icon: Icons.smart_toy, label: 'AI Copilot'),
  ];

  static const _screens = [
    DashboardScreen(),
    IncidentsScreen(),
    RemediationScreen(),
    RulesScreen(),
    AgentScreen(),
  ];

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Row(
        children: [
          // Side navigation
          Container(
            width: 220,
            color: const Color(0xFF111827),
            child: Column(
              children: [
                // Logo / Title
                Container(
                  padding: const EdgeInsets.all(20),
                  child: const Row(
                    children: [
                      Icon(
                        Icons.shield,
                        color: Color(0xFF3B82F6),
                        size: 28,
                      ),
                      SizedBox(width: 10),
                      Text(
                        'SENTINEL',
                        style: TextStyle(
                          color: Colors.white,
                          fontSize: 18,
                          fontWeight: FontWeight.bold,
                          letterSpacing: 1.5,
                        ),
                      ),
                    ],
                  ),
                ),
                const Divider(color: Color(0xFF1F2937), height: 1),
                const SizedBox(height: 8),
                // Nav items
                ...List.generate(_tabs.length, (i) {
                  final tab = _tabs[i];
                  final selected =
                      context.watch<SentinelProvider>().activeTabIndex == i;
                  return InkWell(
                    onTap: () => context.read<SentinelProvider>().switchToTab(i),
                    child: Container(
                      padding: const EdgeInsets.symmetric(
                          horizontal: 16, vertical: 12),
                      margin: const EdgeInsets.symmetric(
                          horizontal: 8, vertical: 2),
                      decoration: BoxDecoration(
                        color: selected
                            ? const Color(0xFF1F2937)
                            : Colors.transparent,
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: Row(
                        children: [
                          Icon(
                            tab.icon,
                            color: selected
                                ? const Color(0xFF3B82F6)
                                : const Color(0xFF6B7280),
                            size: 20,
                          ),
                          const SizedBox(width: 12),
                          Text(
                            tab.label,
                            style: TextStyle(
                              color: selected
                                  ? Colors.white
                                  : const Color(0xFF9CA3AF),
                              fontSize: 14,
                              fontWeight: selected
                                  ? FontWeight.w600
                                  : FontWeight.normal,
                            ),
                          ),
                        ],
                      ),
                    ),
                  );
                }),
                const Spacer(),
                // Connection status
                Consumer<SentinelProvider>(
                  builder: (context, provider, _) {
                    return Container(
                      padding: const EdgeInsets.all(16),
                      child: Row(
                        children: [
                          Container(
                            width: 8,
                            height: 8,
                            decoration: BoxDecoration(
                              color: provider.wsConnected
                                  ? const Color(0xFF10B981)
                                  : const Color(0xFFEF4444),
                              shape: BoxShape.circle,
                            ),
                          ),
                          const SizedBox(width: 8),
                          Text(
                            provider.wsConnected
                                ? 'Live Connected'
                                : 'Disconnected',
                            style: TextStyle(
                              color: provider.wsConnected
                                  ? const Color(0xFF10B981)
                                  : const Color(0xFFEF4444),
                              fontSize: 12,
                            ),
                          ),
                        ],
                      ),
                    );
                  },
                ),
              ],
            ),
          ),
          // Main content — driven by provider's activeTabIndex so any widget
          // can switch tabs (e.g. dashboard's "Ask Copilot" button).
          Expanded(
            child:
                _screens[context.watch<SentinelProvider>().activeTabIndex],
          ),
        ],
      ),
    );
  }
}

class _TabInfo {
  final IconData icon;
  final String label;

  const _TabInfo({required this.icon, required this.label});
}
