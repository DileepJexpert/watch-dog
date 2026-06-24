# Proactive, Automated Right-Sizing for ~140 Spring Boot Microservices on EKS

Internal research / decision document.

## TL;DR

- **Build, don't buy (initially).** Recommended stack: Prometheus/Grafana +
  JVM Micrometer metrics for detection; Goldilocks/VPA-in-recommendation-mode
  + a metrics-driven GitOps PR pipeline (Argo CD) for corrective action;
  Karpenter consolidation for node-level savings; Kyverno for policy
  guardrails; KEDA/HPA on real metrics (Kafka lag, request rate) for
  elasticity — owned by a platform team with FinOps showback. This replaces
  "Ramu chases developers" with an auditable, code-reviewed pipeline that
  fits bank change-control.
- **Never auto-evict JVM pods.** VPA in Auto/Recreate mode is the wrong
  default for a regulated bank running Java: it restarts pods (losing JIT
  warmup), can shrink memory below the JVM's committed floor, and conflicts
  with HPA. Use VPA only as a recommendation engine; apply changes through
  reviewed Git pull requests. Reserve VPA `InPlaceOrRecreate` (K8s 1.33+)
  for narrow, safe cases.
- **Phase it: Observe → Recommend → Automate-safe-cases.** Prove savings
  with committed-vs-used memory, cost-per-service, and bin-packing
  efficiency. A commercial platform (StormForge, CAST AI, or PerfectScale)
  becomes worthwhile only once manual PR-review overhead exceeds its
  license cost — and StormForge is the strongest JVM-aware option because
  it couples request sizing with HPA targets.

## Key Findings

1. **The waste is real and large.** Per Cast AI's 2025 Kubernetes Cost
   Benchmark Report (analysis of 2,100+ organizations across AWS/GCP/Azure
   during 2024), "average CPU utilization across Kubernetes clusters
   remains low at just 10%, down from 13% last year. Memory utilization is
   23% — a modest 3% increase from the previous year." The same report
   measured a 40% provisioned-vs-requested CPU gap and a 57%
   provisioned-vs-requested memory gap. With ~140 services committing
   roughly 315GB–1TB of memory at a ~600MB–1GB JVM idle floor, the org is
   paying for multiples of what it uses.

2. **VPA is the right brain, wrong hands for JVMs.** VPA's recommender
   produces decent CPU/memory targets, but its enforcement (eviction +
   recreate) is disruptive and JVM-blind. Google Cloud's GKE documentation
   states verbatim: "Vertical Pod autoscaling is not ready for use with
   JVM-based workloads due to limited visibility into actual memory usage
   of the workload" — and GKE's rightsizing-recommendations guidance adds,
   "Use extra scrutiny before applying recommendations for these types of
   workloads." The fix is to consume VPA recommendations as data, not let
   VPA mutate pods.

3. **HPA/VPA conflict on the same metric is a hard rule, not a nuance.**
   If HPA and VPA both act on CPU/memory they race. Production pattern:
   HPA on a real business metric (Kafka consumer lag, request rate, p99)
   via KEDA; VPA (recommendation-only) on the resource requests.

4. **JVM CPU sizing is a startup vs. steady-state problem.** Java needs
   far more CPU during class-loading/JIT warmup than at steady state;
   under-provisioned CPU at startup causes long boots and throttling. The
   JVM also reads CPU count and `-Xmx` once at startup, so naively raising
   a running container's memory limit does nothing for heap.

5. **Karpenter consolidation is where the largest, lowest-risk savings
   live.** Right-sizing pod requests, then letting Karpenter bin-pack and
   consolidate, is the highest-leverage move; OneUptime (Feb 2026) reports
   Karpenter consolidation "typically achieves 30-50% cost reduction
   compared to static node pools," and the freeCodeCamp "EKS Cost
   Optimization Handbook" cites up to 60% via Karpenter plus rightsizing.
   But tight PodDisruptionBudgets on JVM pods slow consolidation.

6. **A GitOps PR pipeline is the bank-grade corrective action.** AWS
   publishes a reference pattern that reads metrics, computes
   recommendations, and opens reviewed pull requests — preserving Git as
   source of truth, audit trail, and rollback.

## Details

### Problem framing

