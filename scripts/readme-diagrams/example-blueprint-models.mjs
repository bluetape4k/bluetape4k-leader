import { band, card, edge } from "./lib/node-diagram-renderer.mjs";
import { branch, msg } from "./lib/sequence-renderer.mjs";

const examples = [
  ["batch-scheduler", "Batch Scheduler", "scheduled batch tick", "BatchScheduler", "Lettuce Elector", "Redis lock key", "settlement job", "lease TTL recovery"],
  ["cache-warmer", "Cache Warmer", "cache warm cycle", "CacheWarmer", "Leader elector factory", "partition locks", "warm cache partition", "failed partition isolation"],
  ["consul-maintenance", "Consul Maintenance", "maintenance tick", "ServiceMaintenanceCoordinator", "ConsulLeaderElector", "Consul Session and KV", "drain maintenance", "session TTL release"],
  ["dynamodb-export", "DynamoDB Export", "export request", "DynamoDbExportCoordinator", "DynamoDB leader elector", "lock table row", "export batch", "logical lease expiry"],
  ["etcd-reconciler", "Etcd Reconciler", "reconcile tick", "ClusterReconciler", "EtcdLeaderElector", "etcd lease key", "apply desired state", "lease revoke handoff"],
  ["k8s-lease", "K8s Lease", "lease attempt", "K8sLeaseExample", "KubernetesLeaseLeaderElector", "Lease resource", "holder operation", "renewTime takeover"],
  ["k8s-operator", "K8s Operator", "operator schedule", "OperatorController", "Kubernetes Lease Elector", "coordination Lease", "reconcile workload", "RBAC scoped handoff"],
  ["ktor-app", "Ktor App", "background schedule", "LeaderElectionPlugin", "SuspendLeaderElector", "Redis lock key", "aggregate stats", "HTTP health visibility"],
  ["migration-gate", "Migration Gate", "deploy startup", "MigrationGate", "Exposed JDBC Elector", "leader lock table", "run migration", "schema marker recheck"],
  ["prometheus-dashboard", "Prometheus Dashboard", "scheduled metric job", "Leader job bean", "Spring AOP leader proxy", "Redis lock key", "record Micrometer meters", "Prometheus scrape"],
  ["rate-limiter", "Rate Limiter", "dispatch window", "LeaderDispatchScheduler", "Redis Leader Elector", "Bucket4j quota", "call external API", "quota window reset"],
  ["redisson-watchdog", "Redisson Watchdog", "long job trigger", "WatchdogJobRunner", "RedissonLeaderElector", "RLock storage", "long-running body", "auto-extension stop"],
  ["strategic-election", "Strategic Election", "candidate scan", "StrategicLeaderRunner", "Scored election strategy", "local leader slot", "winner-only work", "score refresh"],
  ["tenant-aggregator", "Tenant Aggregator", "tenant cycle", "TenantAggregator", "R2DBC leader electors", "tenant lock rows", "aggregate tenant metrics", "per-tenant isolation"],
  ["virtual-thread-runner", "Virtual Thread Runner", "blocking task trigger", "VirtualThreadRunner", "VirtualThreadLeaderElector", "local lock state", "blocking task body", "virtual thread release"],
  ["webhook-poller", "Webhook Poller", "poll loop", "WebhookPoller", "Mongo leader elector", "Mongo lock document", "claim webhook event", "claim timeout recovery"],
  ["zookeeper-scheduler", "ZooKeeper Scheduler", "scheduler tick", "ZooKeeperLegacyScheduler", "ZooKeeperLeaderElector", "Curator lock path", "legacy scheduled job", "session lock release"],
];

export const exampleArchitectureDiagrams = examples.map(exampleArchitecture);
export const exampleFlowDiagrams = examples.map(exampleFlow);
export const exampleScenarioDiagrams = examples.map(exampleScenario);
export const exampleSequences = examples.map(exampleSequence);

