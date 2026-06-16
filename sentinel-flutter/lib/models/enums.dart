enum Severity {
  p1Critical('P1_CRITICAL', 'P1', 1),
  p2High('P2_HIGH', 'P2', 2),
  p3Medium('P3_MEDIUM', 'P3', 3),
  p4Info('P4_INFO', 'P4', 4);

  const Severity(this.value, this.label, this.level);
  final String value;
  final String label;
  final int level;

  static Severity fromString(String s) =>
      Severity.values.firstWhere((e) => e.value == s, orElse: () => Severity.p4Info);

  bool isHigherThan(Severity other) => level < other.level;
}

enum IncidentStatus {
  open('OPEN'),
  investigating('INVESTIGATING'),
  autoRemediated('AUTO_REMEDIATED'),
  resolved('RESOLVED'),
  escalated('ESCALATED');

  const IncidentStatus(this.value);
  final String value;

  static IncidentStatus fromString(String s) =>
      IncidentStatus.values.firstWhere((e) => e.value == s, orElse: () => IncidentStatus.open);
}

enum ServiceStatus {
  green('GREEN'),
  yellow('YELLOW'),
  red('RED');

  const ServiceStatus(this.value);
  final String value;

  static ServiceStatus fromString(String s) =>
      ServiceStatus.values.firstWhere((e) => e.value == s, orElse: () => ServiceStatus.green);
}

enum SignalType {
  log('LOG'),
  trace('TRACE'),
  metric('METRIC'),
  probe('PROBE');

  const SignalType(this.value);
  final String value;

  static SignalType fromString(String s) =>
      SignalType.values.firstWhere((e) => e.value == s, orElse: () => SignalType.log);
}
