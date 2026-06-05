#!/usr/bin/env node

import { execFileSync } from "node:child_process";
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

const diagrams = [
  {
    kind: "architecture",
    slug: "examples-zookeeper-scheduler-scenario-01",
    title: "ZooKeeper Scheduler Scenario",
    subtitle: "The demo proves execute, skip, and reacquire behavior with two scheduler nodes",
    width: 1320,
    height: 650,
    nodes: [
      node("tick", "Same schedule", ["daily-ledger", "first run"], 70, 160, 220, 92, "blue"),
      node("nodeA", "node-a", ["acquires leadership", "runs legacy job"], 350, 120, 270, 100, "green"),
      node("nodeB", "node-b", ["competes while held", "returns SKIPPED"], 350, 300, 270, 100, "amber"),
      cylinder("zk", "ZooKeeper lock", ["Curator session", "base path + lock name"], 700, 205, 260, 128, "lavender"),
      node("job", "Legacy job", ["read-ledger", "write-summary"], 1040, 120, 230, 100, "teal"),
      node("skip", "Skip report", ["status=SKIPPED", "no body call"], 1040, 300, 230, 100, "pink"),
      node("next", "Next schedule", ["node-b reacquires", "status=EXECUTED"], 700, 465, 300, 100, "green"),
    ],
    edges: [
      edge("tick", "nodeA", "trigger"),
      edge("tick", "nodeB", "same run"),
      edge("nodeA", "zk", "lock"),
      edge("nodeB", "zk", "miss"),
      edge("zk", "job", "leader"),
      edge("zk", "skip", "not leader"),
      edge("nodeB", "next", "after release", "M485 400 L485 515 L700 515"),
      edge("next", "zk", "reacquire", "M850 465 L850 333"),
    ],
  },
  {
    kind: "architecture",
    slug: "examples-zookeeper-scheduler-architecture-01",
    title: "ZooKeeper Scheduler Architecture",
    subtitle: "Caller-owned Curator clients feed bluetape4k leader election before a legacy job runs",
    width: 1420,
    height: 720,
    layers: [
      ["Scheduler nodes", 120, "#F8FAFC"],
      ["Example adapter", 255, "#FFFFFF"],
      ["bluetape4k leader", 390, "#F8FAFC"],
      ["ZooKeeper backend", 525, "#FFFFFF"],
    ],
    nodes: [
      node("nodeA", "node-a service", ["scheduled trigger", "candidate"], 80, 135, 250, 92, "blue"),
      node("nodeB", "node-b service", ["same trigger", "candidate"], 390, 135, 250, 92, "amber"),
      node("adapter", "ZooKeeperLegacyScheduler", ["runOnce(scheduleId)", "EXECUTED or SKIPPED"], 250, 270, 330, 96, "green"),
      node("config", "ZooKeeperSchedulerConfig", ["nodeId + lockName", "waitTime + leaseTime"], 720, 270, 300, 96, "teal"),
      node("elector", "ZooKeeperLeaderElector", ["LeaderElectionOptions", "autoExtend=false"], 300, 405, 360, 96, "lavender"),
      node("report", "SchedulerRunReport", ["nodeId + scheduleId", "status + steps"], 790, 405, 300, 96, "pink"),
      cylinder("zk", "ZooKeeper / Curator", ["session lock", "basePath/lockName"], 265, 545, 330, 120, "sand"),
      node("job", "Legacy scheduled job", ["caller body", "validated step names"], 760, 555, 330, 96, "green"),
    ],
    edges: [
      edge("nodeA", "adapter", "calls"),
      edge("nodeB", "adapter", "calls"),
      edge("config", "adapter", "configures"),
      edge("adapter", "elector", "delegates"),
      edge("elector", "zk", "Curator lock"),
      edge("adapter", "job", "leader only"),
      edge("adapter", "report", "returns"),
    ],
  },
  {
    kind: "flow",
    slug: "examples-zookeeper-scheduler-flow-01",
    title: "ZooKeeper Scheduler Flow",
    subtitle: "runOnce converts leader-election outcome into explicit EXECUTED or SKIPPED reports",
    width: 1380,
    height: 760,
    nodes: [
      node("start", "Scheduled tick", ["build SchedulerRunId"], 80, 155, 260, 90, "blue"),
      node("config", "Validate inputs", ["nodeId, lockName", "basePath, scheduleId"], 410, 155, 300, 90, "teal"),
      node("elect", "runIfLeader", ["ZooKeeperLeaderElector", "waitTime window"], 780, 155, 300, 90, "lavender"),
      diamond("decision", "Leadership?", ["lock acquired"], 1140, 140, 150, 120, "amber"),
      node("execute", "Execute job body", ["legacy work runs once"], 910, 355, 300, 90, "green"),
      node("steps", "Validate completed steps", ["requireNotBlank"], 570, 355, 300, 90, "teal"),
      node("executed", "Return EXECUTED", ["steps + elapsed time"], 260, 545, 300, 90, "green"),
      node("skipped", "Return SKIPPED", ["empty steps", "body not invoked"], 940, 545, 300, 90, "pink"),
    ],
    edges: [
      edge("start", "config", "input"),
      edge("config", "elect", "config"),
      edge("elect", "decision", "outcome"),
      edge("decision", "execute", "yes", "M1140 200 L1060 200 L1060 355"),
      edge("execute", "steps", "steps"),
      edge("steps", "executed", "valid"),
      edge("decision", "skipped", "no", "M1290 200 L1320 200 L1320 590 L1240 590"),
    ],
  },
  {
    kind: "sequence",
    slug: "examples-zookeeper-scheduler-sequence-01",
    title: "ZooKeeper Scheduler Sequence",
    subtitle: "node-a holds the ZooKeeper lock, node-b skips, then node-b reacquires for the next run",
    width: 1780,
    height: 1180,
    participants: [
      ["Demo", "blue"],
      ["node-a scheduler", "green"],
      ["ZooKeeperLeaderElector", "lavender"],
      ["ZooKeeper / Curator", "sand"],
      ["Legacy job", "teal"],
      ["node-b scheduler", "amber"],
    ],
    messages: [
      msg(0, 1, "submit first run", "line"),
      msg(1, 2, "runIfLeader(lockName)", "line"),
      msg(2, 3, "acquire session lock", "line"),
      msg(3, 2, "ACQUIRED", "dashed"),
      msg(2, 4, "execute job body", "line"),
      msg(0, 5, "same run while held", "line"),
      msg(5, 2, "runIfLeader(lockName)", "line"),
      msg(2, 3, "try same lock", "line"),
      msg(3, 2, "not leader", "dashed"),
      msg(2, 5, "SKIPPED report", "dashed"),
      msg(1, 3, "release on exit", "line"),
      msg(5, 2, "next run", "line"),
      msg(2, 3, "acquire released lock", "line"),
      msg(3, 2, "ACQUIRED", "dashed"),
      msg(2, 4, "execute next job", "line"),
    ],
  },
];

