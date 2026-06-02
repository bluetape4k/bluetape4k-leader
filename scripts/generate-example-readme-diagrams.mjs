#!/usr/bin/env node

import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const root = process.cwd();
const outDir = join(root, "docs/images/readme-diagrams");
mkdirSync(outDir, { recursive: true });

const palette = [
  ["#E8F3FF", "#5B8DEF"],
  ["#EAF7EF", "#58A978"],
  ["#FFF3D9", "#D6A441"],
  ["#FDECEF", "#DC6B82"],
  ["#E9F7F6", "#45A7A1"],
  ["#F1ECFF", "#8A72D6"],
  ["#F7F1E7", "#B88A44"],
  ["#EEF4E1", "#8BA84D"],
];

const architectureDiagrams = [
  {
    slug: "examples-batch-scheduler-architecture-01",
    title: "Batch Scheduler Architecture",
    subtitle: "Three scheduler instances share one Redis leader lock before running the batch job",
    width: 1280,
    height: 560,
    nodes: [
      ["trigger", "Cron trigger", ["same schedule", "all nodes"], 70, 170],
      ["node1", "BatchScheduler node-1", ["runIfLeader", "may execute"], 330, 130, 280],
      ["node2", "BatchScheduler node-2", ["runIfLeader", "skip on miss"], 330, 250, 280],
      ["node3", "BatchScheduler node-3", ["runIfLeader", "skip on miss"], 330, 370, 280],
      ["elector", "LettuceLeaderElector", ["waitTime + leaseTime", "single lock owner"], 670, 250, 280],
      ["redis", "Redis lock", ["nightly-settlement", "SET NX EX"], 990, 250],
    ],
    edges: [
      ["trigger", "node1"],
      ["trigger", "node2"],
      ["trigger", "node3"],
      ["node1", "elector"],
      ["node2", "elector"],
      ["node3", "elector"],
      ["elector", "redis"],
    ],
  },
  {
    slug: "examples-cache-warmer-architecture-01",
    title: "Cache Warmer Architecture",
    subtitle: "Every cache partition gets its own leader lock so warming can be distributed safely",
    width: 1280,
    height: 590,
    nodes: [
      ["warmerA", "Warmer node-A", ["warmAll", "sequential partitions"], 70, 150],
      ["warmerB", "Warmer node-B", ["warmAll", "skip non-leader"], 70, 300],
      ["factory", "Elector factory", ["Hazelcast/Redis/Mongo", "backend swap"], 360, 225],
      ["locks", "Partition locks", ["prefix-region-asia", "prefix-region-eu"], 650, 225],
      ["cache", "Cache partitions", ["warmFunction", "failure isolation"], 940, 225],
    ],
    edges: [
      ["warmerA", "factory"],
      ["warmerB", "factory"],
      ["factory", "locks"],
      ["locks", "cache"],
    ],
  },
  {
    slug: "examples-k8s-lease-architecture-01",
    title: "K8s Lease Architecture",
    subtitle: "Fabric8 client creates, renews, conflicts, and releases coordination.k8s.io Lease resources",
    width: 980,
    height: 760,
    nodes: [
      ["callerA", "Holder A", ["tryAcquire", "release"], 190, 150, 260],
      ["callerB", "Holder B", ["tryAcquire", "conflict while valid"], 530, 150, 260],
      ["example", "K8sLeaseLeaderElectionExample", ["leaseDuration", "clock-aware validity"], 300, 310, 380],
      ["fabric8", "Fabric8 KubernetesClient", ["get/create/update/delete", "typed Lease API"], 300, 460, 380],
      ["lease", "Kubernetes Lease", ["holderIdentity", "renewTime + duration"], 300, 610, 380],
    ],
    edges: [
      ["callerA", "example"],
      ["callerB", "example"],
      ["example", "fabric8"],
      ["fabric8", "lease"],
    ],
  },
  {
    slug: "examples-k8s-operator-architecture-01",
    title: "K8s Operator Architecture",
    subtitle: "Three Spring Boot operator pods share one Kubernetes Lease before reconciling workload",
    width: 1430,
    height: 600,
    nodes: [
      ["pod1", "Operator pod-1", ["@Scheduled tick", "candidate"], 70, 130],
      ["pod2", "Operator pod-2", ["@Scheduled tick", "standby"], 70, 280],
      ["pod3", "Operator pod-3", ["@Scheduled tick", "standby"], 70, 430],
      ["controller", "OperatorController", ["runIfLeader", "cronjob-reconciler"], 360, 280, 260],
      ["elector", "KubernetesLeaseLeaderElector", ["autoExtend", "retryDelay"], 700, 280, 370],
      ["lease", "Kubernetes Lease", ["coordination.k8s.io", "RBAC protected"], 1130, 180],
      ["workload", "Demo workload", ["reconcile", "revision counter"], 1130, 380],
    ],
    edges: [
      ["pod1", "controller"],
      ["pod2", "controller"],
      ["pod3", "controller"],
      ["controller", "elector"],
      ["elector", "lease"],
      ["controller", "workload", "M490 372 L490 426 L1130 426"],
    ],
  },
  {
    slug: "examples-ktor-app-architecture-01",
    title: "Ktor App Architecture",
    subtitle: "Ktor exposes stats routes while a leader-scheduled background job aggregates once per cycle",
    width: 1320,
    height: 600,
    nodes: [
      ["client", "HTTP client", ["GET /stats", "GET /health"], 70, 220],
      ["ktor", "Ktor CIO app", ["ContentNegotiation", "routing"], 340, 220],
      ["routes", "Stats routes", ["currentState", "health"], 640, 130],
      ["plugin", "LeaderElectionPlugin", ["SuspendLeaderElector", "leaderScheduled"], 640, 310, 250],
      ["aggregator", "StatsAggregator", ["runCount", "lastRunAt"], 940, 130],
      ["redis", "Redis lock", ["hourly-stats-aggregation", "minLeaseTime"], 940, 310],
    ],
    edges: [
      ["client", "ktor"],
      ["ktor", "routes"],
      ["routes", "aggregator"],
      ["ktor", "plugin"],
      ["plugin", "aggregator"],
      ["plugin", "redis"],
    ],
  },
  {
    slug: "examples-migration-gate-architecture-01",
    title: "Migration Gate Architecture",
    subtitle: "Rolling deploy pods check migration markers before and after the Exposed JDBC leader lock",
    width: 1340,
    height: 600,
    nodes: [
      ["podA", "Deploy pod-A", ["startup migration", "candidate"], 70, 170],
      ["podB", "Deploy pod-B", ["startup migration", "candidate"], 70, 340],
      ["gate", "MigrationGate", ["precheck", "in-lock recheck"], 390, 250],
      ["elector", "ExposedJdbcLeaderElector", ["lockOwner=nodeId", "no auto-extend"], 700, 250, 310],
      ["lockTable", "Leader lock table", ["leaseTime", "single owner"], 1060, 170],
      ["marker", "Schema marker", ["isApplied", "idempotent migration"], 1060, 340],
    ],
    edges: [
      ["podA", "gate"],
      ["podB", "gate"],
      ["gate", "elector"],
      ["elector", "lockTable"],
      ["gate", "marker", "M505 342 L505 386 L1060 386"],
      ["elector", "marker"],
    ],
  },
  {
    slug: "examples-rate-limiter-architecture-01",
    title: "Rate Limiter Architecture",
    subtitle: "Leader election schedules work once; all workers share one Redis-backed Bucket4j quota",
    width: 1390,
    height: 620,
    nodes: [
      ["node1", "Node-1", ["dispatch + worker", "candidate"], 70, 140],
      ["node2", "Node-2", ["dispatch + worker", "candidate"], 70, 300],
      ["node3", "Node-3", ["dispatch + worker", "candidate"], 70, 460],
      ["scheduler", "LeaderDispatchScheduler", ["schedule work once", "REJECTED losers"], 360, 220, 300],
      ["lock", "Redis leader lock", ["rate-limiter:*", "single scheduler"], 730, 220],
      ["limiter", "DistributedSuspendRateLimiter", ["Bucket4j quota", "10 calls/sec"], 700, 400, 340],
      ["api", "ExternalApiProbe", ["called only after token", "global ceiling"], 1090, 310],
    ],
    edges: [
      ["node1", "scheduler"],
      ["node2", "scheduler"],
      ["node3", "scheduler"],
      ["scheduler", "lock"],
      ["scheduler", "limiter"],
      ["limiter", "api"],
    ],
  },
  {
    slug: "examples-tenant-aggregator-architecture-01",
    title: "Tenant Aggregator Architecture",
    subtitle: "Each tenant has a long-running coroutine and an independent leader lock",
    width: 1320,
    height: 610,
    nodes: [
      ["appA", "App instance A", ["start(scope)", "tenant loops"], 70, 170],
      ["appB", "App instance B", ["start(scope)", "standby loops"], 70, 350],
      ["aggregator", "TenantAggregator", ["supervisorScope", "stopGracefully"], 380, 260],
      ["locks", "Tenant locks", ["prefix-tenant-A", "prefix-tenant-B"], 700, 260],
      ["r2dbc", "Exposed R2DBC elector", ["created once per tenant", "reused per cycle"], 990, 170, 270],
      ["metrics", "Tenant metrics service", ["aggregateFunction", "exception isolation"], 990, 350, 270],
    ],
    edges: [
      ["appA", "aggregator"],
      ["appB", "aggregator"],
      ["aggregator", "locks"],
      ["locks", "r2dbc"],
      ["locks", "metrics"],
    ],
  },
  {
    slug: "examples-webhook-poller-architecture-01",
    title: "Webhook Poller Architecture",
    subtitle: "One elected poller claims MongoDB events atomically and processes each claim at least once",
    width: 1340,
    height: 630,
    nodes: [
      ["pollerA", "Poller node-A", ["runLoop", "candidate"], 70, 150],
      ["pollerB", "Poller node-B", ["runLoop", "standby"], 70, 320],
      ["elector", "SuspendLeaderElector", ["lockName", "handoff on death"], 360, 235, 270],
      ["claim", "claimNext", ["findOneAndUpdate", "attempts +1"], 690, 150],
      ["handler", "handler(event)", ["markDone", "requeue or FAILED"], 690, 320],
      ["mongo", "Mongo events", ["PENDING/CLAIMED/DONE", "claimExpiresAt index"], 1000, 235],
    ],
    edges: [
      ["pollerA", "elector"],
      ["pollerB", "elector"],
      ["elector", "claim"],
      ["claim", "handler"],
      ["claim", "mongo"],
      ["handler", "mongo"],
    ],
  },
];

