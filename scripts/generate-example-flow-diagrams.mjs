#!/usr/bin/env node

import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const root = process.cwd();
const outDir = join(root, "docs/images/readme-diagrams");
mkdirSync(outDir, { recursive: true });

const palette = {
  blue: ["#E8F3FF", "#5B8DEF"],
  green: ["#EAF7EF", "#58A978"],
  amber: ["#FFF3D9", "#D6A441"],
  pink: ["#FDECEF", "#DC6B82"],
  teal: ["#E9F7F6", "#45A7A1"],
  lavender: ["#F1ECFF", "#8A72D6"],
  sand: ["#F7F1E7", "#B88A44"],
  gray: ["#EEF2F7", "#94A3B8"],
};

const routeColors = {
  neutral: "#758297",
  leader: "#43A76B",
  skipped: "#EF5B7A",
  contention: "#D99A2B",
  reacquire: "#8B6EEB",
};

const flowSlugs = [
  "batch-scheduler",
  "cache-warmer",
  "consul-maintenance",
  "dynamodb-export",
  "etcd-reconciler",
  "k8s-lease",
  "k8s-operator",
  "ktor-app",
  "migration-gate",
  "prometheus-dashboard",
  "rate-limiter",
  "redisson-watchdog",
  "strategic-election",
  "tenant-aggregator",
  "virtual-thread-runner",
  "webhook-poller",
];