The current "Ramu" model is reactive, doesn't scale to 140 services, and
has no audit trail. The goal is a closed loop: continuously detect
over/under-provisioning, recommend corrected requests/limits, apply
changes safely (reviewed, auditable, reversible), and hold teams
accountable via dashboards/showback rather than personal outreach.

### 1. Comparison of automated right-sizing / autoscaling approaches

#### Vertical Pod Autoscaler (VPA)

Three components: Recommender (decaying-histogram of usage, ~8 days
history, plus OOM events), Updater (evicts pods when drift is large),
Admission Controller (injects requests on new pods). Modes:

- `Off` — recommendation only. **This is the production-safe default
  for JVMs.**
- `Initial` — sets requests at pod creation only.
- `Recreate` — evicts running pods to apply (disruptive; "Auto" is now
  deprecated/aliased to Recreate).
- `InPlaceOrRecreate` (alpha in VPA 1.4+, needs K8s 1.33+ in-place
  resize; in-place resize went GA in K8s 1.35, Dec 2025) — patches
  cgroups without restart where possible, falls back to evict.

**Risks for JVMs:** (a) eviction clears JIT compilation and forces cold
restart; (b) the recommender can shrink memory toward off-peak minimums
and then evict at peak; (c) even with in-place resize, raising the cgroup
memory limit does not raise JVM heap because `-Xmx` is fixed at startup —
you must set `resizePolicy: RestartContainer` for memory on JVM apps,
which reintroduces a restart. **Conclusion:** use VPA recommendations as
a data source; do not let it enforce on JVM pods.

#### Horizontal Pod Autoscaler (HPA) + KEDA

Native HPA on CPU is a lagging indicator and a poor signal for I/O-bound
or Kafka-consumer workloads (CPU stays flat while lag grows). KEDA
extends HPA with 60+ scalers and is the de-facto standard:

- **Kafka/MSK consumer lag** — scale consumers on `lagThreshold` (cap
  `maxReplicaCount` at partition count).
- **Request rate / p99 latency** — scale on Prometheus queries (RPS per
  pod) with a CPU backstop.
- **Cron/scheduled** — pre-scale for business hours, scale baseline down
  off-peak (and scale-to-zero for non-prod).

KEDA generates and manages the HPA object under the hood, so HPA and
KEDA are not separate decisions.

**Combining HPA + VPA safely.** The supported pattern: HPA on an
orthogonal metric (lag/RPS/custom), VPA in `Off` mode feeding request
recommendations into the GitOps pipeline. Never both on CPU/memory.

#### Cluster/node right-sizing — Karpenter

Karpenter watches pending pods' actual requests and provisions the
cheapest fitting instance from the full EC2 catalog, then consolidates
underutilized nodes (Empty, Multi-node, Single-node consolidation) and
supports Spot-first with On-Demand fallback. Caveats: consolidation
considers requests not limits (bursting pods on one node can OOM); tight
PDBs and `karpenter.sh/do-not-disrupt` on JVM pods reduce consolidation
effectiveness. In an explicitly adversarial 7-day benchmark (with tight
java-heap PDBs and topology-spread constraints), Cast AI's own published
results show baseline Karpenter cost of $703.08 vs. Cast AI Autoscaler
at $400.83 — "43.0% savings ($302.25 saved)" — though this is a
vendor-constructed worst case; real clusters with mostly stateless,
evenly-sized workloads perform far closer to Karpenter's best case.
Karpenter is recommended for every EKS cluster in 2026 and supersedes
Cluster Autoscaler.

#### Commercial / OSS right-sizing platforms (honest trade-offs)

- **Goldilocks** (Fairwinds, OSS, free) — wraps VPA in `Off` mode,
  auto-creates VPA objects per workload in labeled namespaces, dashboard
  shows Guaranteed (target) and Burstable (lower/upper bound)
  recommendations. Best starting point; recommendation-only, no
  automation, no JVM awareness. Pairs with the GitOps PR loop.
- **VPA + Prometheus + custom GitOps automation** (OSS) — the
  recommended core; maximum control and auditability, but you build and
  maintain the PR pipeline.
- **KubeCost / OpenCost** (OSS + commercial) — cost visibility and
  allocation (showback/chargeback), not optimization. OpenCost free;
  Kubecost Enterprise ~$50K+/yr for multi-cluster.