function exampleArchitecture([slug, title, trigger, coordinator, elector, backend, work, recovery]) {
  return {
    kind: "architecture",
    slug: `examples-${slug}-architecture-01`,
    title: `${title} Architecture`,
    subtitle: `${coordinator} gates ${work} through ${elector} and a source-backed backend ownership boundary.`,
    desc: `Architecture diagram for the ${title} example showing the runtime trigger, coordinator, leader elector, backend ownership state, leader-only work, and recovery/observability boundary.`,
    intent: `Answer the ${title} README reader's architecture question: which runtime component receives the ${trigger}, which coordinator owns the example workflow, which bluetape4k leader elector decides ownership, which backend state stores the lock or lease, and which work is allowed to run only while leadership is held?`,
    evidence: `examples/${slug} README overview/scenario/usage sections; example source under examples/${slug}/src/main; tests under examples/${slug}/src/test when present`,
    sourceRead: `examples/${slug}/README.md; examples/${slug}/README.ko.md; examples/${slug}/src/main; examples/${slug}/src/test`,
    bands: [
      band("Runtime entrypoints", 150, 170, 1500, 310),
      band("Leader election boundary", 150, 540, 1500, 220),
      band("Leader-only work and recovery", 150, 820, 1500, 210),
    ],
    nodes: [
      card("trigger", titleCase(trigger), ["same logical cycle", "all candidates see it"], 210, 260, 300, 82, "blue"),
      card("nodeA", "Candidate Node A", ["attempts leadership", "may execute"], 575, 240, 300, 82, "green"),
      card("nodeB", "Candidate Node B", ["same workflow", "skips on miss"], 575, 345, 300, 82, "teal"),
      card("coordinator", coordinator, ["example facade", "wraps runIfLeader"], 980, 292, 340, 92, "purple"),
      card("backend", backend, ["acquire or conflict", "release or expire"], 485, 625, 360, 92, "indigo"),
      card("elector", elector, ["wait + lease options", "owner token semantics"], 980, 625, 360, 92, "amber"),
      card("recovery", titleCase(recovery), ["crash or release path", "next cycle can proceed"], 485, 900, 360, 86, "gray"),
      card("work", titleCase(work), ["leader-only body", "single execution"], 980, 900, 360, 86, "green"),
    ],
    edges: [
      edge("trigger", "nodeA", "call"),
      edge("trigger", "nodeB", "call"),
      edge("nodeA", "coordinator", "call"),
      edge("nodeB", "coordinator", "call"),
      edge("coordinator", "elector", "dependency", [{ x: 1150, y: 384 }, { x: 1150, y: 510 }, { x: 1160, y: 510 }, { x: 1160, y: 625 }]),
      edge("elector", "backend", "contention", [{ x: 980, y: 671 }, { x: 845, y: 671 }]),
      edge("coordinator", "work", "success", [{ x: 1320, y: 356 }, { x: 1420, y: 356 }, { x: 1420, y: 943 }, { x: 1340, y: 943 }]),
      edge("backend", "recovery", "release", [{ x: 845, y: 671 }, { x: 900, y: 671 }, { x: 900, y: 943 }, { x: 845, y: 943 }]),
    ],
  };
}