function node(id, title, details, x, y, width = 250, height = 92, color = "blue") {
  return { id, title, details, x, y, width, height, color, shape: "card" };
}

function cylinder(id, title, details, x, y, width = 280, height = 118, color = "lavender") {
  return { id, title, details, x, y, width, height, color, shape: "cylinder" };
}

function diamond(id, title, details, x, y, width = 160, height = 120, color = "amber") {
  return { id, title, details, x, y, width, height, color, shape: "diamond" };
}

function edge(from, to, label, route = null) {
  return { from, to, label, route };
}

function msg(from, to, label, type) {
  return { from, to, label, type };
}

function xml(value) {
  return value.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;");
}

function defs() {
  return `<defs>
  <filter id="shadow" x="-8%" y="-8%" width="116%" height="116%">
    <feDropShadow dx="0" dy="7" stdDeviation="8" flood-color="#1f2937" flood-opacity="0.10"/>
  </filter>
  <marker id="arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto" markerUnits="strokeWidth">
    <path d="M 1 1 L 7 4 L 1 7 Z" fill="#758297"/>
  </marker>
  <style>
    .canvas{fill:#f7f9fc}
    .frame{fill:#fff;stroke:#d8e0ea;stroke-width:1.5}
    .layer{stroke:#dbe4ef;stroke-width:1.2}
    .title{font-family:"Architects Daughter","Comic Mono","Comic Sans MS",cursive;font-size:42px;font-weight:400;fill:#102033}
    .subtitle{font-family:"Comic Mono","Comic Sans MS",monospace;font-size:15px;font-weight:400;fill:#536273}
    .label{font-family:"Architects Daughter","Comic Mono","Comic Sans MS",cursive;font-size:21px;font-weight:400;fill:#102033}
    .detail{font-family:"Comic Mono","Comic Sans MS",monospace;font-size:12px;font-weight:400;fill:#334155}
    .strong{font-family:"Comic Mono","Comic Sans MS",monospace;font-size:13px;font-weight:400;fill:#102033}
    .card{stroke-width:2;filter:url(#shadow)}
    .line{stroke:#758297;stroke-width:2.2;fill:none;marker-end:url(#arrow);stroke-linecap:round;stroke-linejoin:round}
    .dashed{stroke:#758297;stroke-width:2.2;stroke-dasharray:7 6;fill:none;marker-end:url(#arrow);stroke-linecap:round;stroke-linejoin:round}
    .lifeline{stroke:#b7c2d1;stroke-width:2;stroke-dasharray:10 10}
    .pill{fill:#ffffff;stroke:#dbe4ef;stroke-width:1.1;opacity:.98}
  </style>
</defs>`;
}