- **StormForge** (now part of CloudBolt/F5) — per-workload ML models,
  each "trained on 28+ days of observed usage data"; uniquely couples
  request sizing with HPA target utilization — its patented
  "bi-dimensional autoscaling solves this by treating requests and HPA
  targets as a coupled pair, preserving your intended scaling behavior
  while reducing resource waste," and a named customer (Senior Architect,
  Global Media & Telecommunications Company) reports it "effectively
  addresses the challenges of Java applications" while "seamlessly
  integrating with the HPA and Karpenter." Defaults to read-only, earns
  trust dev→test→prod; usage-based pay-as-you-go pricing via AWS
  Marketplace (billed by replica-hours). Best fit for JVM-heavy estates
  that want automation without fighting HPA.
- **CAST AI** — strongest hands-off node+pod automation, Spot,
  autoscaler replacement; CAST AI's site and AWS Marketplace listing
  claim "50 to 75% average savings" (LeanOps' 2026 comparison cites
  "50-70%... pricing starts at 15-20% of savings"). Best for fastest ROI
  if you can grant write access to production — a bigger ask for a
  regulated bank.
- **PerfectScale** — autonomous right-sizing framed around
  availability/SLA protection; per-node pricing, sales-quoted. Good if
  "must not cause an outage" is the cultural blocker.
- **Densify/Kubex, ScaleOps, Sedai, Zesty** — other
  autonomous/recommendation players; ScaleOps and Sedai run in-cluster
  real-time; Sedai is autonomous but its "black box" changes can trigger
  SRE trust concerns in regulated settings.

**Build-vs-buy verdict:** start OSS (Goldilocks/VPA + GitOps PRs +
Karpenter + Kyverno + KEDA); buy a platform (StormForge first for JVM)
only when PR-review toil exceeds license cost or when you want
continuous closed-loop sizing the platform team can't staff.

### 2. Monitoring stack and signals

**Stack:** Prometheus + Grafana, kube-state-metrics (object state,
requests/limits), cAdvisor (container CPU/memory/throttling),
metrics-server (for VPA), and Spring Boot Actuator + Micrometer
(`micrometer-registry-prometheus`) exposing `/actuator/prometheus` with
`jvm_memory_used_bytes{area="heap|nonheap"}`, `jvm_gc_pause_seconds`,
`jvm_memory_usage_after_gc_percent`, `http_server_requests_seconds`
(enable percentiles-histogram for p95/p99). Import Grafana dashboard
4701 (JVM Micrometer) and 17175 (Spring Boot 3.x). Scrape via pod
annotations `prometheus.io/scrape/port/path`.

**Key signals & example alert rules:**

**Over-provisioned (waste) — candidate for shrink:**

- Memory working set P99 « requests over 7 days:
  `quantile_over_time(0.99, container_memory_working_set_bytes[7d]) / on(...) kube_pod_container_resource_requests{resource="memory"} < 0.5`
- CPU P95 « requests:
  `quantile_over_time(0.95, rate(container_cpu_usage_seconds_total[5m])[7d:]) / kube_pod_container_resource_requests{resource="cpu"} < 0.4`
- JVM heap-after-GC persistently low
  (`jvm_memory_usage_after_gc_percent < 0.3`) → heap (and container)
  oversized.

**Under-provisioned / at risk — candidate for grow:**

- OOMKilled events:
  `(kube_pod_container_status_restarts_total - offset 10m >= 1) and min_over_time(kube_pod_container_status_last_terminated_reason{reason="OOMKilled"}[10m]) == 1`
- CPU CFS throttling ratio:
  `rate(container_cpu_cfs_throttled_periods_total[5m]) / rate(container_cpu_cfs_periods_total[5m]) > 0.25`
  (warning), `> 0.75` (critical)
- Memory working set > 90% of limit (OOM risk; memory has no graceful
  degradation — it goes 85%→95%→OOM):
  `container_memory_working_set_bytes / kube_pod_container_resource_limits{resource="memory"} > 0.9`
- JVM heap > 85% of max for 5m:
  `sum(jvm_memory_used_bytes{area="heap"}) / sum(jvm_memory_max_bytes{area="heap"}) > 0.85`
- GC pause time eating CPU:
  `rate(jvm_gc_pause_seconds_sum[5m]) > 0.1`