function exampleFlow([slug, title, trigger, coordinator, elector, backend, work, recovery]) {
  return {
    kind: "flow",
    slug: `examples-${slug}-flow-01`,
    title: `${title} Leader Decision Flow`,
    subtitle: `The example validates inputs, attempts leadership, runs ${work} only on success, and returns a visible skip otherwise.`,
    desc: `Flow diagram for the ${title} example showing validation, leadership decision, leader-only work, skip terminal state, release, and recovery path.`,
    intent: `Answer the ${title} README reader's operational-flow question: what happens after the ${trigger}, where does ${coordinator} validate or prepare the request, how does ${elector} interact with ${backend}, what is the success path for ${work}, and what terminal result appears when the node is not leader?`,
    evidence: `examples/${slug} README scenario/usage sections; examples/${slug}/src/main workflow code; examples/${slug}/src/test behavior checks when present`,
    sourceRead: `examples/${slug}/README.md; examples/${slug}/README.ko.md; examples/${slug}/src/main; examples/${slug}/src/test`,
    bands: [
      band("Input and ownership attempt", 150, 170, 1500, 205),
      band("Branch-specific work", 150, 410, 1500, 190),
      band("Terminal result", 150, 680, 1500, 190),
    ],
    nodes: [
      card("start", titleCase(trigger), ["build request", "same lock name"], 210, 240, 280, 82, "blue"),
      card("validate", "Validate Example Inputs", ["node id / lock name", "backend options"], 555, 240, 315, 82, "teal"),
      card("attempt", elector, ["attempt ownership", "bounded wait"], 935, 240, 330, 82, "amber"),
      card("decision", "Leadership Result", ["acquired?", "skip when false"], 1325, 240, 280, 82, "purple"),
      card("release", "Release Or Extend", ["finally path", recovery], 520, 480, 340, 88, "indigo"),
      card("execute", titleCase(work), ["leader branch", "body runs once"], 965, 480, 340, 88, "green"),
      card("executed", "Return Executed", ["result payload", "observable success"], 375, 745, 320, 82, "green"),
      card("skipped", "Return Skipped", ["null or skipped status", "no duplicate work"], 1085, 745, 340, 82, "pink"),
    ],
    edges: [
      edge("start", "validate", "call"),
      edge("validate", "attempt", "dependency"),
      edge("attempt", "decision", "contention"),
      edge("decision", "execute", "success", [{ x: 1465, y: 322 }, { x: 1465, y: 380 }, { x: 1135, y: 380 }, { x: 1135, y: 480 }]),
      edge("execute", "release", "release"),
      edge("release", "executed", "success", [{ x: 690, y: 568 }, { x: 690, y: 650 }, { x: 720, y: 650 }, { x: 720, y: 786 }, { x: 695, y: 786 }]),
      edge("decision", "skipped", "skip", [{ x: 1605, y: 281 }, { x: 1670, y: 281 }, { x: 1670, y: 786 }, { x: 1425, y: 786 }]),
    ],
  };
}

function exampleScenario([slug, title, trigger, coordinator, elector, backend, work, recovery]) {
  return {
    kind: "scenario-workflow",
    slug: `examples-${slug}-scenario-01`,
    title: `${title} Replica Race Scenario`,
    subtitle: `Multiple candidates see the same ${trigger}; ${backend} allows one leader and makes peers skip cleanly.`,
    desc: `Scenario diagram for the ${title} example showing concurrent candidates, shared backend ownership, one elected leader, skipped peers, and recovery semantics.`,
    intent: `Answer the ${title} README reader's scenario question: when more than one instance observes the same ${trigger}, which candidates race, which shared backend object arbitrates leadership, which node runs ${work}, how do non-leaders return without duplicate work, and how does ${recovery} make the next cycle safe?`,
    evidence: `examples/${slug} README scenario/demo sections; examples/${slug}/src/main example implementation; examples/${slug}/src/test concurrency or behavior examples when present`,
    sourceRead: `examples/${slug}/README.md; examples/${slug}/README.ko.md; examples/${slug}/src/main; examples/${slug}/src/test`,
    bands: [
      band("Shared trigger", 150, 170, 1500, 190),
      band("Replica race", 150, 410, 1500, 370),
      band("Outcome and handoff", 150, 800, 1500, 190),
    ],
    nodes: [
      card("tick", titleCase(trigger), ["one logical event", "seen by all nodes"], 280, 245, 320, 78, "blue"),
      card("nodeA", "Candidate A", [coordinator, "attempts lock"], 620, 470, 300, 78, "green"),
      card("nodeB", "Candidate B", [coordinator, "same lock name"], 620, 565, 300, 78, "teal"),
      card("nodeC", "Candidate C", [coordinator, "standby on miss"], 620, 660, 300, 78, "purple"),
      card("backend", backend, ["single owner wins", "contention is explicit"], 1080, 565, 350, 88, "amber"),
      card("recovery", titleCase(recovery), ["release or expiry", "next cycle safe"], 260, 870, 340, 78, "indigo"),
      card("skipped", "Peers Skip", ["no duplicate body", "visible skipped result"], 710, 870, 340, 78, "pink"),
      card("leader", "One Leader Runs", [titleCase(work), "exactly once"], 1135, 870, 350, 78, "green"),
    ],
    edges: [
      edge("tick", "nodeA", "call", [{ x: 440, y: 323 }, { x: 440, y: 375 }, { x: 770, y: 375 }, { x: 770, y: 470 }]),
      edge("tick", "nodeB", "call", [{ x: 440, y: 323 }, { x: 440, y: 390 }, { x: 590, y: 390 }, { x: 590, y: 604 }, { x: 620, y: 604 }]),
      edge("tick", "nodeC", "call", [{ x: 440, y: 323 }, { x: 440, y: 405 }, { x: 580, y: 405 }, { x: 580, y: 699 }, { x: 620, y: 699 }]),
      edge("nodeA", "backend", "contention", [{ x: 920, y: 509 }, { x: 1000, y: 509 }, { x: 1000, y: 587 }, { x: 1080, y: 587 }]),
      edge("nodeB", "backend", "contention"),
      edge("nodeC", "backend", "contention", [{ x: 920, y: 699 }, { x: 1000, y: 699 }, { x: 1000, y: 631 }, { x: 1080, y: 631 }]),
      edge("backend", "leader", "success", [{ x: 1430, y: 609 }, { x: 1530, y: 609 }, { x: 1530, y: 909 }, { x: 1485, y: 909 }]),
      edge("backend", "skipped", "skip", [{ x: 1255, y: 653 }, { x: 1255, y: 770 }, { x: 880, y: 770 }, { x: 880, y: 870 }]),
      edge("backend", "recovery", "release", [{ x: 1255, y: 653 }, { x: 1255, y: 770 }, { x: 630, y: 770 }, { x: 630, y: 909 }, { x: 600, y: 909 }]),
    ],
  };
}