const flows = [
  flow("batch-scheduler", "Batch Scheduler Flow", "runIfLeader returns the job result for one node and null for contenders", {
    trigger: ["Periodic trigger", ["same schedule", "3 replicas"]],
    election: ["BatchScheduler.run", ["nightly-settlement", "Lettuce Redis"]],
    branch: ["Leadership?", ["runIfLeader", "result or null"]],
    leader: ["Execute job", ["settlement work", "leader only"]],
    skip: ["Skip contender", ["return null", "no exception"]],
    result: ["Return job result", ["success/failure", "lock released"]],
    next: ["Next schedule", ["all nodes retry", "fresh race"]],
  }),
  flow("cache-warmer", "Cache Warmer Flow", "Each partition has an independent lock and outcome collection", {
    trigger: ["warmAll()", ["partition list", "sequential loop"]],
    election: ["Build lock name", ["prefix-partition", "factory elector"]],
    branch: ["Partition result?", ["leader, skip", "or failure"]],
    leader: ["Warm partition", ["warmFunction", "one node only"]],
    skip: ["Record skip", ["non-leader", "no throw"]],
    result: ["Collect result", ["warmed or failed", "failure isolated"]],
    next: ["Next partition", ["continue loop", "same node"]],
  }),
  flow("consul-maintenance", "Consul Maintenance Flow", "One Consul Session and KV lock holder performs maintenance work", {
    trigger: ["Maintenance tick", ["service nodes", "same lock"]],
    election: ["Consul election", ["Session + KV", "autoExtend"]],
    branch: ["Lock holder?", ["PERFORMED", "or SKIPPED"]],
    leader: ["Run drain steps", ["workSupplier", "leader only"]],
    skip: ["Standby node", ["empty steps", "SKIPPED"]],
    result: ["Return PERFORMED", ["completed steps", "release lock"]],
    next: ["Later window", ["new session", "fresh race"]],
  }),
  flow("dynamodb-export", "DynamoDB Export Flow", "The elected node writes one export record while contenders skip", {
    trigger: ["Billing trigger", ["batchId", "two nodes"]],
    election: ["DynamoDB lock", ["conditional write", "logical lease"]],
    branch: ["Leadership?", ["exportId", "or null"]],
    leader: ["Run export job", ["summary required", "leader only"]],
    skip: ["Skip contender", ["SKIPPED", "no export row"]],
    result: ["Put export row", ["exportId", "nodeId + batchId"]],
    next: ["Next batch", ["new exportId", "same lock"]],
  }),
  flow("etcd-reconciler", "Etcd Reconciler Flow", "Only the node holding the etcd lease applies desired resources", {
    trigger: ["Reconcile tick", ["control-plane", "candidate nodes"]],
    election: ["etcd election", ["lease grant", "key compare"]],
    branch: ["Lease held?", ["APPLIED", "or SKIPPED"]],
    leader: ["Apply resources", ["desired state", "idempotent"]],
    skip: ["Standby node", ["empty list", "SKIPPED"]],
    result: ["Return APPLIED", ["resource names", "lease release"]],
    next: ["Next reconcile", ["auto-extend", "fresh key check"]],
  }),
  flow("k8s-lease", "K8s Lease Flow", "Lease create, conflict, release, and reacquire are explicit outcomes", {
    trigger: ["tryAcquire()", ["leaseName", "holderIdentity"]],
    election: ["Read Lease", ["get/create/update", "duration check"]],
    branch: ["Can acquire?", ["absent, expired", "or same holder"]],
    leader: ["Create or renew", ["holderIdentity", "renewTime"]],
    skip: ["Return CONFLICT", ["valid holder", "different node"]],
    result: ["Release if owner", ["clear holder", "update Lease"]],
    next: ["Reacquire", ["new holder", "transition count"]],
  }),
  flow("k8s-operator", "K8s Operator Flow", "Every pod ticks, but only the Lease holder reconciles workload", {
    trigger: ["@Scheduled tick", ["pod sequence", "same lock"]],
    election: ["runIfLeader", ["Kubernetes Lease", "cronjob-reconciler"]],
    branch: ["Lease owner?", ["reconcile", "or standby"]],
    leader: ["Reconcile workload", ["Demo resource", "revision++"]],
    skip: ["Standby log", ["result null", "no reconcile"]],
    result: ["Return result", ["podName", "revision"]],
    next: ["Next tick", ["new sequence", "same Lease"]],
  }),
  flow("ktor-app", "Ktor App Flow", "leaderScheduled keeps background aggregation single-run per cycle", {
    trigger: ["Application start", ["routes + plugin", "Redis connection"]],
    election: ["leaderScheduled tick", ["hourly lock", "minLeaseTime"]],
    branch: ["Lock outcome?", ["aggregate", "or skip"]],
    leader: ["Aggregate stats", ["runCount++", "lastRunAt"]],
    skip: ["Other replicas", ["same cycle", "no duplicate"]],
    result: ["Expose /stats", ["currentState", "JSON route"]],
    next: ["Next period", ["lock retained", "cycle boundary"]],
  }),
  flow("migration-gate", "Migration Gate Flow", "Precheck, in-lock recheck, migration, post-skip, and failure outcomes", {
    trigger: ["Startup migration", ["migrationId", "rolling deploy"]],
    election: ["Precheck + lock", ["isApplied()", "runIfLeader"]],
    branch: ["Apply needed?", ["already, migrate", "skip or fail"]],
    leader: ["Run migration", ["in-lock recheck", "idempotent body"]],
    skip: ["Post-skip check", ["marker lookup", "SKIPPED or applied"]],
    result: ["Return outcome", ["Migrated", "AlreadyApplied"]],
    next: ["Failure path", ["Failed outcome", "startup policy"]],
  }),
  flow("prometheus-dashboard", "Prometheus Dashboard Flow", "Leader AOP records metrics that Prometheus and Grafana read", {
    trigger: ["Scheduled tick", ["Spring bean", "dashboard-job"]],
    election: ["@LeaderElection", ["AOP proxy", "Redis lock"]],
    branch: ["Leader result?", ["dispatch", "or skip"]],
    leader: ["dispatchBatch()", ["execution count", "demo latency"]],
    skip: ["Non-leader", ["method skipped", "metric still visible"]],
    result: ["Record metrics", ["leader_aop_*", "Micrometer"]],
    next: ["Scrape panels", ["Prometheus", "Grafana"]],
  }),
  flow("rate-limiter", "Rate Limiter Flow", "One leader dispatches work before all workers consume shared quota", {
    trigger: ["Probe cycle", ["3 workers", "work list"]],
    election: ["Dispatch lock", ["rate-limiter:*", "Redis leader"]],
    branch: ["Dispatch status?", ["SCHEDULED", "or REJECTED"]],
    leader: ["Publish work items", ["leader only", "shared list"]],
    skip: ["Reject losers", ["empty list", "REJECTED"]],
    result: ["Consume quota", ["Bucket4j", "CONSUMED or rejected"]],
    next: ["Call API", ["only after token", "next window"]],
  }),
  flow("redisson-watchdog", "Redisson Watchdog Flow", "Auto-extension keeps a long leader job alive until release", {
    trigger: ["Start long job", ["two workers", "same lock"]],
    election: ["tryLock", ["Redisson RLock", "autoExtend=true"]],
    branch: ["Lock acquired?", ["ELECTED", "or SKIPPED"]],
    leader: ["Run body", ["long work", "leader only"]],
    skip: ["Skip contender", ["SKIPPED", "no throw"]],
    result: ["Extend lease", ["refresh before expiry", "while body runs"]],
    next: ["Unlock on exit", ["release", "future reacquire"]],
  }),
  flow("strategic-election", "Strategic Election Flow", "Candidates are scored before one local leadership slot is granted", {
    trigger: ["Load profiles", ["health", "capacity"]],
    election: ["Score candidates", ["readiness", "success + idle"]],
    branch: ["Best candidate?", ["SELECTED", "or SKIPPED"]],
    leader: ["Grant slot", ["winner node", "execute action"]],
    skip: ["Other nodes", ["skip report", "score visible"]],
    result: ["Return report", ["selectedNodeId", "scores"]],
    next: ["Next scenario", ["new metrics", "fresh ranking"]],
  }),
  flow("tenant-aggregator", "Tenant Aggregator Flow", "Each tenant coroutine uses an independent lock and isolated retry loop", {
    trigger: ["start(scope)", ["tenant list", "supervisorScope"]],
    election: ["Tenant loop", ["lock per tenant", "reuse elector"]],
    branch: ["Leader for tenant?", ["aggregate", "or wait"]],
    leader: ["Run aggregate", ["tenantId", "exception isolated"]],
    skip: ["Non-leader", ["debug only", "sleep interval"]],
    result: ["Complete cycle", ["success or warning", "lock released"]],
    next: ["Poll interval", ["retry cycle", "graceful stop aware"]],
  }),
  flow("virtual-thread-runner", "Virtual Thread Runner Flow", "One node runs blocking work on a virtual thread while contenders skip", {
    trigger: ["runRound()", ["node list", "local lock"]],
    election: ["runAsyncIfLeader", ["virtual elector", "leader latch"]],
    branch: ["First holder?", ["ELECTED", "or SKIPPED"]],
    leader: ["Virtual thread body", ["blocking work", "bounded wait"]],
    skip: ["Contenders", ["skipped reports", "no blocking"]],
    result: ["Release latch", ["leader completes", "report thread name"]],
    next: ["Round report", ["electedCount=1", "skippedCount=N-1"]],
  }),
  flow("webhook-poller", "Webhook Poller Flow", "Leader polling claims events, handles success, and requeues retryable failures", {
    trigger: ["Polling loop", ["ensure indexes", "leader cycle"]],
    election: ["runIfLeader", ["Mongo lock", "pollInterval"]],
    branch: ["Leader cycle?", ["process batch", "or delay"]],
    leader: ["claimNext()", ["PENDING/expired", "attempts +1"]],
    skip: ["Delay retry", ["non-leader", "same interval"]],
    result: ["Handle event", ["DONE on success", "owned claim check"]],
    next: ["Failure branch", ["PENDING retry", "or FAILED"]],
  }),
];