**SLO framing:** "Over-provisioned" = P99 working set < 50% of requests
AND zero OOM/throttle for 7 days. "Under-provisioned" = any OOMKill in
24h OR throttle ratio > 25% sustained OR heap > 85%. Note since K8s
1.34, PSI metrics (`container_pressure_cpu_waiting_seconds_total`) give
a contention signal even without CPU limits set.

### 3. Automated corrective action — the safe→aggressive spectrum

1. **Recommendation-only (safest).** Goldilocks dashboards + VPA `Off`;
   surface recommendations to teams. Zero risk, but relies on humans to
   act (the failure mode of "Ramu").
2. **Semi-automated GitOps loop (RECOMMENDED for the bank).** A
   scheduled controller reads Prometheus history, computes bounded
   recommendations, renders manifests (Helm/Kustomize) to map values back
   to the correct source files, and opens a pull request against the
   deployment repo. Argo CD/Flux reconciles after human merge. Auditable,
   reviewable, revertible (`git revert`), and fits change control. AWS's
   reference implementation ("Kubernetes right-sizing with metrics-driven
   GitOps automation," Sept 2025; companion repo
   aws-samples/K8sResourceResizer) uses three components: "Metrics-driven
   analysis" via Amazon Managed Service for Prometheus, "GitOps-based
   implementation" via ArgoCD, and "Pattern-aware optimization." Its
   recommendation engine runs outside the production cluster, offers
   configurable strategies (time-aware, trend-aware, workload-aware, and
   a "Statistical ensemble combining Quantile Regression, Moving Average,
   and Prophet strategies"), bounds recommendations with min/max
   thresholds, then uses the rendered-manifests pattern — spinning up a
   temporary local cluster with Argo CD to render full manifests and
   "accurately map the recommended resource changes back to the specific
   source files in the Git repository" — and calls Amazon Bedrock to
   generate the PR description before opening the PR via GitHub Actions
   for human review/merge. (Note: this AWS post publishes the pattern but
   no savings figures.)
3. **Fully automated (most aggressive).** VPA `InPlaceOrRecreate`, or a
   commercial platform mutating requests/limits live. Risks for a bank:
   unreviewed production change (change-control breach), JVM restart/heap-
   ceiling surprises, in-place memory shrink → OOM. Reserve for non-prod
   or a small set of stateless, restart-tolerant services.

**Making it bank-safe:** changes flow only through Git PRs (no direct
cluster mutation) → mandatory review + CODEOWNERS → CI policy checks
(Kyverno/Conftest) → progressive rollout (canary one replica, watch
OOM/throttle/p99) → automatic rollback on regression → immutable audit
trail in Git + Argo history. Bound every recommendation with
`minAllowed`/`maxAllowed`. Schedule applies during change windows.

### 4. JVM-specific guidance (explicit pitfalls)

- **Memory: size on working set + non-heap, not just heap.** Total ≈
  heap + metaspace + thread stacks + direct/NIO buffers + code cache +
  ~25% overhead. Set `-XX:MaxRAMPercentage` to 50–75% (smaller containers
  → 50–60%; larger → up to 75–80%), explicitly bound
  `-XX:MaxMetaspaceSize` and `-XX:MaxDirectMemorySize`. Keep memory
  request = limit (Guaranteed QoS) per the org's interim standard — this
  also prevents the OOM-on-burst trap during Karpenter consolidation.
- **Why naive VPA memory recs are wrong:** the JVM commits heap and
  (depending on GC) may not return it promptly; VPA sees a flat floor and
  can't see "true" need. Modern GCs (G1 with JEP 346, etc.) return unused
  memory and improve this, but the GKE/AKS "not ready for JVM" warning
  stands. Use VPA memory recs only as a sanity bound, validated against
  actual working set and load tests. (A practitioner report on
  kubernetes/autoscaler issue #5029 found that across 100 JVM pods,
  average configured memory request of 1600MB vs. VPA-recommended 1240MB
  — an average ~425MB/~24% per-pod saving — illustrating both the
  opportunity and the need to validate.)
- **CPU: protect startup, be cautious with limits.** JIT/GC thread
  counts and GC algorithm are chosen from CPU visible at startup;
  under-provisioning startup CPU causes minute-plus boots and throttling.
  Options: (a) Kube Startup CPU Boost (Google OSS controller) inflates
  CPU during startup then reverts via in-place resize — purpose-built for
  JVMs; (b) burstable CPU (limit > request) to absorb the JIT
  "compilation storm"; (c) for latency-sensitive services consider no CPU
  limit but set `-XX:ActiveProcessorCount` to stabilize thread sizing.
  The org's interim standard (CPU request < limit / Burstable) is correct
  for JVMs.
- **In-place resize ≠ free JVM scaling.** Even on K8s 1.35 GA in-place
  resize, JVM memory benefits need a container restart; CPU and non-heap
  can scale live.

### 5. Operating model — replacing "Ramu chases developers"

- **Platform team owns the pipeline** (Team Topologies "platform team").
  The platform team runs monitoring, the recommendation/PR automation,
  Karpenter, Kyverno, and KEDA as a paved road / golden path.
  Stream-aligned product teams consume it via self-service and own
  merging PRs for their services. This converts a person-dependent
  process into a product.
- **Golden-path defaults.** Ship a Helm library chart / scaffold with
  sane JVM defaults: `MaxRAMPercentage`, memory request=limit, startup
  CPU boost, Actuator/Micrometer wired, Kafka KEDA template. New services
  are right-sized by default.
- **Policy-as-code (Kyverno) guardrails.** Enforce: memory request=limit
  (`memory-requests-equal-limits`); require CPU+memory requests and
  memory limits (`require-pod-requests-limits`); cap memory limit ≤ N×
  request (`enforce-resources-as-ratio`); generate default
  LimitRange/ResourceQuota per namespace. Run Audit first, graduate to
  Enforce.
- **FinOps showback** (FinOps Foundation Inform→Optimize→Operate). Use
  Kubecost/OpenCost to allocate cost per namespace/team/service via a
  consistent label taxonomy (team/cost-center labels). Start with
  showback (information) to build trust; move to chargeback only when
  ≥80% of spend is attributable and accounting policy supports it. Drive
  accountability through dashboards (committed vs. used, cost per
  service, $ trend), embedding cost in PRs/sprint review — not personal
  outreach. The FinOps Foundation is explicit that neither showback nor
  chargeback is "more mature"; the choice "depends on your organization's
  accounting policies, not a maturity checklist."

### 6. Reference architecture & phased rollout

**Reference architecture:**

```
[Spring Boot pods] --Actuator/Micrometer--> [Prometheus / AMP] <-- cAdvisor, kube-state-metrics, metrics-server
        |                                          |
   [KEDA ScaledObjects]                      [Grafana dashboards + Alertmanager]  --> over/under-provision alerts
   (Kafka lag, RPS, cron)                          |
        |                                   [VPA (Off) / Goldilocks] -> recommendations
   [HPA managed by KEDA]                           |
        |                                   [Recommendation+PR controller] -- renders manifests, opens PR
   [Deployments] <--reconcile-- [Argo CD] <----- [Git repo] <--- reviewed/merged PR (CODEOWNERS, CI Kyverno/Conftest)
        |
   [Karpenter] -- bin-packs & consolidates nodes (Spot-first)
        |
   [Kyverno] -- admission guardrails    [Kubecost/OpenCost] -- showback per team
```

**Phase 1 — Observe (Weeks 0–6, zero enforcement).** Deploy
Prometheus/Grafana + kube-state-metrics + cAdvisor + metrics-server;
wire Actuator/Micrometer across all 140 services; install Goldilocks/VPA
in `Off`; install Kubecost/OpenCost. Deploy Kyverno in Audit. Establish
baselines: committed vs. used memory/CPU, cost per service, OOM/throttle
inventory. **Exit criteria:** dashboards live for 100% of services, ≥7
days of recommendation data.

**Phase 2 — Recommend (Weeks 6–14, human-in-loop).** Stand up the GitOps
PR pipeline (AWS pattern or in-house). Auto-open bounded PRs for the
worst over-provisioned services first; teams review/merge. Roll out KEDA
for Kafka consumers (lag) and request-driven services (RPS) — replacing
CPU-only HPA. Introduce Kube Startup CPU Boost for slow-booting
services. Switch Kyverno memory request=limit + require-limits to
Enforce in non-prod, then prod. **Exit criteria:** ≥50% of services
right-sized via merged PRs; committed memory down measurably; zero OOM
regressions from changes.

**Phase 3 — Automate safe cases (Weeks 14+, guardrailed).** Enable
Karpenter consolidation (Spot-first for non-prod and stateless prod)
once requests are trustworthy; relax over-tight PDBs to unblock
consolidation. Turn on KEDA cron scale-down off-peak / scale-to-zero for
non-prod. Allow VPA `InPlaceOrRecreate` only for stateless,
restart-tolerant, non-JVM-heap-sensitive services. Evaluate a commercial
platform (StormForge for JVM-aware request+HPA coupling) if PR toil is
high. Keep all prod request/limit changes flowing through reviewed Git.

**What to measure to prove savings:**

- Committed vs. used memory and CPU (cluster and per service) — target
  closing the gap from ~10% CPU / ~23% memory utilization toward 50–65%.
- Cost per service / per namespace (Kubecost) trend.
- Bin-packing efficiency: node count, average node CPU/memory allocation
  %, Karpenter consolidation events.
- Reliability guardrails (must not regress): OOMKill rate, CPU throttle
  ratio, p99 latency, GC pause time.
- Process metrics: % services right-sized, PR merge lead time,
  recommendation drift.

## Recommendations

1. **Adopt the OSS core now:** Prometheus/Grafana + Micrometer detection,
   Goldilocks/VPA-`Off` recommendations, a GitOps PR pipeline (Argo CD),
   Karpenter consolidation, Kyverno guardrails, KEDA on Kafka lag/RPS —
   run by the platform team with Kubecost/OpenCost showback. This
   directly replaces the Ramu process with an auditable loop.
2. **Hard rules:** never auto-evict JVM pods; never put HPA and VPA on
   the same metric; always memory request=limit for JVMs; always bound
   recommendations with min/max; all prod changes via reviewed Git PRs.
3. **JVM hygiene as golden-path defaults:** `MaxRAMPercentage` 50–75%,
   explicit metaspace/direct limits, Kube Startup CPU Boost or burstable
   CPU for startup, `ActiveProcessorCount` where no CPU limit.
4. **Sequence the savings:** right-size requests first (Phase 2), THEN
   let Karpenter consolidate (Phase 3) — consolidation on un-right-sized
   requests yields little.
5. **Buy a platform only on triggers:** evaluate StormForge (JVM-aware,
   HPA-coupled, read-only first) when PR-review toil exceeds license
   cost; consider CAST AI for aggressive node automation if production
   write-access is acceptable; PerfectScale if availability-protection is
   the cultural gate. Benchmark: if the platform team spends >1
   FTE-equivalent on manual recommendation review, or savings plateau
   above 50% committed-vs-used, buy.
6. **FinOps maturity gate:** stay in showback until ≥80% of spend is
   label-attributable, then decide chargeback per accounting policy.

## Caveats

- Vendor savings figures are vendor-reported (CAST AI "50 to 75% average
  savings"; StormForge cites 40–70%; Karpenter 30–60% per
  OneUptime/freeCodeCamp; Cast AI's 43% consolidation benchmark was an
  explicitly adversarial setup) and based on their own benchmarks/customers;
  treat as directional and validate in your estate. The AWS GitOps
  reference architecture publishes the pattern but no savings percentages.
- VPA "not ready for JVM" is an official GKE statement (echoed by AKS
  docs); modern GCs mitigate it but it remains the conservative truth —
  hence recommendation-only.
- In-place resize maturity: GA in K8s 1.35 (Dec 2025); confirm your EKS
  version before relying on `InPlaceOrRecreate`. JVM heap still won't
  grow without a container restart.
- Karpenter consolidation vs. PDBs: tight PDBs on JVM pods (common for
  HA) materially reduce consolidation savings; you must tune PDBs and
  `do-not-disrupt` annotations deliberately.
- Real-world JVM right-sizing case studies are workload-specific and
  often vendor-sourced. As corroborating evidence: Akamas reports a 49%
  cost improvement tuning a Java microservice's combined Kubernetes +
  JVM parameters (heap +20%, memory request raised to 5GB), and Sedai
  reports $3.5M / 46% Kubernetes cost reduction at Palo Alto Networks
  (general right-sizing, not strictly JVM). Your mileage will vary by
  service shape.
- This report assumes Prometheus-style metrics are genuinely available
  (stated as "likely"); if not, Phase 1 lengthens.