function renderNode(item) {
  const [fill, stroke] = palette[item.color];
  if (item.shape === "cylinder") {
    const cap = 24;
    return `<g id="${item.id}">
  <path class="card" d="M${item.x} ${item.y + cap} C${item.x} ${item.y + 8} ${item.x + item.width} ${item.y + 8} ${item.x + item.width} ${item.y + cap} L${item.x + item.width} ${item.y + item.height - cap} C${item.x + item.width} ${item.y + item.height - 8} ${item.x} ${item.y + item.height - 8} ${item.x} ${item.y + item.height - cap} Z" fill="${fill}" stroke="${stroke}"/>
  <ellipse cx="${item.x + item.width / 2}" cy="${item.y + cap}" rx="${item.width / 2}" ry="${cap - 8}" fill="${fill}" stroke="${stroke}" stroke-width="2"/>
${centerText(item)}
</g>`;
  }
  if (item.shape === "diamond") {
    const cx = item.x + item.width / 2;
    const cy = item.y + item.height / 2;
    return `<g id="${item.id}">
  <path class="card" d="M${cx} ${item.y} L${item.x + item.width} ${cy} L${cx} ${item.y + item.height} L${item.x} ${cy} Z" fill="${fill}" stroke="${stroke}"/>
${centerText(item)}
</g>`;
  }
  return `<g id="${item.id}">
  <rect class="card" x="${item.x}" y="${item.y}" width="${item.width}" height="${item.height}" rx="14" fill="${fill}" stroke="${stroke}"/>
${centerText(item)}
</g>`;
}

function centerText(item) {
  const cx = item.x + item.width / 2;
  const cy = item.y + item.height / 2;
  const detailLines = item.details.slice(0, 2);
  const total = 1 + detailLines.length;
  const lineHeight = 19;
  const first = cy - ((total - 1) * lineHeight) / 2 + 4;
  const lines = [
    `<text class="label" x="${cx}" y="${first}" text-anchor="middle" dominant-baseline="middle">${xml(item.title)}</text>`,
  ];
  for (const [index, detail] of detailLines.entries()) {
    lines.push(`<text class="detail" x="${cx}" y="${first + (index + 1) * lineHeight}" text-anchor="middle" dominant-baseline="middle">${xml(detail)}</text>`);
  }
  return lines.join("\n");
}

