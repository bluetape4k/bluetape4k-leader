#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const dir = path.join(root, "docs/images/readme-diagrams");
const targets = fs
  .readdirSync(dir)
  .filter((name) => /^examples-.+-(architecture|flow|scenario|sequence)-01\.svg$/.test(name))
  .sort();

const words = {
  k8s: "K8s",
  ktor: "Ktor",
  etcd: "etcd",
  redisson: "Redisson",
  zookeeper: "ZooKeeper",
  dynamodb: "DynamoDB",
  consul: "Consul",
  prometheus: "Prometheus",
  redis: "Redis",
};

const domainBySlug = {
  "batch-scheduler": ["Scheduler", "batch trigger", "Quartz-style tick"],
  "cache-warmer": ["Cache", "warm keyspace", "Redis population"],
  "consul-maintenance": ["Consul", "session maintenance", "KV lock"],
  "dynamodb-export": ["DynamoDB", "export partition", "conditional write"],
  "etcd-reconciler": ["etcd", "reconcile loop", "lease key"],
  "k8s-lease": ["K8s Lease", "controller lease", "coordination API"],
  "k8s-operator": ["K8s Operator", "custom resource", "reconcile claim"],
  "ktor-app": ["Ktor", "HTTP worker", "request guard"],
  "migration-gate": ["Migration", "schema gate", "one writer"],
  "prometheus-dashboard": ["Prometheus", "metrics scrape", "dashboard refresh"],
  "rate-limiter": ["Rate Limit", "bucket refill", "shared quota"],
  "redisson-watchdog": ["Redisson", "watchdog renew", "Redis lock"],
  "strategic-election": ["Strategy", "elector policy", "candidate scoring"],
  "tenant-aggregator": ["Tenant", "fan-in window", "aggregate snapshot"],
  "virtual-thread-runner": ["Virtual Thread", "blocking job", "cheap carrier"],
  "webhook-poller": ["Webhook", "remote poll", "delivery cursor"],
  "zookeeper-scheduler": ["ZooKeeper", "ephemeral znode", "scheduled leader"],
};

const colors = {
  ink: "#263238",
  text: "#36464f",
  muted: "#60727d",
  frame: "#41545d",
  line: "#3f7d9c",
  work: "#6e8f4f",
  return: "#9b7d54",
  skip: "#b86868",
  amber: "#c97831",
  purple: "#7c5aa6",
  slate: "#78909c",
  bg: "#fbfcf8",
  panel: "#ffffff",
};