const sequenceDiagrams = [
  {
    slug: "examples-k8s-lease-sequence-01",
    title: "K8s Lease Sequence Flow",
    subtitle: "Lease creation, conflict, release, and reacquire behavior from the Fabric8 example",
    participants: ["Holder A", "Example", "Kubernetes API", "Lease", "Holder B"],
    messages: [
      [0, 1, "tryAcquire(app-lock, A)", "line"],
      [1, 2, "GET Lease", "line"],
      [2, 1, "not found", "dashed"],
      [1, 2, "CREATE Lease holder=A", "line"],
      [2, 3, "store holderIdentity=A", "line"],
      [4, 1, "tryAcquire(app-lock, B)", "line"],
      [1, 2, "GET Lease", "line"],
      [2, 1, "valid holder=A", "dashed"],
      [1, 4, "CONFLICT", "dashed"],
      [0, 1, "release(app-lock, A)", "line"],
      [1, 2, "UPDATE holder=null", "line"],
      [4, 1, "tryAcquire(app-lock, B)", "line"],
      [1, 2, "UPDATE holder=B", "line"],
      [1, 4, "ACQUIRED", "dashed"],
    ],
  },
  {
    slug: "examples-k8s-operator-sequence-01",
    title: "K8s Operator Sequence Flow",
    subtitle: "Only the pod holding the Kubernetes Lease reconciles the demo workload on a scheduled tick",
    participants: ["Scheduler", "Pod A", "Pod B", "Lease", "Workload"],
    messages: [
      [0, 1, "tick #42", "line"],
      [0, 2, "tick #42", "line"],
      [1, 3, "runIfLeader(cronjob-reconciler)", "line"],
      [2, 3, "runIfLeader(cronjob-reconciler)", "line"],
      [3, 1, "ACQUIRED", "dashed"],
      [3, 2, "null / standby", "dashed"],
      [1, 4, "reconcile(request)", "line"],
      [4, 1, "OperatorReconcileResult", "dashed"],
      [1, 3, "auto-extend while active", "line"],
    ],
  },
  {
    slug: "examples-rate-limiter-sequence-01",
    title: "Rate Limiter Sequence Flow",
    subtitle: "One leader schedules work; workers call the external API only after quota consumption",
    participants: ["Nodes", "Leader scheduler", "Redis lock", "Bucket4j quota", "External API"],
    messages: [
      [0, 1, "schedule attempt from 3 nodes", "line"],
      [1, 2, "runIfLeader(rate-limiter:*)", "line"],
      [2, 1, "one SCHEDULED, losers REJECTED", "dashed"],
      [1, 0, "work item list", "dashed"],
      [0, 3, "consume(quotaKey, 1)", "line"],
      [3, 0, "CONSUMED or REJECTED", "dashed"],
      [0, 4, "call(item) only if CONSUMED", "line"],
      [4, 0, "sequence number", "dashed"],
      [0, 3, "repeat per second window", "line"],
    ],
  },
];