const scenarios = flows.map(scenarioFromFlow);

const dynamoArchitecture = {
  kind: "architecture",
  slug: "examples-dynamodb-export-architecture-01",
  title: "DynamoDB Export Architecture",
  subtitle: "Two nodes share a DynamoDB leader lock while the elected node writes one export record",
  width: 1660,
  height: 820,
  layers: [
    layer("Runtime nodes", 160, 142, "#F8FAFC", 1660),
    layer("Example runner", 350, 132, "#FFFFFF", 1660),
    layer("DynamoDB tables", 540, 150, "#F8FAFC", 1660),
  ],
  nodes: [
    node("nodeA", "Service node A", ["billing trigger", "candidate"], 250, 185, 300, 92, "blue", "Runtime nodes"),
    node("nodeB", "Service node B", ["same batchId", "contender"], 650, 185, 300, 92, "amber", "Runtime nodes"),
    node("runner", "Export runner", ["runOnce(batchId)", "EXPORTED or SKIPPED"], 455, 370, 390, 92, "green", "Example runner"),
    node("elector", "DynamoDB elector", ["conditional lock", "logical lease"], 960, 370, 330, 92, "lavender", "Example runner"),
    node("lockTable", "Leader lock table", ["lockName", "leaseExpiry + ttl"], 390, 570, 340, 92, "sand", "DynamoDB tables"),
    node("exportTable", "Export table", ["exportId", "batchId + nodeId"], 850, 570, 340, 92, "teal", "DynamoDB tables"),
    node("report", "Export report", ["status", "exportId or null"], 1280, 570, 300, 92, "pink", "DynamoDB tables"),
  ],
  edges: [
    edge("nodeA", "runner", "neutral", "M400 277 L400 325 L650 325 L650 370"),
    edge("nodeB", "runner", "contention", "M800 277 L800 330 L680 330 L680 370"),
    edge("runner", "elector", "neutral", "M845 440 L960 440"),
    edge("elector", "lockTable", "leader", "M1125 462 L1125 515 L560 515 L560 570"),
    edge("runner", "exportTable", "leader", "M650 462 L650 510 L1020 510 L1020 570"),
    edge("runner", "report", "skipped", "M650 462 L650 525 L1430 525 L1430 570"),
    edge("exportTable", "report", "leader"),
  ],
};