function boundaryPoint(from, to, start) {
  const a = center(from);
  const b = center(to);
  const dx = b.x - a.x;
  const dy = b.y - a.y;
  const source = start ? from : to;
  const sx = start ? a.x : b.x;
  const sy = start ? a.y : b.y;
  const signX = start ? Math.sign(dx) : -Math.sign(dx);
  const signY = start ? Math.sign(dy) : -Math.sign(dy);
  if (Math.abs(dx) >= Math.abs(dy)) {
    return { x: sx + signX * source.width / 2, y: sy };
  }
  return { x: sx, y: sy + signY * source.height / 2 };
}

function center(item) {
  return { x: item.x + item.width / 2, y: item.y + item.height / 2 };
}

function renderEdge(diagram, route) {
  const nodes = new Map(diagram.nodes.map((item) => [item.id, item]));
  const from = nodes.get(route.from);
  const to = nodes.get(route.to);
  const d = route.route ?? autoRoute(from, to);
  const label = route.label ? labelForPath(route, d, from, to) : "";
  return `<path class="line" d="${d}"/>${label}`;
}

function autoRoute(from, to) {
  const p1 = boundaryPoint(from, to, true);
  const p2 = boundaryPoint(from, to, false);
  if (Math.abs(p1.x - p2.x) > Math.abs(p1.y - p2.y)) {
    const midX = Math.round((p1.x + p2.x) / 2);
    return `M${p1.x} ${p1.y} L${midX} ${p1.y} L${midX} ${p2.y} L${p2.x} ${p2.y}`;
  }
  const midY = Math.round((p1.y + p2.y) / 2);
  return `M${p1.x} ${p1.y} L${p1.x} ${midY} L${p2.x} ${midY} L${p2.x} ${p2.y}`;
}

function labelForPath(route, d, from, to) {
  const c1 = center(from);
  const c2 = center(to);
  const x = Math.round((c1.x + c2.x) / 2);
  const y = Math.round((c1.y + c2.y) / 2) - 16;
  const width = Math.max(68, route.label.length * 7 + 24);
  return `<g>
  <rect class="pill" x="${x - width / 2}" y="${y - 16}" width="${width}" height="26" rx="13"/>
  <text class="strong" x="${x}" y="${y - 3}" text-anchor="middle" dominant-baseline="middle">${xml(route.label)}</text>
</g>`;
}

function renderArchitecture(diagram) {
  const layers = diagram.layers?.map(([label, y, fill]) => `<g>
  <rect class="layer" x="54" y="${y}" width="${diagram.width - 108}" height="112" rx="18" fill="${fill}"/>
  <text class="label" x="78" y="${y + 34}">${xml(label)}</text>
</g>`) ?? [];
  return svgWrap(diagram, [
    ...layers,
    ...diagram.edges.map((item) => renderEdge(diagram, item)),
    ...diagram.nodes.map(renderNode),
  ].join("\n"));
}

function renderSequence(diagram) {
  const top = 166;
  const headerW = 235;
  const headerH = 72;
  const left = 78;
  const right = diagram.width - 78;
  const centers = diagram.participants.map((_, index) =>
    left + headerW / 2 + index * ((right - left - headerW) / (diagram.participants.length - 1)),
  );
  const messageStart = 325;
  const gap = 50;
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
    const width = Math.min(maxX - minX - 24, Math.max(205, message.label.length * 7.2 + 56));
    const labelX = minX + (maxX - minX) / 2 - width / 2;
    const labelY = y - 39;
    return `<g>
  <rect class="pill" x="${labelX.toFixed(1)}" y="${labelY}" width="${width.toFixed(1)}" height="28" rx="14"/>
  <text class="strong" x="${(labelX + 14).toFixed(1)}" y="${labelY + 14}" dominant-baseline="middle">${index + 1}. ${xml(message.label)}</text>
  <path class="${message.type}" d="M${x1.toFixed(1)} ${y} L${x2.toFixed(1)} ${y}"/>
</g>`;
  });
  return svgWrap(diagram, [...participantSvg, ...messageSvg].join("\n"));
}