function xml(value) {
  return value.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

function defs() {
  return `<defs>
  <filter id="shadow" x="-8%" y="-8%" width="116%" height="116%"><feDropShadow dx="0" dy="8" stdDeviation="9" flood-color="#1f2937" flood-opacity="0.10"/></filter>
  <marker id="openArrow" markerWidth="12" markerHeight="10" refX="10" refY="5" orient="auto" markerUnits="strokeWidth"><path d="M 1 1 L 10 5 L 1 9" fill="none" stroke="#758297" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></marker>
  <style>
    .canvas{fill:#f7f9fc}.frame{fill:#fff;stroke:#d8e0ea;stroke-width:1.5}
    .title{font-family:"Architects Daughter","Comic Mono","Comic Sans MS","Comic Sans",cursive;font-size:43px;font-weight:400;fill:#102033}
    .subtitle{font-family:"Comic Mono","Comic Sans MS","Comic Sans","Comic Neue",Arial,sans-serif;font-size:15px;font-weight:400;fill:#536273}
    .smallLabel{font-family:"Architects Daughter","Comic Mono","Comic Sans MS","Comic Sans",cursive;font-size:21px;font-weight:400;fill:#102033}
    .mono{font-family:"Comic Mono","Comic Sans MS","Comic Sans","Comic Neue",Arial,sans-serif;font-size:12px;font-weight:400;fill:#102033}
    .strong{font-family:"Comic Mono","Comic Sans MS","Comic Sans","Comic Neue",Arial,sans-serif;font-size:13px;font-weight:400;fill:#102033}
    .card{stroke-width:2.1;filter:url(#shadow)}
    .line{stroke:#758297;stroke-width:2.1;fill:none;marker-end:url(#openArrow)}
    .dashed{stroke:#758297;stroke-width:2.1;stroke-dasharray:7 6;fill:none;marker-end:url(#openArrow)}
    .lifeline{stroke:#b7c2d1;stroke-width:2;stroke-dasharray:10 10}
  </style>
</defs>`;
}

function card(node, index) {
  const [id, title, details, x, y] = node;
  const [fill, stroke] = palette[index % palette.length];
  const width = node[5] ?? 230;
  const height = 92;
  const centerX = x + width / 2;
  const centerY = y + height / 2;
  const shownDetails = details.slice(0, 2);
  const lineYs = shownDetails.length === 0
    ? [46]
    : shownDetails.length === 1
      ? [35, 63]
      : [28, 53, 70];
  const lines = [
    `<g id="${id}" transform="translate(${x},${y})">`,
    `  <rect class="card" x="0" y="0" width="${width}" height="${height}" rx="14" fill="${fill}" stroke="${stroke}"/>`,
    `  <text class="smallLabel" x="${width / 2}" y="${lineYs[0]}" text-anchor="middle">${xml(title)}</text>`,
    ...shownDetails.map((detail, detailIndex) =>
      `  <text class="mono" x="${width / 2}" y="${lineYs[detailIndex + 1]}" text-anchor="middle">${xml(detail)}</text>`,
    ),
    `</g>`,
  ];
  return { id, x, y, width, height, centerX, centerY, svg: lines.join("\n") };
}

function edgePath(from, to) {
  const dx = Math.abs(from.centerX - to.centerX);
  const dy = Math.abs(from.centerY - to.centerY);
  if (dy > dx) {
    const startX = from.centerX;
    const endX = to.centerX;
    const startY = from.centerY <= to.centerY ? from.y + from.height : from.y;
    const endY = from.centerY <= to.centerY ? to.y : to.y + to.height;
    const midY = (startY + endY) / 2;
    return `<path class="line" d="M${startX} ${startY} L${startX} ${midY} L${endX} ${midY} L${endX} ${endY}"/>`;
  }
  const startX = from.centerX <= to.centerX ? from.x + from.width : from.x;
  const endX = from.centerX <= to.centerX ? to.x : to.x + to.width;
  const startY = from.centerY;
  const endY = to.centerY;
  const midX = (startX + endX) / 2;
  return `<path class="line" d="M${startX} ${startY} L${midX} ${startY} L${midX} ${endY} L${endX} ${endY}"/>`;
}

function architectureSvg(diagram) {
  const cards = diagram.nodes.map(card);
  const byId = new Map(cards.map((item) => [item.id, item]));
  const routes = diagram.edges.map(([from, to, route]) =>
    route ? `<path class="line" d="${route}"/>` : edgePath(byId.get(from), byId.get(to)),
  );
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${diagram.width}" height="${diagram.height}" viewBox="0 0 ${diagram.width} ${diagram.height}" role="img" aria-label="${xml(diagram.title)}">
${defs()}
<rect class="canvas" width="${diagram.width}" height="${diagram.height}"/>
<rect class="frame" x="32" y="28" width="${diagram.width - 64}" height="${diagram.height - 56}" rx="28"/>
<text class="title" x="66" y="82">${xml(diagram.title)}</text>
<text class="subtitle" x="70" y="118">${xml(diagram.subtitle)}</text>
${routes.join("\n")}
${cards.map((item) => item.svg).join("\n")}
</svg>
`;
}

function sequenceSvg(diagram) {
  const width = 1540;
  const top = 166;
  const headerW = 210;
  const headerH = 72;
  const laneStart = 110;
  const laneGap = (width - laneStart * 2 - headerW) / (diagram.participants.length - 1);
  const centers = diagram.participants.map((_, index) => laneStart + headerW / 2 + index * laneGap);
  const messageYStart = 310;
  const messageGap = 104;
  const height = messageYStart + diagram.messages.length * messageGap + 170;
  const participantSvg = diagram.participants.map((participant, index) => {
    const x = centers[index] - headerW / 2;
    const [fill, stroke] = palette[index % palette.length];
    return `<rect class="card" x="${x}" y="${top}" width="${headerW}" height="${headerH}" rx="12" fill="${fill}" stroke="${stroke}"/>
<text class="smallLabel" x="${centers[index]}" y="${top + 43}" text-anchor="middle">${xml(participant)}</text>
<line class="lifeline" x1="${centers[index]}" y1="${top + 86}" x2="${centers[index]}" y2="${height - 60}"/>`;
  });
  const messages = diagram.messages.map(([from, to, label, type], index) => {
    const y = messageYStart + index * messageGap;
    const x1 = centers[from];
    const x2 = centers[to];
    const labelX = Math.min(x1, x2) + Math.abs(x2 - x1) / 2 - 145;
    const labelW = Math.max(250, Math.min(360, label.length * 8 + 52));
    return `<rect x="${labelX.toFixed(1)}" y="${y - 46}" width="${labelW}" height="40" rx="20" fill="#ffffff" opacity="0.97"/>
<text class="strong" x="${(labelX + 18).toFixed(1)}" y="${y - 19}" text-anchor="start">${index + 1}. ${xml(label)}</text>
<path class="${type}" d="M${x1.toFixed(1)} ${y} L${x2.toFixed(1)} ${y}"/>`;
  });
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" role="img" aria-label="${xml(diagram.title)}">
${defs()}
<rect class="canvas" width="${width}" height="${height}"/>
<rect class="frame" x="32" y="28" width="${width - 64}" height="${height - 56}" rx="28"/>
<text class="title" x="66" y="82">${xml(diagram.title)}</text>
<text class="subtitle" x="70" y="118">${xml(diagram.subtitle)}</text>
${participantSvg.join("\n")}
${messages.join("\n")}
</svg>
`;
}

architectureDiagrams.push(
  {
    slug: "examples-consul-maintenance-architecture-01",
    title: "Consul Maintenance Architecture",
    subtitle: "One service instance owns the Consul Session and KV lock before running drain work",
    width: 1360,
    height: 610,
    nodes: [
      ["nodeA", "Service instance A", ["maintenance tick", "candidate"], 70, 150],
      ["nodeB", "Service instance B", ["same lock", "skip on miss"], 70, 340],
      ["coordinator", "ServiceMaintenanceCoordinator", ["performMaintenance", "release after body"], 360, 250, 350],
      ["elector", "ConsulLeaderElector", ["Session + KV", "lease ownership"], 750, 250, 270],
      ["consul", "Consul agent", ["session lifecycle", "KV lock value"], 1070, 150, 260],
      ["drain", "Maintenance steps", ["drain instance", "rotate endpoint"], 1070, 340, 260],
    ],
    edges: [
      ["nodeA", "coordinator"],
      ["nodeB", "coordinator"],
      ["coordinator", "elector"],
      ["elector", "consul"],
      ["coordinator", "drain"],
    ],
  },
  {
    slug: "examples-etcd-reconciler-architecture-01",
    title: "Etcd Reconciler Architecture",
    subtitle: "Only the control-plane node holding the etcd lease applies desired resources",
    width: 1360,
    height: 610,
    nodes: [
      ["nodeA", "Control-plane A", ["reconcile tick", "candidate"], 70, 150],
      ["nodeB", "Control-plane B", ["reconcile tick", "standby"], 70, 340],
      ["reconciler", "ClusterReconciler", ["runIfLeader", "desired state loop"], 380, 250, 300],
      ["elector", "EtcdLeaderElector", ["lease grant", "key compare"], 720, 250, 270],
      ["etcd", "etcd cluster", ["lease + key", "single owner"], 1040, 150, 260],
      ["resources", "Cluster resources", ["apply changes", "idempotent"], 1040, 340, 260],
    ],
    edges: [
      ["nodeA", "reconciler"],
      ["nodeB", "reconciler"],
      ["reconciler", "elector"],
      ["elector", "etcd"],
      ["reconciler", "resources"],
    ],
  },
  {
    slug: "examples-redisson-watchdog-architecture-01",
    title: "Redisson Watchdog Architecture",
    subtitle: "Redisson lock ownership and bluetape4k auto-extension protect a long-running leader job",
    width: 1400,
    height: 630,
    nodes: [
      ["nodeA", "Worker node A", ["long job", "candidate"], 70, 140],
      ["nodeB", "Worker node B", ["long job", "skip while locked"], 70, 310],
      ["runner", "Watchdog job runner", ["runIfLeader", "autoExtend=true"], 390, 225, 300],
      ["elector", "RedissonLeaderElector", ["RLock", "lease watchdog"], 740, 225, 300],
      ["redis", "Redis lock storage", ["Redisson lock key", "owned by node A"], 1090, 140, 270],
      ["extender", "Lease extender", ["refresh before expiry", "stop on release"], 1090, 310, 270],
      ["job", "Long-running work", ["body executes once", "release on exit"], 740, 440, 300],
    ],
    edges: [
      ["nodeA", "runner"],
      ["nodeB", "runner"],
      ["runner", "elector"],
      ["elector", "redis"],
      ["elector", "extender"],
      ["runner", "job"],
      ["extender", "redis"],
    ],
  },
  {
    slug: "examples-strategic-election-architecture-01",
    title: "Strategic Election Architecture",
    subtitle: "Candidates are scored first; the highest-ranked eligible node receives the local leadership slot",
    width: 1390,
    height: 620,
    nodes: [
      ["nodeA", "Service node A", ["readiness", "success rate"], 70, 130],
      ["nodeB", "Service node B", ["idle time", "load"], 70, 300],
      ["nodeC", "Service node C", ["candidate", "weighted score"], 70, 470],
      ["scorer", "WeightedScorer", ["CandidateScorer", "normalized weights"], 390, 300, 300],
      ["strategy", "ScoredElectionStrategy", ["rank candidates", "choose winner"], 740, 220, 290],
      ["elector", "LocalStrategicLeaderElector", ["single winner", "losers skip"], 1050, 220, 340],
      ["work", "Leader-only work", ["execute on winner", "observe score"], 1050, 400, 340],
    ],
    edges: [
      ["nodeA", "scorer"],
      ["nodeB", "scorer"],
      ["nodeC", "scorer"],
      ["scorer", "strategy"],
      ["strategy", "elector"],
      ["elector", "work"],
    ],
  },
  {
    slug: "examples-virtual-thread-runner-architecture-01",
    title: "Virtual Thread Runner Architecture",
    subtitle: "A local virtual-thread elector runs blocking leader work without pinning platform threads",
    width: 1360,
    height: 610,
    nodes: [
      ["nodeA", "Service node A", ["try run", "candidate"], 70, 150],
      ["nodeB", "Service node B", ["try run", "skip on miss"], 70, 340],
      ["elector", "VirtualThreadLeaderElector", ["runIfLeader", "lease options"], 390, 250, 320],
      ["local", "Local leader backend", ["in-JVM lock", "single owner"], 760, 250, 270],
      ["thread", "Virtual thread", ["blocking body", "bounded lifetime"], 1080, 150, 260],
      ["task", "Maintenance task", ["execute once", "release lock"], 1080, 340, 260],
    ],
    edges: [
      ["nodeA", "elector"],
      ["nodeB", "elector"],
      ["elector", "local"],
      ["elector", "thread"],
      ["thread", "task"],
      ["task", "local"],
    ],
  },
);

sequenceDiagrams.push(
  {
    slug: "examples-consul-maintenance-sequence-01",
    title: "Consul Maintenance Sequence Flow",
    subtitle: "Consul Session and KV ownership decide which service instance performs drain work",
    participants: ["Node A", "Coordinator", "Consul", "KV lock", "Node B"],
    messages: [
      [0, 1, "performMaintenance()", "line"],
      [1, 2, "create Session", "line"],
      [1, 3, "acquire KV lock", "line"],
      [3, 1, "ACQUIRED", "dashed"],
      [4, 1, "performMaintenance()", "line"],
      [1, 4, "null / skip", "dashed"],
      [1, 0, "run drain steps", "dashed"],
      [1, 3, "release KV lock", "line"],
      [4, 1, "reacquire later", "line"],
    ],
  },
  {
    slug: "examples-etcd-reconciler-sequence-01",
    title: "Etcd Reconciler Sequence Flow",
    subtitle: "Only the node holding the etcd lease applies the desired cluster state",
    participants: ["Node A", "Reconciler", "Etcd", "Resources", "Node B"],
    messages: [
      [0, 1, "scheduled reconcile", "line"],
      [1, 2, "grant lease + put key", "line"],
      [2, 1, "ACQUIRED", "dashed"],
      [4, 1, "scheduled reconcile", "line"],
      [1, 2, "compare lock key", "line"],
      [2, 1, "held by node A", "dashed"],
      [1, 3, "apply desired resources", "line"],
      [3, 1, "reconcile result", "dashed"],
      [1, 2, "revoke lease on exit", "line"],
    ],
  },
  {
    slug: "examples-redisson-watchdog-sequence-01",
    title: "Redisson Watchdog Sequence Flow",
    subtitle: "A long-running leader body keeps its Redis lock alive until release",
    participants: ["Node A", "Runner", "Redisson", "Redis lock", "Node B"],
    messages: [
      [0, 1, "start long job", "line"],
      [1, 2, "tryLock(lockName)", "line"],
      [2, 3, "set owner + lease", "line"],
      [2, 1, "ACQUIRED", "dashed"],
      [4, 1, "start same job", "line"],
      [1, 4, "null / skip", "dashed"],
      [1, 2, "auto-extend lease", "line"],
      [1, 0, "execute body", "dashed"],
      [1, 2, "unlock on completion", "line"],
    ],
  },
  {
    slug: "examples-strategic-election-sequence-01",
    title: "Strategic Election Sequence Flow",
    subtitle: "Candidate scores are evaluated before the local elector grants one leadership slot",
    participants: ["Trigger", "Strategic elector", "Scorer", "Candidates", "Winner"],
    messages: [
      [0, 1, "runIfBestCandidate()", "line"],
      [1, 3, "load candidate state", "line"],
      [3, 1, "readiness + metrics", "dashed"],
      [1, 2, "score candidates", "line"],
      [2, 1, "ranked scores", "dashed"],
      [1, 4, "grant leadership", "line"],
      [4, 1, "execute body", "dashed"],
      [1, 3, "others skip", "dashed"],
    ],
  },
  {
    slug: "examples-virtual-thread-runner-sequence-01",
    title: "Virtual Thread Runner Sequence Flow",
    subtitle: "The elected node runs blocking work on a virtual thread and releases the local lock",
    participants: ["Node A", "Elector", "Local lock", "Virtual thread", "Node B"],
    messages: [
      [0, 1, "runIfLeader()", "line"],
      [1, 2, "tryAcquire(local-lock)", "line"],
      [2, 1, "ACQUIRED", "dashed"],
      [1, 3, "start virtual thread", "line"],
      [4, 1, "runIfLeader()", "line"],
      [1, 4, "null / skip", "dashed"],
      [3, 0, "execute blocking task", "dashed"],
      [3, 1, "complete", "dashed"],
      [1, 2, "release local lock", "line"],
    ],
  },
  {
    slug: "examples-prometheus-dashboard-sequence-01",
    title: "Prometheus Dashboard Sequence Flow",
    subtitle: "The proxied leader job records Micrometer metrics before Prometheus and Grafana read them",
    participants: ["Scheduler", "Job bean", "Redis lock", "Micrometer", "Prometheus", "Grafana"],
    messages: [
      [0, 1, "fixed-delay tick", "line"],
      [1, 2, "runIfLeader(dashboard-job)", "line"],
      [2, 1, "ACQUIRED or skip", "dashed"],
      [1, 3, "record leader_aop_*", "line"],
      [4, 1, "scrape /actuator/prometheus", "line"],
      [1, 4, "metrics text", "dashed"],
      [5, 4, "query dashboard panels", "line"],
      [4, 5, "time series", "dashed"],
    ],
  },
);

for (const diagram of architectureDiagrams) {
  writeFileSync(join(outDir, `${diagram.slug}.svg`), architectureSvg(diagram));
}

for (const diagram of sequenceDiagrams) {
  writeFileSync(join(outDir, `${diagram.slug}.svg`), sequenceSvg(diagram));
}

console.log(`generated architecture=${architectureDiagrams.length} sequence=${sequenceDiagrams.length}`);