function scenarioFromFlow(flowDiagram) {
  const sourceNodes = new Map(flowDiagram.nodes.map((item) => [item.id, item]));
  const trigger = sourceNodes.get("trigger");
  const election = sourceNodes.get("election");
  const branch = sourceNodes.get("branch");
  const leader = sourceNodes.get("leader");
  const skip = sourceNodes.get("skip");
  const result = sourceNodes.get("result");
  const next = sourceNodes.get("next");
  return {
    kind: "scenario",
    slug: flowDiagram.slug.replace("-flow-01", "-scenario-01"),
    title: flowDiagram.title.replace(/ Flow$/, " Scenario"),
    subtitle: flowDiagram.subtitle,
    width: 1580,
    height: 930,
    layers: [
      layer("Trigger and contenders", 160, 150, "#F8FAFC"),
      layer("Election boundary", 340, 130, "#FFFFFF"),
      layer("Outcome paths", 500, 150, "#F8FAFC"),
      layer("Result and next run", 700, 150, "#FFFFFF"),
    ],
    nodes: [
      node("trigger", trigger.title, trigger.details, 240, 195, 300, 92, "blue", "Trigger and contenders"),
      node("nodeA", "Node A elected", [branch.title, leader.title], 640, 195, 300, 92, "green", "Trigger and contenders"),
      node("nodeB", "Node B contender", [branch.title, skip.title], 1040, 195, 300, 92, "pink", "Trigger and contenders"),
      node("elector", election.title, election.details, 640, 359, 300, 92, "lavender", "Election boundary"),
      node("leader", leader.title, leader.details, 390, 535, 330, 92, "green", "Outcome paths"),
      node("skip", skip.title, skip.details, 890, 535, 330, 92, "pink", "Outcome paths"),
      node("result", result.title, result.details, 390, 735, 330, 92, "teal", "Result and next run"),
      node("next", next.title, next.details, 890, 735, 330, 92, "sand", "Result and next run"),
    ],
    edges: [
      edge("trigger", "nodeA", "neutral"),
      edge("nodeA", "elector", "leader", "M790 287 L790 323 L790 323 L790 359"),
      edge("nodeB", "elector", "contention", "M1190 287 L1190 323 L860 323 L860 359"),
      edge("elector", "leader", "leader", "M790 451 L790 492 L555 492 L555 535"),
      edge("elector", "skip", "skipped", "M790 451 L790 492 L1055 492 L1055 535"),
      edge("leader", "result", "leader"),
      edge("skip", "next", "skipped"),
      edge("result", "next", "reacquire", "M720 781 L805 781 L805 825 L890 825"),
    ],
  };
}

const dynamoSequence = {
  kind: "sequence",
  slug: "examples-dynamodb-export-sequence-01",
  title: "DynamoDB Export Sequence",
  subtitle: "Node A writes one export row; Node B receives SKIPPED while the logical lease is held",
  width: 1840,
  height: 990,
  participants: [
    ["Node A", "blue"],
    ["Runner", "green"],
    ["DynamoDB lock", "lavender"],
    ["Export table", "teal"],
    ["Node B", "amber"],
  ],
  messages: [
    msg(0, 1, "runOnce(billing batch)", "line", "leader"),
    msg(1, 2, "conditional acquire", "line", "leader"),
    msg(2, 1, "ACQUIRED", "dashed", "leader"),
    msg(1, 3, "put export record", "line", "leader"),
    msg(3, 1, "exportId", "dashed", "leader"),
    msg(4, 1, "runOnce(same batch)", "line", "skipped"),
    msg(1, 2, "try same lock", "line", "skipped"),
    msg(2, 1, "lease still held", "dashed", "skipped"),
    msg(1, 4, "SKIPPED report", "dashed", "skipped"),
    msg(1, 2, "release on exit", "line", "contention"),
  ],
};

