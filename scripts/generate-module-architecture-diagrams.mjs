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
  ["#EEF6D9", "#8BA84D"],
  ["#F2F5F9", "#7B8798"],
];

const diagrams = [
  {
    slug: "leader-micrometer-architecture-01",
    title: "leader micrometer Architecture",
    subtitle: "AOP, direct elector, and listener paths converge on a Micrometer MeterRegistry",
    width: 1260,
    height: 760,
    nodes: [
      ["annotations", ["@LeaderElection", "@LeaderGroupElection"], 70, 165, 300, 108],
      ["recorder", ["Micrometer Leader", "Aop Metrics Recorder"], 450, 150, 320, 108],
      ["direct", ["Direct elector calls"], 870, 185, 300, 108],
      ["decorators", ["Instrumented*Elector", "decorators"], 800, 340, 320, 108],
      ["callbacks", ["Leader Election", "Listener callbacks"], 70, 455, 300, 108],
      ["listener", ["Micrometer Leader", "Election Listener"], 450, 455, 320, 108],
      ["registry", ["MeterRegistry"], 450, 610, 320, 108],
      ["exporters", ["Prometheus / Datadog", "/ OTLP"], 860, 600, 320, 108],
    ],
    edges: [
      ["M370 219 L450 219"],
      ["M1020 293 L1020 318 L960 318 L960 340"],
      ["M610 258 L610 310 L390 310 L390 664 L450 664"],
      ["M960 448 L960 585 L690 585 L690 610"],
      ["M370 509 L450 509"],
      ["M610 563 L610 610"],
      ["M770 664 L815 664 L815 654 L860 654"],
    ],
  },
  {
    slug: "leader-spring-boot-architecture-01",
    title: "leader spring boot Architecture",
    subtitle: "Properties and auto-configuration select backend factories before woven leader aspects execute",
    width: 1360,
    height: 800,
    nodes: [
      ["leaderProps", ["LeaderProperties", "bluetape4k.leader.*"], 70, 160, 300, 108],
      ["factoryAuto", ["Leader Aop Factory", "Auto Configuration"], 430, 160, 320, 108],
      ["electorFactory", ["LeaderElectorFactory", "Leader Group Elector", "Factory Suspend"], 830, 160, 360, 118],
      ["aopProps", ["LeaderAopProperties", "bluetape4k.leader.aop.*"], 70, 330, 300, 108],
      ["aopAuto", ["Leader Aop Auto", "Configuration"], 430, 330, 320, 108],
      ["selector", ["LeaderBeanSelector"], 830, 330, 300, 108],
      ["micrometer", ["Leader Micrometer", "Auto Configuration"], 70, 520, 300, 108],
      ["spel", ["Spel Expression", "Evaluator"], 430, 600, 320, 108],
      ["aspects", ["LeaderElectionAspect", "Leader Group", "Election Aspect"], 830, 600, 360, 118],
    ],
    edges: [
      ["M370 214 L430 214"],
      ["M750 214 L790 214 L790 219 L830 219"],
      ["M370 384 L430 384"],
      ["M370 574 L400 574 L400 384 L430 384"],
      ["M750 384 L830 384"],
      ["M590 438 L590 600"],
      ["M980 438 L980 560 L1010 560 L1010 600"],
      ["M750 654 L790 654 L790 659 L830 659"],
      ["M1010 278 L1010 300 L1230 300 L1230 659 L1190 659"],
      ["M430 214 L400 214 L400 574 L370 574"],
    ],
  },
  {
    slug: "leader-etcd-architecture-01",
    title: "leader etcd Architecture",
    subtitle: "jetcd electors use caller-owned clients, etcd leases, Lock service keys, and optional watch events",
    width: 1320,
    height: 760,
    nodes: [
      ["app", ["Application workers", "runIfLeader / group"], 70, 165, 300, 108],
      ["spring", ["Spring Boot", "caller-owned Client bean"], 70, 385, 300, 108],
      ["single", ["EtcdLeaderElector", "blocking / async / suspend"], 430, 145, 340, 108],
      ["group", ["EtcdLeaderGroupElector", "slot keys"], 430, 330, 340, 108],
      ["watcher", ["Event Publisher", "watch key create/delete"], 430, 515, 340, 108],
      ["client", ["jetcd Client", "lifecycle outside elector"], 810, 315, 300, 108],
      ["lock", ["etcd Lock service", "ownership key + token"], 970, 145, 300, 108],
      ["lease", ["etcd Lease", "TTL + keepalive"], 970, 515, 300, 108],
    ],
    edges: [
      ["M370 219 L400 219 L400 199 L430 199"],
      ["M370 439 L400 439 L400 384 L430 384"],
      ["M370 439 L400 439 L400 569 L430 569"],
      ["M770 199 L970 199"],
      ["M770 384 L790 384 L790 369 L810 369"],
      ["M600 330 L600 253"],
      ["M1130 253 L1130 515"],
      ["M810 369 L805 369 L805 199 L970 199"],
    ],
  },
  {
    slug: "leader-dynamodb-architecture-01",
    title: "leader dynamodb Architecture",
    subtitle: "Conditional writes guard ownership while logical leaseExpiry, not TTL deletion, decides correctness",
    width: 1320,
    height: 760,
    nodes: [
      ["clients", ["DynamoDbClient", "DynamoDbAsyncClient"], 70, 170, 310, 108],
      ["spring", ["Spring Boot", "caller-owned SDK beans"], 70, 420, 310, 108],
      ["single", ["DynamoDbLeaderElector", "conditional PutItem"], 450, 145, 340, 108],
      ["group", ["DynamoDbLeaderGroupElector", "fixed slot rows"], 450, 330, 340, 108],
      ["adapters", ["Virtual / Suspend", "adapters"], 450, 515, 340, 108],
      ["table", ["DynamoDB lock table", "partition key lockName"], 850, 235, 340, 108],
      ["row", ["Lock row", "ownerId / leaseExpiry / ttl"], 850, 500, 340, 108],
    ],
    edges: [
      ["M380 224 L415 224 L415 199 L450 199"],
      ["M380 224 L415 224 L415 384 L450 384"],
      ["M380 474 L415 474 L415 569 L450 569"],
      ["M790 199 L820 199 L820 289 L850 289"],
      ["M790 384 L820 384 L820 289 L850 289"],
      ["M790 569 L820 569 L820 554 L850 554"],
      ["M1020 343 L1020 500"],
    ],
  },
  {
    slug: "leader-consul-architecture-01",
    title: "leader consul Architecture",
    subtitle: "Consul sessions and KV acquire/release provide single and slot-based group leadership",
    width: 1320,
    height: 760,
    nodes: [
      ["endpoint", ["ConsulEndpoint", "URL / DC / ACL token"], 70, 180, 310, 108],
      ["spring", ["Spring Boot", "endpoint bean"], 70, 430, 310, 108],
      ["single", ["ConsulLeaderElector", "single key"], 450, 155, 340, 108],
      ["group", ["ConsulLeaderGroupElector", "slot keys"], 450, 360, 340, 108],
      ["session", ["Consul Session", "TTL renew + lockDelay"], 850, 155, 340, 108],
      ["kv", ["Consul KV", "acquire / release"], 850, 360, 340, 108],
      ["listeners", ["Core listeners", "withListeners() decorators"], 450, 565, 340, 108],
    ],
    edges: [
      ["M380 234 L415 234 L415 209 L450 209"],
      ["M380 234 L415 234 L415 414 L450 414"],
      ["M380 484 L415 484 L415 619 L450 619"],
      ["M790 209 L850 209"],
      ["M790 414 L850 414"],
      ["M1020 263 L1020 360"],
      ["M620 565 L620 468"],
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
    .card{stroke-width:2.1;filter:url(#shadow)}
    .line{stroke:#758297;stroke-width:2.1;fill:none;marker-end:url(#openArrow)}
  </style>
</defs>`;
}

function card(node, index) {
  const [id, lines, x, y, width, height] = node;
  const [fill, stroke] = palette[index % palette.length];
  const center = width / 2;
  const labels = lines.length === 1 ? [54] : lines.length === 2 ? [39, 66] : [31, 58, 83];
  return `<g id="${id}" transform="translate(${x},${y})">
  <rect class="card" x="0" y="0" width="${width}" height="${height}" rx="14" fill="${fill}" stroke="${stroke}"/>
${lines.map((line, lineIndex) => `  <text class="smallLabel" x="${center}" y="${labels[lineIndex]}" text-anchor="middle">${xml(line)}</text>`).join("\n")}
</g>`;
}

function svg(diagram) {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${diagram.width}" height="${diagram.height}" viewBox="0 0 ${diagram.width} ${diagram.height}" role="img" aria-label="${xml(diagram.title)}">
${defs()}
<rect class="canvas" width="${diagram.width}" height="${diagram.height}"/>
<rect class="frame" x="32" y="28" width="${diagram.width - 64}" height="${diagram.height - 56}" rx="28"/>
<text class="title" x="66" y="82">${xml(diagram.title)}</text>
<text class="subtitle" x="70" y="118">${xml(diagram.subtitle)}</text>
${diagram.edges.map((edge) => `<path class="line" d="${edge[0]}"/>`).join("\n")}
${diagram.nodes.map(card).join("\n")}
</svg>
`;
}

for (const diagram of diagrams) {
  writeFileSync(join(outDir, `${diagram.slug}.svg`), svg(diagram));
}

console.log(`generated module architecture=${diagrams.length}`);