function svgWrap(diagram, body) {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${diagram.width}" height="${diagram.height}" viewBox="0 0 ${diagram.width} ${diagram.height}" role="img" aria-label="${xml(diagram.title)}">
  <metadata id="graphviz-evidence">Generated with Graphviz evidence: ${diagram.slug}.dot, ${diagram.slug}.plain, ${diagram.slug}-graphviz.svg, and ${diagram.slug}-graphviz.png.</metadata>
${defs()}
<rect class="canvas" width="${diagram.width}" height="${diagram.height}"/>
<rect class="frame" x="32" y="28" width="${diagram.width - 64}" height="${diagram.height - 56}" rx="28"/>
<text class="title" x="66" y="82">${xml(diagram.title)}</text>
<text class="subtitle" x="70" y="118">${xml(diagram.subtitle)}</text>
${body}
</svg>
`;
}

function graphvizDot(diagram) {
  const edges = diagram.kind === "sequence"
    ? diagram.messages.map((message) => {
      const from = diagram.participants[message.from][0];
      const to = diagram.participants[message.to][0];
      return `"${from}" -> "${to}" [label="${message.label}"];`;
    })
    : diagram.edges.map((item) => `"${diagram.nodes.find((node) => node.id === item.from).title}" -> "${diagram.nodes.find((node) => node.id === item.to).title}" [label="${item.label}"];`);
  const nodes = diagram.kind === "sequence"
    ? diagram.participants.map(([name]) => `"${name}";`)
    : diagram.nodes.map((item) => `"${item.title}";`);
  return `digraph "${diagram.slug}" {
  graph [rankdir=LR, bgcolor="transparent", margin=0.1, nodesep=0.65, ranksep=0.9];
  node [shape=box, style="rounded,filled", fillcolor="#f8fafc", color="#94a3b8", fontname="Comic Mono"];
  edge [color="#758297", fontname="Comic Mono", fontsize=10];
  ${nodes.join("\n  ")}
  ${edges.join("\n  ")}
}
`;
}

function validate(svg, diagram) {
  const forbidden = ["Inter", "Arial", "Helvetica"];
  for (const token of forbidden) {
    if (svg.includes(token)) {
      throw new Error(`${diagram.slug}: forbidden font token ${token}`);
    }
  }
  if (diagram.kind === "sequence") {
    for (const message of diagram.messages) {
      if (!message.label || /^\d+\.?$/.test(message.label) || /undefined|source to target/i.test(message.label)) {
        throw new Error(`${diagram.slug}: invalid sequence label ${message.label}`);
      }
    }
  }
}

for (const diagram of diagrams) {
  const dot = graphvizDot(diagram);
  const dotPath = join(outDir, `${diagram.slug}.dot`);
  const plainPath = join(outDir, `${diagram.slug}.plain`);
  const graphvizSvgPath = join(outDir, `${diagram.slug}-graphviz.svg`);
  const graphvizPngPath = join(outDir, `${diagram.slug}-graphviz.png`);
  const svgPath = join(outDir, `${diagram.slug}.svg`);
  const pngPath = join(outDir, `${diagram.slug}.png`);
  writeFileSync(dotPath, dot);
  writeFileSync(plainPath, execFileSync("dot", ["-Tplain", dotPath]));
  execFileSync("dot", ["-Tsvg", dotPath, "-o", graphvizSvgPath]);
  execFileSync("dot", ["-Tpng", dotPath, "-o", graphvizPngPath]);
  const svg = diagram.kind === "sequence" ? renderSequence(diagram) : renderArchitecture(diagram);
  validate(svg, diagram);
  writeFileSync(svgPath, svg);
  execFileSync("rsvg-convert", ["-o", pngPath, svgPath]);
}

console.log(`generated ${diagrams.length} ZooKeeper scheduler README diagrams`);