function esc(value) {
  return String(value).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

function titleCase(slug) {
  return slug
    .split("-")
    .map((part) => words[part] || part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}

function parseName(name) {
  const match = name.match(/^examples-(.+)-(architecture|flow|scenario|sequence)-01\.svg$/);
  return { slug: match[1], kind: match[2], title: titleCase(match[1]) };
}

function domain(slug) {
  return domainBySlug[slug] || [titleCase(slug), "work request", "shared lock"];
}

function defs(width = 1680, height = 1040) {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" role="img" aria-labelledby="title desc">
  <defs>
    <filter id="softShadow" x="-12%" y="-12%" width="124%" height="124%">
      <feDropShadow dx="0" dy="8" stdDeviation="8" flood-color="#263238" flood-opacity="0.14"/>
    </filter>
    <style>
      .canvas { fill: ${colors.bg}; }
      .frame { fill: ${colors.panel}; stroke: ${colors.frame}; stroke-width: 3; }
      .title { font-family: "Architects Daughter"; font-size: 42px; fill: ${colors.ink}; }
      .subtitle, .small, .labelText, .detail, .footer, .msg, .note { font-family: "Comic Mono"; fill: ${colors.text}; }
      .subtitle { font-size: 17px; }
      .footer { font-size: 13px; fill: ${colors.muted}; }
      .band { fill: #f5f9fb; stroke: #c8d7df; stroke-width: 1.6; }
      .bandAlt { fill: #fbfdf8; stroke: #d7e0d0; stroke-width: 1.6; }
      .bandTitle { font-family: "Architects Daughter"; font-size: 22px; fill: ${colors.ink}; }
      .bandHint { font-family: "Comic Mono"; font-size: 12px; fill: ${colors.muted}; }
      .card { filter: url(#softShadow); stroke-width: 2.4; rx: 8; }
      .cardTitle { font-family: "Architects Daughter"; font-size: 24px; fill: #1f3138; }
      .detail { font-size: 13.5px; fill: #546a73; }
      .edge { fill: none; stroke-width: 3.4; stroke-linecap: round; stroke-linejoin: round; }
      .edgeThin { fill: none; stroke-width: 3; stroke-linecap: round; stroke-linejoin: round; }
      .dash { stroke-dasharray: 10 8; }
      .pill { fill: #ffffff; stroke-width: 1.5; }
      .labelText { font-size: 12.5px; }
      .badgeText { font-family: "Comic Mono"; font-size: 12px; font-weight: 700; }
      .header { fill: #ffffff; stroke: #546e7a; stroke-width: 2.2; }
      .participant { font-family: "Architects Daughter"; font-size: 20px; fill: #1f3138; }
      .role { font-family: "Comic Mono"; font-size: 13px; fill: #546a73; }
      .lifeline { stroke: #9aaab1; stroke-width: 2; stroke-dasharray: 7 8; }
      .activation { fill: #e6f2ec; stroke: #5b7e67; stroke-width: 1.7; }
      .branch { fill: none; stroke: #78909c; stroke-width: 3; stroke-dasharray: 12 8; }
    </style>
    ${marker("line", colors.line)}
    ${marker("work", colors.work)}
    ${marker("return", colors.return)}
    ${marker("skip", colors.skip)}
    ${marker("amber", colors.amber)}
    ${marker("purple", colors.purple)}
    ${marker("slate", colors.slate)}
  </defs>`;
}

function marker(id, color) {
  return `<marker id="arrow-${id}" viewBox="0 0 14 14" markerUnits="userSpaceOnUse" markerWidth="14" markerHeight="14" refX="12" refY="7" orient="auto"><path d="M 1 1 L 13 7 L 1 13 Z" fill="${color}" stroke="${color}" stroke-width="0" stroke-dasharray="none" style="stroke-dasharray:none!important"/></marker>`;
}

function label(x, y, w, text, color, n) {
  w = Math.max(w, text.length * 8 + 66);
  return `<g>
    <rect x="${x}" y="${y}" width="${w}" height="32" rx="16" class="pill" stroke="${color}"/>
    <circle cx="${x + 22}" cy="${y + 16}" r="12.5" fill="#fff" stroke="${color}" stroke-width="1.6"/>
    <text x="${x + 22}" y="${y + 20}" text-anchor="middle" class="badgeText" fill="${color}">${n}</text>
    <text x="${x + 43}" y="${y + 21}" class="labelText" fill="${color}">${esc(text)}</text>
  </g>`;
}

function card(x, y, w, h, title, detail, fill, stroke) {
  return `<g>
    <rect x="${x}" y="${y}" width="${w}" height="${h}" rx="8" class="card" fill="${fill}" stroke="${stroke}"/>
    <text x="${x + w / 2}" y="${y + 34}" text-anchor="middle" class="cardTitle">${esc(title)}</text>
    <text x="${x + w / 2}" y="${y + 58}" text-anchor="middle" class="detail">${esc(detail)}</text>
  </g>`;
}

function shell(title, subtitle, desc, body, width = 1680, height = 1040) {
  return `${defs(width, height)}
  <title id="title">${esc(title)}</title>
  <desc id="desc">${esc(desc)}</desc>
  <rect width="${width}" height="${height}" class="canvas"/>
  <rect x="28" y="26" width="${width - 56}" height="${height - 52}" rx="8" class="frame"/>
  <text x="80" y="82" class="title">${esc(title)}</text>
  <text x="82" y="114" class="subtitle">${esc(subtitle)}</text>
${body}
</svg>
`.replace(/[ \t]+$/gm, "");
}

function architecture(file) {
  const { slug, title } = parseName(file);
  const [system, trigger, store] = domain(slug);
  const body = `
  <rect x="84" y="150" width="1512" height="180" rx="8" class="band"/>
  <text x="112" y="184" class="bandTitle">Trigger Layer</text>
  <text x="112" y="207" class="bandHint">external request, timer, or controller callback</text>
  <rect x="84" y="370" width="1512" height="220" rx="8" class="bandAlt"/>
  <text x="112" y="404" class="bandTitle">Leader Election Layer</text>
  <text x="112" y="427" class="bandHint">single winner gates the critical branch</text>
  <rect x="84" y="630" width="1512" height="220" rx="8" class="band"/>
  <text x="112" y="664" class="bandTitle">Work and State Layer</text>
  <text x="112" y="687" class="bandHint">protected work records visible state</text>
  ${card(250, 200, 340, 90, system, trigger, "#eef7fb", colors.line)}
  ${card(670, 200, 340, 90, "Leader API", "runIfLeader / result contract", "#f8fbef", colors.work)}
  ${card(1090, 200, 340, 90, "Peer Workers", "same lockName, same policy", "#fff8ed", colors.amber)}
  ${card(250, 435, 340, 92, "Candidate Context", "deadline, key, tenant scope", "#ffffff", colors.slate)}
  ${card(670, 435, 340, 92, "Election Guard", "try lock, lease, listener events", "#eef7f0", colors.work)}
  ${card(1090, 435, 340, 92, store, "lease or conditional ownership", "#fdf7ff", colors.purple)}
  ${card(250, 700, 340, 92, "Leader Work", "one protected side effect", "#eef7f0", colors.work)}
  ${card(670, 700, 340, 92, "Outcome Record", "elected, skipped, or failed", "#fff7e8", colors.return)}
  ${card(1090, 700, 340, 92, "Observers", "metrics, logs, dashboards", "#fff5f5", colors.skip)}
  ${label(608, 333, 248, "candidate enters election", colors.line, 1)}
  <path d="M 420 290 L 420 346 Q 420 370 444 370 L 840 370 Q 864 370 864 394 L 864 435" class="edge" stroke="${colors.line}" marker-end="url(#arrow-line)"/>
  ${label(948, 333, 226, "peers contend", colors.amber, 2)}
  <path d="M 1260 290 L 1260 346 Q 1260 370 1236 370 L 864 370" class="edge" stroke="${colors.amber}" marker-end="url(#arrow-amber)"/>
  ${label(1045, 535, 260, "ownership stored", colors.purple, 3)}
  <path d="M 1010 481 L 1090 481" class="edge" stroke="${colors.purple}" marker-end="url(#arrow-purple)"/>
  ${label(528, 592, 242, "leader branch opens", colors.work, 4)}
  <path d="M 840 527 L 840 592 Q 840 630 802 630 L 420 630 Q 396 630 396 654 L 396 700" class="edge" stroke="${colors.work}" marker-end="url(#arrow-work)"/>
  ${label(608, 856, 252, "state becomes visible", colors.return, 5)}
  <path d="M 590 746 L 670 746" class="edge" stroke="${colors.return}" marker-end="url(#arrow-return)"/>
  ${label(1038, 856, 234, "metrics explain result", colors.skip, 6)}
  <path d="M 1010 746 L 1090 746" class="edge" stroke="${colors.skip}" marker-end="url(#arrow-skip)"/>
  `;
  return shell(`${title} Architecture`, `${system} uses one elected worker while peers observe explicit skipped or failed outcomes.`, `Architecture diagram for ${title}.`, body);
}

function flow(file) {
  const { slug, title } = parseName(file);
  const [system, trigger, store] = domain(slug);
  const body = `
  <rect x="84" y="150" width="1512" height="105" rx="8" class="band"/>
  <text x="112" y="184" class="bandTitle">Trigger</text><text x="112" y="207" class="bandHint">${esc(trigger)}</text>
  <rect x="84" y="285" width="1512" height="105" rx="8" class="bandAlt"/>
  <text x="112" y="319" class="bandTitle">Prepare</text><text x="112" y="342" class="bandHint">normalize lockName and deadline</text>
  <rect x="84" y="420" width="1512" height="190" rx="8" class="band"/>
  <text x="112" y="454" class="bandTitle">Election</text><text x="112" y="477" class="bandHint">winner, skipped peer, or failure branch</text>
  <rect x="84" y="640" width="1512" height="105" rx="8" class="bandAlt"/>
  <text x="112" y="674" class="bandTitle">Leader Work</text><text x="112" y="697" class="bandHint">single protected side effect</text>
  <rect x="84" y="780" width="1512" height="150" rx="8" class="band"/>
  <text x="112" y="814" class="bandTitle">Outcome</text><text x="112" y="837" class="bandHint">record, return, and next cycle</text>
  ${card(630, 164, 420, 76, `1. ${system}`, trigger, "#eef7fb", colors.line)}
  ${card(630, 299, 420, 76, "2. Prepare Context", `${store} + wait budget`, "#ffffff", colors.slate)}
  ${card(630, 434, 420, 76, "3. Try Leadership", "attempt ownership once", "#f8fbef", colors.work)}
  <polygon points="840,535 982,570 840,605 698,570" fill="#fff8ed" stroke="${colors.amber}" stroke-width="2.4" filter="url(#softShadow)"/>
  <text x="840" y="566" text-anchor="middle" class="cardTitle">4. Acquired?</text>
  <text x="840" y="588" text-anchor="middle" class="detail">branch on election result</text>
  ${card(200, 531, 330, 78, "Failure", "record thrown error", "#fff5f5", colors.skip)}
  ${card(1150, 531, 330, 78, "Skipped Peer", "return no-op result", "#fff8ed", colors.amber)}
  ${card(630, 652, 420, 76, "5. Run Protected Work", "only elected worker proceeds", "#eef7f0", colors.work)}
  ${card(630, 792, 420, 76, "6. Record Outcome", "success, skipped, or failed", "#fff7e8", colors.return)}
  ${card(630, 884, 420, 76, "7. Return Result", "release lease and report", "#eef7fb", colors.line)}
  ${label(875, 257, 210, "request context", colors.line, 1)}
  <path d="M 840 240 L 840 299" class="edge" stroke="${colors.line}" marker-end="url(#arrow-line)"/>
  ${label(875, 392, 205, "lock attempt", colors.work, 2)}
  <path d="M 840 375 L 840 434" class="edge" stroke="${colors.work}" marker-end="url(#arrow-work)"/>
  <path d="M 840 510 L 840 535" class="edge" stroke="${colors.amber}" marker-end="url(#arrow-amber)"/>
  ${label(525, 535, 185, "exception", colors.skip, 3)}
  <path d="M 698 570 L 530 570" class="edge" stroke="${colors.skip}" marker-end="url(#arrow-skip)"/>
  ${label(986, 535, 178, "not leader", colors.amber, 4)}
  <path d="M 982 570 L 1150 570" class="edge" stroke="${colors.amber}" marker-end="url(#arrow-amber)"/>
  ${label(875, 612, 178, "leader only", colors.work, 5)}
  <path d="M 840 605 L 840 652" class="edge" stroke="${colors.work}" marker-end="url(#arrow-work)"/>
  <path d="M 840 728 L 840 792" class="edge" stroke="${colors.return}" marker-end="url(#arrow-return)"/>
  <path d="M 840 868 L 840 884" class="edge" stroke="${colors.line}" marker-end="url(#arrow-line)"/>
  ${label(236, 952, 186, "next cycle", colors.purple, 6)}
  <path d="M 840 960 L 840 986 Q 840 1000 826 1000 L 150 1000 Q 136 1000 136 986 L 136 202 Q 136 184 154 184 L 630 184" class="edge dash" stroke="${colors.purple}"/>
  <polygon points="630,184 614,176 614,192" fill="${colors.purple}" stroke="${colors.purple}" stroke-width="0" stroke-dasharray="none" style="stroke-dasharray:none!important"/>
  `;
  return shell(`${title} Flow`, `${system} follows trigger, prepare, election, protected work, and observable outcome bands.`, `Flow diagram for ${title}.`, body);
}

function scenario(file) {
  const { slug, title } = parseName(file);
  const [system, trigger, store] = domain(slug);
  const body = `
  <rect x="84" y="150" width="1512" height="720" rx="8" class="band"/>
  <text x="112" y="184" class="bandTitle">Scenario Workflow</text>
  <text x="112" y="207" class="bandHint">best-practices branching layout with explicit success, skipped, and retry lanes</text>
  ${card(150, 260, 315, 96, "Trigger", `${system}: ${trigger}`, "#eef7fb", colors.line)}
  ${card(535, 260, 315, 96, "Build Claim", `${store} key + policy`, "#ffffff", colors.slate)}
  ${card(920, 260, 315, 96, "Acquire", "single leader decision", "#fff8ed", colors.amber)}
  ${card(535, 510, 315, 96, "Leader Work", "perform protected branch", "#eef7f0", colors.work)}
  ${card(920, 510, 315, 96, "Persist Outcome", "state, metric, and audit event", "#fff7e8", colors.return)}
  ${card(1295, 510, 240, 96, "Skipped", "peer returns quickly", "#fff8ed", colors.amber)}
  ${card(150, 510, 240, 96, "Retry", "backoff or next tick", "#fdf7ff", colors.purple)}
  ${card(725, 735, 315, 96, "Visible Result", "caller receives explicit outcome", "#eef7fb", colors.line)}
  ${label(392, 378, 165, "normalize", colors.line, 1)}
  <path d="M 465 308 L 535 308" class="edge" stroke="${colors.line}" marker-end="url(#arrow-line)"/>
  ${label(782, 378, 154, "contend", colors.amber, 2)}
  <path d="M 850 308 L 920 308" class="edge" stroke="${colors.amber}" marker-end="url(#arrow-amber)"/>
  ${label(765, 438, 190, "leader branch", colors.work, 3)}
  <path d="M 1078 356 L 1078 430 Q 1078 454 1054 454 L 692 454 Q 668 454 668 478 L 668 510" class="edge" stroke="${colors.work}" marker-end="url(#arrow-work)"/>
  ${label(1245, 438, 152, "skipped", colors.amber, 4)}
  <path d="M 1235 308 L 1376 308 Q 1400 308 1400 332 L 1400 510" class="edge" stroke="${colors.amber}" marker-end="url(#arrow-amber)"/>
  ${label(392, 586, 160, "backoff", colors.purple, 5)}
  <path d="M 535 558 L 390 558" class="edge dash" stroke="${colors.purple}"/>
  <polygon points="390,558 406,550 406,566" fill="${colors.purple}" stroke="${colors.purple}" stroke-width="0" stroke-dasharray="none" style="stroke-dasharray:none!important"/>
  <path d="M 850 558 L 920 558" class="edge" stroke="${colors.return}" marker-end="url(#arrow-return)"/>
  <path d="M 1078 606 L 1078 682 Q 1078 706 1054 706 L 906 706 Q 882 706 882 730 L 882 735" class="edge" stroke="${colors.line}" marker-end="url(#arrow-line)"/>
  <path d="M 1400 606 L 1400 682 Q 1400 706 1376 706 L 906 706" class="edge" stroke="${colors.amber}" marker-end="url(#arrow-amber)"/>
  `;
  return shell(`${title} Scenario`, `${system} shows the happy path plus skipped and retry outcomes without crossing cards.`, `Scenario diagram for ${title}.`, body);
}

function sequence(file) {
  const { slug, title } = parseName(file);
  const [system, trigger, store] = domain(slug);
  const xs = [155, 410, 675, 950, 1215, 1450];
  const header = (x, w, p, r) => `<rect x="${x - w / 2}" y="150" width="${w}" height="68" rx="8" class="header"/><text x="${x}" y="179" text-anchor="middle" class="participant">${esc(p)}</text><text x="${x}" y="201" text-anchor="middle" class="role">${esc(r)}</text>`;
  const msg = (n, y, x1, x2, text, color, marker) => `${label(Math.min(x1, x2) + 20, y - 52, Math.abs(x2 - x1) - 40, text, color, n)}
  <path d="M ${x1} ${y} L ${x2} ${y}" class="edge" stroke="${color}" marker-end="url(#arrow-${marker})"/>`;
  const body = `
  ${header(xs[0], 170, "Caller", trigger)}
  ${header(xs[1], 190, "Example", system)}
  ${header(xs[2], 210, "Leader API", "runIfLeader")}
  ${header(xs[3], 210, "Lock Store", store)}
  ${header(xs[4], 190, "Action", "protected work")}
  ${header(xs[5], 170, "Observer", "metrics/events")}
  ${xs.map((x) => `<line x1="${x}" y1="218" x2="${x}" y2="938" class="lifeline"/>`).join("\n  ")}
  <rect x="${xs[2] - 7}" y="316" width="14" height="520" rx="6" class="activation"/>
  <rect x="${xs[3] - 7}" y="392" width="14" height="216" rx="6" class="activation"/>
  <rect x="${xs[4] - 7}" y="560" width="14" height="112" rx="6" class="activation"/>
  ${msg(1, 318, xs[0], xs[2], "submit leader-guarded request", colors.line, "line")}
  ${msg(2, 394, xs[2], xs[3], "try acquire lease / conditional claim", colors.work, "work")}
  ${msg(3, 480, xs[3], xs[2], "acquired or skipped outcome", colors.return, "return")}
  <rect x="82" y="518" width="1420" height="270" rx="12" class="branch"/>
  <text x="112" y="548" class="note">alt acquired</text>
  <line x1="82" y1="690" x2="1502" y2="690" class="branch"/>
  <text x="112" y="720" class="note">else skipped / failed</text>
  ${msg(4, 590, xs[2], xs[4], "invoke protected work once", colors.work, "work")}
  ${msg(5, 668, xs[4], xs[2], "return value or action failure", colors.return, "return")}
  ${msg(6, 754, xs[2], xs[5], "publish skipped/failed event", colors.skip, "skip")}
  ${msg(7, 838, xs[2], xs[3], "release lease and state", colors.return, "return")}
  ${msg(8, 910, xs[2], xs[0], "return explicit result to caller", colors.line, "line")}
  `;
  return shell(`${title} Sequence`, `${system} follows the leader-core sequence style with clear calls, returns, and alternate outcomes.`, `Sequence diagram for ${title}.`, body, 1600, 1020);
}

const renderers = { architecture, flow, scenario, sequence };

for (const file of targets) {
  const { kind } = parseName(file);
  fs.writeFileSync(path.join(dir, file), renderers[kind](file), "utf8");
}

console.log(`regenerated=${targets.length}`);