function exampleSequence([slug, title, trigger, coordinator, elector, backend, work, recovery]) {
  return {
    slug: `examples-${slug}-sequence-01`,
    title: `${title} Runtime Sequence`,
    subtitle: `${coordinator} delegates to ${elector}; the elected branch runs ${work} and the losing branch returns skipped.`,
    desc: `Sequence diagram for the ${title} example showing trigger, coordinator, elector, backend ownership attempt, leader work, release, and skipped peer response.`,
    intent: `Answer the ${title} README reader's runtime-order question: which call starts from the ${trigger}, how ${coordinator} delegates to ${elector}, what ${backend} returns during acquire or contention, which messages are leader-only for ${work}, which messages return a skipped result, and when ${recovery} becomes visible?`,
    evidence: `examples/${slug} README scenario/usage sections; examples/${slug}/src/main example workflow; examples/${slug}/src/test behavior checks when present`,
    sourceRead: `examples/${slug}/README.md; examples/${slug}/README.ko.md; examples/${slug}/src/main; examples/${slug}/src/test`,
    width: 1840,
    participants: [
      { id: "trigger", label: titleCase(trigger), color: "blue" },
      { id: "coord", label: coordinator, color: "green" },
      { id: "elector", label: elector, color: "amber" },
      { id: "backend", label: backend, color: "purple" },
      { id: "work", label: titleCase(work), color: "teal" },
      { id: "peer", label: "Skipped Peer", color: "pink" },
    ],
    events: [
      msg(0, 1, `start ${trigger}`, "call"),
      msg(1, 2, "runIfLeader with source options", "call"),
      msg(2, 3, "attempt backend ownership", "contention"),
      branch("alt ownership acquired"),
      msg(3, 2, "owner token accepted", "success", true),
      msg(2, 4, `execute ${work}`, "success"),
      msg(4, 2, "return leader result", "success", true),
      msg(2, 3, `release or renew for ${recovery}`, "release"),
      branch("else ownership unavailable"),
      msg(3, 2, "lock already owned", "skip", true),
      msg(2, 5, "return skipped result", "skip"),
      msg(5, 0, "cycle completes without duplicate work", "skip", true),
    ],
  };
}

function titleCase(value) {
  return String(value).replace(/\b[a-z]/g, (match) => match.toUpperCase());
}