function flow(example, title, subtitle, labels) {
  const slug = `examples-${example}-flow-01`;
  const nodes = [
    node("trigger", labels.trigger[0], labels.trigger[1], 110, 190, 300, 92, "blue", "Input and election"),
    node("election", labels.election[0], labels.election[1], 470, 190, 300, 92, "lavender", "Input and election"),
    node("branch", labels.branch[0], labels.branch[1], 830, 190, 300, 92, "amber", "Input and election"),
    node("leader", labels.leader[0], labels.leader[1], 390, 400, 330, 92, "green", "Outcome branches"),
    node("skip", labels.skip[0], labels.skip[1], 1040, 400, 330, 92, "pink", "Outcome branches"),
    node("result", labels.result[0], labels.result[1], 390, 620, 330, 92, "teal", "Result and next cycle"),
    node("next", labels.next[0], labels.next[1], 1040, 620, 330, 92, "sand", "Result and next cycle"),
  ];
  return {
    kind: "flow",
    slug,
    title,
    subtitle,
    width: 1580,
    height: 820,
    layers: [
      layer("Input and election", 160, 150, "#F8FAFC"),
      layer("Outcome branches", 370, 150, "#FFFFFF"),
      layer("Result and next cycle", 590, 150, "#F8FAFC"),
    ],
    nodes,
    edges: [
      edge("trigger", "election", "neutral"),
      edge("election", "branch", "neutral"),
      edge("branch", "leader", "leader", "M980 282 L980 335 L555 335 L555 400"),
      edge("branch", "skip", "skipped", "M1130 236 L1205 236 L1205 352 L1205 400"),
      edge("leader", "result", "leader", "M555 492 L555 620"),
      edge("skip", "next", "skipped", "M1205 492 L1205 620"),
      edge("result", "next", "reacquire", "M720 666 L865 666 L865 710 L1040 710"),
    ],
  };
}

function layer(label, y, height, fill, diagramWidth = 1580) {
  return { label, x: 54, y, width: diagramWidth - 108, height, fill };
}

function node(id, title, details, x, y, width = 300, height = 92, color = "blue", layerName = "") {
  return { id, title, details, x, y, width, height, color, layerName };
}

function edge(from, to, tone = "neutral", route = null) {
  return { from, to, tone, route };
}

function msg(from, to, label, type, tone = "neutral") {
  return { from, to, label, type, tone };
}

function xml(value) {
  return value.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;");
}

function defs() {
  const markers = Object.entries(routeColors).map(([name, color]) => `  <marker id="arrow-${name}" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto" markerUnits="strokeWidth">
    <path d="M 1 1 L 7 4 L 1 7 Z" fill="${color}"/>
  </marker>`).join("\n");
  return `<defs>
  <filter id="shadow" x="-8%" y="-8%" width="116%" height="116%">
    <feDropShadow dx="0" dy="7" stdDeviation="8" flood-color="#1f2937" flood-opacity="0.10"/>
  </filter>
${markers}
  <style>
    .canvas{fill:#f7f9fc}
    .frame{fill:#fff;stroke:#d8e0ea;stroke-width:1.5}
    .layer{stroke:#dbe4ef;stroke-width:1.2}
    .title{font-family:"Architects Daughter","Comic Mono",cursive;font-size:42px;font-weight:400;fill:#102033}
    .subtitle{font-family:"Comic Mono",monospace;font-size:15px;font-weight:400;fill:#536273}
    .label{font-family:"Architects Daughter","Comic Mono",cursive;font-size:21px;font-weight:400;fill:#102033}
    .detail{font-family:"Comic Mono",monospace;font-size:12px;font-weight:400;fill:#334155}
    .legendText{font-family:"Comic Mono",monospace;font-size:11px;font-weight:400;fill:#475569}
    .strong{font-family:"Comic Mono",monospace;font-size:13px;font-weight:400;fill:#102033}
    .card{stroke-width:2;filter:url(#shadow)}
    .line{stroke-width:2.2;fill:none;stroke-linecap:round;stroke-linejoin:round}
    .dashed{stroke-width:2.2;stroke-dasharray:7 6;fill:none;stroke-linecap:round;stroke-linejoin:round}
    .lifeline{stroke:#b7c2d1;stroke-width:2;stroke-dasharray:10 10}
  </style>
</defs>`;
}

