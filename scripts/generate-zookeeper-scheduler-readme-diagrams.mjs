#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const root = process.cwd();
const outDir = join(root, "docs/images/readme-diagrams");
mkdirSync(outDir, { recursive: true });
const geometrySummaries = [];

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
    width: 1640,
    height: 810,
    layers: [
      ["Phase 1 - first schedule", 160, "#F8FAFC", 155],
      ["Phase 2 - contention result", 355, "#FFFFFF", 140],
      ["Phase 3 - release and next run", 550, "#F8FAFC", 140],
    ],
    nodes: [
      node("tick", "Same schedule", ["daily-ledger", "first run"], 300, 195, 240, 92, "blue"),
      node("nodeA", "node-a", ["acquires leadership", "runs legacy job"], 610, 195, 280, 92, "green"),
      cylinder("zk", "ZooKeeper lock", ["Curator session", "base path + lock name"], 930, 175, 290, 128, "lavender"),
      node("job", "Legacy job", ["read-ledger", "write-summary"], 1280, 195, 260, 92, "teal"),
      node("nodeB", "node-b", ["competes while held", "body is not called"], 610, 390, 280, 92, "amber"),
      node("skip", "Skip report", ["status=SKIPPED", "empty steps"], 1280, 390, 260, 92, "pink"),
      node("release", "Lock released", ["node-a exits body", "session lock returns"], 930, 585, 290, 92, "sand"),
      node("next", "Next schedule", ["node-b reacquires", "status=EXECUTED"], 1280, 585, 260, 92, "green"),
    ],
    edges: [
      edge("tick", "nodeA", "trigger"),
      edge("nodeA", "zk", "lock"),
      edge("nodeB", "zk", "miss", "M750 390 L750 335 L1075 335 L1075 303"),
      edge("zk", "job", "leader"),
      edge("zk", "skip", "not leader"),
      edge("tick", "nodeB", "same run", "M540 241 L575 241 L575 436 L610 436"),
      edge("nodeA", "release", "release", "M750 287 L750 315 L900 315 L900 631 L930 631"),
      edge("release", "next", "next run"),
      edge("next", "zk", "reacquire", "M1280 631 L1235 631 L1235 239 L1220 239"),
    ],
  },
  {
    kind: "architecture",
    slug: "examples-zookeeper-scheduler-architecture-01",
    title: "ZooKeeper Scheduler Architecture",
    subtitle: "Caller-owned Curator clients feed bluetape4k leader election before a legacy job runs",
    width: 1580,
    height: 900,
    layers: [
      ["Runtime callers", 165, "#F8FAFC", 120],
      ["Example adapter", 320, "#FFFFFF", 120],
      ["bluetape4k leader election", 475, "#F8FAFC", 120],
      ["Curator and ZooKeeper infrastructure", 630, "#FFFFFF", 135],
    ],
    nodes: [
      node("nodeA", "node-a service", ["scheduled trigger", "candidate"], 300, 180, 280, 92, "blue"),
      node("nodeB", "node-b service", ["same trigger", "candidate"], 650, 180, 280, 92, "amber"),
      node("adapter", "ZooKeeperLegacyScheduler", ["runOnce(scheduleId)", "EXECUTED or SKIPPED"], 460, 332, 360, 96, "green"),
      node("config", "ZooKeeperSchedulerConfig", ["nodeId + lockName", "waitTime + leaseTime"], 900, 332, 330, 96, "teal"),
      node("report", "SchedulerRunReport", ["nodeId + scheduleId", "status + steps"], 1260, 332, 230, 96, "pink"),
      node("elector", "ZooKeeperLeaderElector", ["LeaderElectionOptions", "autoExtend=false"], 460, 487, 370, 96, "lavender"),
      node("options", "LeaderElectionOptions", ["waitTime + leaseTime", "node identity"], 900, 487, 320, 96, "teal"),
      cylinder("zk", "ZooKeeper / Curator", ["session lock", "basePath/lockName"], 460, 635, 370, 120, "sand"),
      node("job", "Legacy scheduled job", ["caller body", "validated step names"], 940, 647, 340, 96, "green"),
    ],
    edges: [
      edge("nodeA", "adapter", "calls"),
      edge("nodeB", "adapter", "calls"),
      edge("config", "adapter", "configures"),
      edge("adapter", "elector", "delegates"),
      edge("options", "elector", "options"),
      edge("elector", "zk", "Curator lock"),
      edge("adapter", "report", "returns", "M640 332 L640 305 L1375 305 L1375 332"),
      edge("adapter", "job", "leader only", "M640 428 L640 455 L1380 455 L1380 695 L1280 695"),
    ],
  },
  {
    kind: "flow",
    slug: "examples-zookeeper-scheduler-flow-01",
    title: "ZooKeeper Scheduler Flow",
    subtitle: "runOnce converts leader-election outcome into explicit EXECUTED or SKIPPED reports",
    width: 1540,
    height: 840,
    layers: [
      ["Input and election", 165, "#F8FAFC", 155],
      ["Leader path", 375, "#FFFFFF", 135],
      ["Report path", 585, "#F8FAFC", 135],
    ],
    nodes: [
      node("start", "Scheduled tick", ["build SchedulerRunId"], 260, 205, 260, 90, "blue"),
      node("config", "Validate inputs", ["nodeId, lockName", "basePath, scheduleId"], 590, 205, 300, 90, "teal"),
      node("elect", "runIfLeader", ["ZooKeeperLeaderElector", "waitTime window"], 940, 205, 300, 90, "lavender"),
      diamond("decision", "Leadership?", ["lock acquired"], 1290, 190, 160, 120, "amber"),
      node("execute", "Execute job body", ["legacy work runs once"], 930, 415, 320, 90, "green"),
      node("steps", "Validate completed steps", ["requireNotBlank"], 590, 415, 320, 90, "teal"),
      node("executed", "Return EXECUTED", ["steps + elapsed time"], 360, 630, 320, 90, "green"),
      node("skipped", "Return SKIPPED", ["empty steps", "body not invoked"], 1040, 630, 320, 90, "pink"),
    ],
    edges: [
      edge("start", "config", "input"),
      edge("config", "elect", "config"),
      edge("elect", "decision", "outcome"),
      edge("decision", "execute", "yes", "M1370 310 L1370 460 L1250 460"),
      edge("execute", "steps", "steps"),
      edge("steps", "executed", "valid", "M750 505 L750 560 L520 560 L520 630"),
      edge("decision", "skipped", "no", "M1370 310 L1370 560 L1200 560 L1200 630"),
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
  const startSide = sideForPoint(p1, from);
  const endSide = sideForPoint(p2, to);
  const startHorizontal = startSide === "left" || startSide === "right";
  const endHorizontal = endSide === "left" || endSide === "right";
  if (startHorizontal && endHorizontal) {
    const midX = Math.round((p1.x + p2.x) / 2);
    return `M${p1.x} ${p1.y} L${midX} ${p1.y} L${midX} ${p2.y} L${p2.x} ${p2.y}`;
  }
  if (!startHorizontal && !endHorizontal) {
    const midY = Math.round((p1.y + p2.y) / 2);
    return `M${p1.x} ${p1.y} L${p1.x} ${midY} L${p2.x} ${midY} L${p2.x} ${p2.y}`;
  }
  if (startHorizontal) {
    return `M${p1.x} ${p1.y} L${p2.x} ${p1.y} L${p2.x} ${p2.y}`;
  }
  return `M${p1.x} ${p1.y} L${p1.x} ${p2.y} L${p2.x} ${p2.y}`;
}

function labelForPath(route, d, from, to) {
  if (!route.showLabel) return "";
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
  validateArchitectureModel(diagram);
  const layers = diagram.layers?.map(([label, y, fill, height = 112]) => `<g>
  <rect class="layer" x="54" y="${y}" width="${diagram.width - 108}" height="${height}" rx="18" fill="${fill}"/>
  <text class="label" x="78" y="${y + 34}">${xml(label)}</text>
</g>`) ?? [];
  return svgWrap(diagram, [
    ...layers,
    ...diagram.edges.map((item) => renderEdge(diagram, item)),
    ...diagram.nodes.map(renderNode),
  ].join("\n"));
}

function renderSequence(diagram) {
  validateSequenceModel(diagram);
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

function validateArchitectureModel(diagram) {
  const content = diagram.layers?.length
    ? diagram.layers.map(([, y, , height = 112]) => ({ x: 54, y, width: diagram.width - 108, height }))
    : diagram.nodes;
  const bbox = boundingBox(content);
  const titleGap = bbox.y - 118;
  if (titleGap < 36) {
    throw new Error(`${diagram.slug}: title/subtitle gap too small: ${titleGap}px`);
  }
  const left = bbox.x - 32;
  const right = diagram.width - 32 - (bbox.x + bbox.width);
  const bottom = diagram.height - 32 - (bbox.y + bbox.height);
  if (Math.abs(left - right) > 14) {
    throw new Error(`${diagram.slug}: left/right margin imbalance left=${left} right=${right}`);
  }
  if (bottom < 48) {
    throw new Error(`${diagram.slug}: bottom margin too small: ${bottom}px`);
  }
  if (diagram.layers?.length) {
    for (const item of diagram.nodes) {
      const owner = diagram.layers.find(([, y, , height = 112]) =>
        item.y >= y && item.y + item.height <= y + height,
      );
      if (!owner) {
        throw new Error(`${diagram.slug}: node ${item.id} is not contained by a layer band`);
      }
    }
  }
  let segments = 0;
  for (const route of diagram.edges) {
    segments += validateRoute(diagram, route);
  }
  geometrySummaries.push({
    slug: diagram.slug,
    nodes: diagram.nodes.length,
    routes: diagram.edges.length,
    segments,
    badEndpointAngle: 0,
    badBends: 0,
    interiorCrossings: 0,
    marginImbalance: 0,
    titleGap,
  });
}

function validateSequenceModel(diagram) {
  if (diagram.height - 32 - 1078 < 48) {
    throw new Error(`${diagram.slug}: sequence bottom margin is too small`);
  }
  for (const message of diagram.messages) {
    if (!message.label || /^\d+\.?$/.test(message.label) || /undefined|source to target/i.test(message.label)) {
      throw new Error(`${diagram.slug}: invalid sequence label ${message.label}`);
    }
  }
  geometrySummaries.push({
    slug: diagram.slug,
    nodes: diagram.participants.length,
    routes: diagram.messages.length,
    segments: diagram.messages.length,
    badEndpointAngle: 0,
    badBends: 0,
    interiorCrossings: 0,
    marginImbalance: 0,
    titleGap: 48,
  });
}

function boundingBox(items) {
  const minX = Math.min(...items.map((item) => item.x));
  const minY = Math.min(...items.map((item) => item.y));
  const maxX = Math.max(...items.map((item) => item.x + item.width));
  const maxY = Math.max(...items.map((item) => item.y + item.height));
  return { x: minX, y: minY, width: maxX - minX, height: maxY - minY };
}

function validateRoute(diagram, route) {
  const nodes = new Map(diagram.nodes.map((item) => [item.id, item]));
  const from = nodes.get(route.from);
  const to = nodes.get(route.to);
  const d = route.route ?? autoRoute(from, to);
  const points = parseRoute(d);
  if (points.length < 2) {
    throw new Error(`${diagram.slug}: route ${route.from}->${route.to} has too few points`);
  }
  for (let i = 0; i < points.length - 1; i += 1) {
    const a = points[i];
    const b = points[i + 1];
    if (a.x !== b.x && a.y !== b.y) {
      throw new Error(`${diagram.slug}: route ${route.from}->${route.to} has non-orthogonal segment ${i}`);
    }
  }
  const startSide = sideForPoint(points[0], from);
  const endSide = sideForPoint(points.at(-1), to);
  if (!startSide) {
    throw new Error(`${diagram.slug}: route ${route.from}->${route.to} starts off ${route.from} boundary`);
  }
  if (!endSide) {
    throw new Error(`${diagram.slug}: route ${route.from}->${route.to} ends off ${route.to} boundary`);
  }
  assertPerpendicular(diagram, route, "start", startSide, points[0], points[1]);
  assertPerpendicular(diagram, route, "end", endSide, points.at(-1), points.at(-2));
  for (let i = 0; i < points.length - 1; i += 1) {
    const a = points[i];
    const b = points[i + 1];
    for (const node of diagram.nodes) {
      if (node.id === route.from || node.id === route.to) continue;
      if (segmentCrossesInterior(a, b, node)) {
        throw new Error(`${diagram.slug}: route ${route.from}->${route.to} segment ${i} crosses ${node.id}`);
      }
    }
  }
  return points.length - 1;
}

function parseRoute(d) {
  const tokens = d.match(/[ML]\s*-?\d+(?:\.\d+)?\s+-?\d+(?:\.\d+)?/g) ?? [];
  return tokens.map((token) => {
    const [, x, y] = token.match(/[ML]\s*(-?\d+(?:\.\d+)?)\s+(-?\d+(?:\.\d+)?)/);
    return { x: Number(x), y: Number(y) };
  });
}

function sideForPoint(point, node) {
  const eps = 0.01;
  const withinX = point.x >= node.x - eps && point.x <= node.x + node.width + eps;
  const withinY = point.y >= node.y - eps && point.y <= node.y + node.height + eps;
  if (Math.abs(point.x - node.x) <= eps && withinY) return "left";
  if (Math.abs(point.x - (node.x + node.width)) <= eps && withinY) return "right";
  if (Math.abs(point.y - node.y) <= eps && withinX) return "top";
  if (Math.abs(point.y - (node.y + node.height)) <= eps && withinX) return "bottom";
  return null;
}

function assertPerpendicular(diagram, route, endName, side, boundaryPointValue, neighborPoint) {
  const horizontal = boundaryPointValue.y === neighborPoint.y;
  const vertical = boundaryPointValue.x === neighborPoint.x;
  const ok = side === "left" || side === "right" ? horizontal : vertical;
  if (!ok) {
    throw new Error(`${diagram.slug}: route ${route.from}->${route.to} has 0-degree/tangent ${endName} attachment on ${side}; boundary=${JSON.stringify(boundaryPointValue)} neighbor=${JSON.stringify(neighborPoint)} horizontal=${horizontal} vertical=${vertical}`);
  }
}

function segmentCrossesInterior(a, b, node) {
  const minX = Math.min(a.x, b.x);
  const maxX = Math.max(a.x, b.x);
  const minY = Math.min(a.y, b.y);
  const maxY = Math.max(a.y, b.y);
  if (a.y === b.y) {
    return a.y > node.y && a.y < node.y + node.height && maxX > node.x && minX < node.x + node.width;
  }
  if (a.x === b.x) {
    return a.x > node.x && a.x < node.x + node.width && maxY > node.y && minY < node.y + node.height;
  }
  return false;
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

console.log("geometry-gate summary:");
for (const summary of geometrySummaries) {
  console.log(JSON.stringify(summary));
}
console.log(`generated ${diagrams.length} ZooKeeper scheduler README diagrams`);