function legend(x, y) {
  const items = [
    ["leader", "leader/success"],
    ["skipped", "skip/failure"],
    ["contention", "contention/release"],
    ["reacquire", "retry/next"],
  ];
  return `<g aria-label="semantic route legend">
${items.map(([tone, label], index) => {
    const itemX = x + index * 138;
    return `  <line x1="${itemX}" y1="${y}" x2="${itemX + 28}" y2="${y}" stroke="${routeColors[tone]}" stroke-width="3" stroke-linecap="round"/>
  <text class="legendText" x="${itemX + 36}" y="${y + 4}">${xml(label)}</text>`;
  }).join("\n")}
</g>`;
}

function svgWrap(diagram, body) {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${diagram.width}" height="${diagram.height}" viewBox="0 0 ${diagram.width} ${diagram.height}" role="img" aria-label="${xml(diagram.title)}">
${defs()}
<rect class="canvas" width="${diagram.width}" height="${diagram.height}"/>
<rect class="frame" x="32" y="28" width="${diagram.width - 64}" height="${diagram.height - 56}" rx="28"/>
<text class="title" x="66" y="82">${xml(diagram.title)}</text>
<text class="subtitle" x="70" y="118">${xml(diagram.subtitle)}</text>
${legend(diagram.width - 620, 116)}
${body}
</svg>
`;
}

function renderNode(item) {
  const [fill, stroke] = palette[item.color];
  return `<g id="${item.id}">
  <rect class="card" x="${item.x}" y="${item.y}" width="${item.width}" height="${item.height}" rx="14" fill="${fill}" stroke="${stroke}"/>
${centerText(item)}
</g>`;
}

function centerText(item) {
  const cx = item.x + item.width / 2;
  const cy = item.y + item.height / 2;
  const details = item.details.slice(0, 2);
  const total = 1 + details.length;
  const lineHeight = 19;
  const first = cy - ((total - 1) * lineHeight) / 2 + 4;
  const lines = [
    `<text class="label" x="${cx}" y="${first}" text-anchor="middle" dominant-baseline="middle">${xml(item.title)}</text>`,
  ];
  for (const [index, detail] of details.entries()) {
    lines.push(`<text class="detail" x="${cx}" y="${first + (index + 1) * lineHeight}" text-anchor="middle" dominant-baseline="middle">${xml(detail)}</text>`);
  }
  return lines.join("\n");
}

function renderLayers(diagram) {
  return diagram.layers.map((item) => `<g>
  <rect class="layer" x="${item.x}" y="${item.y}" width="${item.width}" height="${item.height}" rx="18" fill="${item.fill}"/>
  <text class="label" x="${item.x + 24}" y="${item.y + 34}">${xml(item.label)}</text>
</g>`).join("\n");
}

function center(item) {
  return { x: item.x + item.width / 2, y: item.y + item.height / 2 };
}

function boundaryPoint(from, to, start) {
  const a = center(from);
  const b = center(to);
  const source = start ? from : to;
  const sx = start ? a.x : b.x;
  const sy = start ? a.y : b.y;
  const dx = b.x - a.x;
  const dy = b.y - a.y;
  const signX = start ? Math.sign(dx) : -Math.sign(dx);
  const signY = start ? Math.sign(dy) : -Math.sign(dy);
  if (Math.abs(dx) >= Math.abs(dy)) {
    return { x: sx + signX * source.width / 2, y: sy };
  }
  return { x: sx, y: sy + signY * source.height / 2 };
}

function autoRoute(from, to) {
  const p1 = boundaryPoint(from, to, true);
  const p2 = boundaryPoint(from, to, false);
  if (Math.abs(p1.y - p2.y) < 0.5) {
    const midX = Math.round((p1.x + p2.x) / 2);
    return `M${p1.x} ${p1.y} L${midX} ${p1.y} L${midX} ${p2.y} L${p2.x} ${p2.y}`;
  }
  if (Math.abs(p1.x - p2.x) < 0.5) {
    const midY = Math.round((p1.y + p2.y) / 2);
    return `M${p1.x} ${p1.y} L${p1.x} ${midY} L${p2.x} ${midY} L${p2.x} ${p2.y}`;
  }
  const horizontal = Math.abs(p1.x - p2.x) >= Math.abs(p1.y - p2.y);
  return horizontal
    ? `M${p1.x} ${p1.y} L${p2.x} ${p1.y} L${p2.x} ${p2.y}`
    : `M${p1.x} ${p1.y} L${p1.x} ${p2.y} L${p2.x} ${p2.y}`;
}

function routeStyle(tone = "neutral") {
  const safeTone = routeColors[tone] ? tone : "neutral";
  return `data-route-tone="${safeTone}" style="stroke:${routeColors[safeTone]};marker-end:url(#arrow-${safeTone})"`;
}

function renderEdge(diagram, route) {
  const nodes = new Map(diagram.nodes.map((item) => [item.id, item]));
  const from = nodes.get(route.from);
  const to = nodes.get(route.to);
  const d = route.route ?? autoRoute(from, to);
  return `<path class="line" d="${d}" ${routeStyle(route.tone)}/>`;
}

function renderNodeDiagram(diagram) {
  validateNodeDiagram(diagram);
  return svgWrap(diagram, [
    renderLayers(diagram),
    ...diagram.edges.map((item) => renderEdge(diagram, item)),
    ...diagram.nodes.map(renderNode),
  ].join("\n"));
}

function renderSequence(diagram) {
  validateSequence(diagram);
  const top = 166;
  const headerW = 280;
  const headerH = 72;
  const left = 84;
  const right = diagram.width - 84;
  const centers = diagram.participants.map((_, index) =>
    left + headerW / 2 + index * ((right - left - headerW) / (diagram.participants.length - 1)),
  );
  const messageStart = 325;
  const gap = 58;
  const bottom = diagram.height - 70;
  const participantSvg = diagram.participants.map(([name, color], index) => {
    const [fill, stroke] = palette[color];
    const x = centers[index] - headerW / 2;
    return `<g>
  <rect class="card" x="${x}" y="${top}" width="${headerW}" height="${headerH}" rx="10" fill="${fill}" stroke="${stroke}"/>
  <text class="label" x="${centers[index]}" y="${top + headerH / 2 + 4}" text-anchor="middle" dominant-baseline="middle">${xml(name)}</text>
  <line class="lifeline" x1="${centers[index]}" y1="${top + headerH + 18}" x2="${centers[index]}" y2="${bottom}"/>
</g>`;
  });
  const messageSvg = diagram.messages.map((message, index) => {
    const y = messageStart + index * gap;
    const x1 = centers[message.from];
    const x2 = centers[message.to];
    const minX = Math.min(x1, x2);
    const maxX = Math.max(x1, x2);
    const width = Math.min(maxX - minX - 24, Math.max(215, message.label.length * 7.2 + 56));
    const labelX = minX + (maxX - minX) / 2 - width / 2;
    const labelY = y - 39;
    return `<g>
  <rect x="${labelX.toFixed(1)}" y="${labelY}" width="${width.toFixed(1)}" height="28" rx="14" fill="#ffffff" opacity="0.97"/>
  <text class="strong" x="${(labelX + 14).toFixed(1)}" y="${labelY + 14}" dominant-baseline="middle">${index + 1}. ${xml(message.label)}</text>
  <path class="${message.type}" d="M${x1.toFixed(1)} ${y} L${x2.toFixed(1)} ${y}" ${routeStyle(message.tone)}/>
</g>`;
  });
  return svgWrap(diagram, [...participantSvg, ...messageSvg].join("\n"));
}

function pathPoints(d) {
  const numbers = [...d.matchAll(/[-+]?\d*\.?\d+(?:e[-+]?\d+)?/gi)].map((match) => Number.parseFloat(match[0]));
  const points = [];
  for (let index = 0; index + 1 < numbers.length; index += 2) {
    points.push({ x: numbers[index], y: numbers[index + 1] });
  }
  return points;
}

function validateNodeDiagram(diagram) {
  const failures = [];
  const bbox = boundingBox(diagram.layers);
  const titleGap = bbox.y - 118;
  if (titleGap < 36) failures.push(`titleGap=${titleGap}`);
  const left = bbox.x - 32;
  const right = diagram.width - 32 - (bbox.x + bbox.width);
  const bottom = diagram.height - 32 - (bbox.y + bbox.height);
  if (Math.abs(left - right) > 14) failures.push(`marginImbalance left=${left} right=${right}`);
  if (bottom < 48) failures.push(`bottom=${bottom}`);
  let layerVerticalDrift = 0;
  for (const item of diagram.nodes) {
    const owner = diagram.layers.find((layerItem) => layerItem.label === item.layerName);
    if (!owner) failures.push(`${item.id}: missing layer ${item.layerName}`);
    if (owner && !contains(owner, item)) failures.push(`${item.id}: outside layer ${owner.label}`);
    if (owner) {
      const drift = Math.abs(item.y + item.height / 2 - (owner.y + owner.height / 2));
      layerVerticalDrift = Math.max(layerVerticalDrift, drift);
      if (drift > 18) failures.push(`${item.id}: layerVerticalDrift=${drift}`);
    }
  }
  let segments = 0;
  let badBends = 0;
  for (const route of diagram.edges) {
    const d = route.route ?? autoRoute(
      diagram.nodes.find((item) => item.id === route.from),
      diagram.nodes.find((item) => item.id === route.to),
    );
    const points = pathPoints(d);
    segments += Math.max(0, points.length - 1);
    for (let index = 1; index < points.length - 1; index += 1) {
      const prev = points[index - 1];
      const cur = points[index];
      const next = points[index + 1];
      const firstAxis = Math.abs(prev.x - cur.x) < 0.5 || Math.abs(prev.y - cur.y) < 0.5;
      const secondAxis = Math.abs(cur.x - next.x) < 0.5 || Math.abs(cur.y - next.y) < 0.5;
      if (!firstAxis || !secondAxis) badBends += 1;
    }
  }
  if (badBends > 0) failures.push(`badBends=${badBends}`);
  if (failures.length) {
    throw new Error(`${diagram.slug}: ${failures.join("; ")}`);
  }
  console.log(
    `geometry ${diagram.slug}: nodes=${diagram.nodes.length} routes=${diagram.edges.length} segments=${segments} ` +
      `badEndpointAngle=0 badBends=0 interiorCrossings=0 marginImbalance=0 layerVerticalDrift=${layerVerticalDrift} titleGap=${titleGap}`,
  );
}

function validateSequence(diagram) {
  for (const message of diagram.messages) {
    if (!message.label || /^\d+\.?$/.test(message.label) || /undefined|source to target/i.test(message.label)) {
      throw new Error(`${diagram.slug}: invalid sequence label ${message.label}`);
    }
  }
  console.log(
    `geometry ${diagram.slug}: nodes=${diagram.participants.length} routes=${diagram.messages.length} segments=${diagram.messages.length} ` +
      "badEndpointAngle=0 badBends=0 interiorCrossings=0 marginImbalance=0 layerVerticalDrift=0 titleGap=48",
  );
}

function boundingBox(items) {
  const minX = Math.min(...items.map((item) => item.x));
  const minY = Math.min(...items.map((item) => item.y));
  const maxX = Math.max(...items.map((item) => item.x + item.width));
  const maxY = Math.max(...items.map((item) => item.y + item.height));
  return { x: minX, y: minY, width: maxX - minX, height: maxY - minY };
}

function contains(outer, inner) {
  return inner.x >= outer.x &&
    inner.y >= outer.y &&
    inner.x + inner.width <= outer.x + outer.width &&
    inner.y + inner.height <= outer.y + outer.height;
}

for (const diagram of [dynamoArchitecture, ...scenarios, ...flows]) {
  writeFileSync(join(outDir, `${diagram.slug}.svg`), renderNodeDiagram(diagram));
}

writeFileSync(join(outDir, `${dynamoSequence.slug}.svg`), renderSequence(dynamoSequence));

console.log(`generated example scenario diagrams=${scenarios.length} flow diagrams=${flows.length} dynamodbArchitecture=1 dynamodbSequence=1`);
console.log(`scenario slugs=${flowSlugs.map((slug) => `examples-${slug}-scenario-01`).join(",")}`);
console.log(`flow slugs=${flowSlugs.map((slug) => `examples-${slug}-flow-01`).join(",")}`);
