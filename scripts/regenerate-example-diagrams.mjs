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
      .frame { fill: ${colors.panel}; stroke: ${colors.frame}; stroke-width: 2.2; }
      .title { font-family: "Architects Daughter"; font-size: 42px; fill: ${colors.ink}; }
      .subtitle, .small, .labelText, .detail, .footer, .msg, .note { font-family: "Comic Mono"; fill: ${colors.text}; }
      .subtitle { font-size: 17px; }
      .footer { font-size: 13px; fill: ${colors.muted}; }
      .band { fill: #f5f9fb; stroke: #c8d7df; stroke-width: 1.6; }
      .bandAlt { fill: #fbfdf8; stroke: #d7e0d0; stroke-width: 1.6; }
      .bandTitle { font-family: "Architects Daughter"; font-size: 22px; fill: ${colors.ink}; }
      .bandHint { font-family: "Comic Mono"; font-size: 12px; fill: ${colors.muted}; }
      .card { filter: url(#softShadow); stroke-width: 2; rx: 8; }
      .cardTitle { font-family: "Architects Daughter"; font-size: 24px; fill: #1f3138; }
      .detail { font-size: 13.5px; fill: #546a73; }
      .edge { fill: none; stroke-width: 2.6; stroke-linecap: round; stroke-linejoin: round; }
      .edgeThin { fill: none; stroke-width: 2.3; stroke-linecap: round; stroke-linejoin: round; }
      .dash { stroke-dasharray: 10 8; }
      .pill { fill: #ffffff; stroke-width: 1.5; }
      .labelText { font-size: 12.5px; }
      .badgeText { font-family: "Comic Mono"; font-size: 12px; font-weight: 700; }
      .header { fill: #ffffff; stroke: #546e7a; stroke-width: 2.2; }
      .participant { font-family: "Architects Daughter"; font-size: 20px; fill: #1f3138; }
      .role { font-family: "Comic Mono"; font-size: 13px; fill: #546a73; }
      .lifeline { stroke: #9aaab1; stroke-width: 2; stroke-dasharray: 7 8; }
      .activation { fill: #e6f2ec; stroke: #5b7e67; stroke-width: 1.7; }
      .branch { fill: none; stroke: #78909c; stroke-width: 2.4; stroke-dasharray: 12 8; }
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
  return `<marker id="arrow-${id}" viewBox="0 0 10 10" markerUnits="userSpaceOnUse" markerWidth="10" markerHeight="10" refX="9" refY="5" orient="auto"><path d="M 0 0 L 10 5 L 0 10 Z" fill="${color}" stroke="${color}" stroke-width="0" stroke-dasharray="none" style="stroke-dasharray:none!important"/></marker>`;
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
  <rect x="84" y="150" width="352" height="760" rx="8" class="band"/>
  <text x="112" y="184" class="bandTitle">Request Lane</text>
  <text x="112" y="207" class="bandHint">trigger and candidate context</text>
  <rect x="460" y="150" width="352" height="760" rx="8" class="bandAlt"/>
  <text x="488" y="184" class="bandTitle">Election Lane</text>
  <text x="488" y="207" class="bandHint">API contract and guard</text>
  <rect x="836" y="150" width="352" height="760" rx="8" class="band"/>
  <text x="864" y="184" class="bandTitle">Work Lane</text>
  <text x="864" y="207" class="bandHint">leader branch and state</text>
  <rect x="1212" y="150" width="384" height="760" rx="8" class="bandAlt"/>
  <text x="1240" y="184" class="bandTitle">Observer Lane</text>
  <text x="1240" y="207" class="bandHint">peers, store, metrics</text>
  ${card(100, 255, 320, 90, system, trigger, "#eef7fb", colors.line)}
  ${card(100, 495, 320, 92, "Candidate Context", "deadline, key, tenant scope", "#ffffff", colors.slate)}
  ${card(476, 255, 320, 90, "Leader API", "runIfLeader / result contract", "#f8fbef", colors.work)}
  ${card(476, 495, 320, 92, "Election Guard", "try lock, lease, listener events", "#eef7f0", colors.work)}
  ${card(852, 495, 320, 92, "Leader Work", "one protected side effect", "#eef7f0", colors.work)}
  ${card(852, 735, 320, 92, "Outcome Record", "elected, skipped, or failed", "#fff7e8", colors.return)}
  ${card(1244, 255, 320, 90, "Peer Workers", "same lockName, same policy", "#fff8ed", colors.amber)}
  ${card(1244, 495, 320, 92, store, "lease or conditional ownership", "#fdf7ff", colors.purple)}
  ${card(1244, 735, 320, 92, "Observers", "metrics, logs, dashboards", "#fff5f5", colors.skip)}
  ${label(300, 382, 238, "candidate enters", colors.line, 1)}
  <path d="M 260 345 L 260 416 Q 260 440 284 440 L 636 440 Q 660 440 660 464 L 660 495" class="edge" stroke="${colors.line}" marker-end="url(#arrow-line)"/>
  ${label(1170, 350, 200, "peers contend", colors.amber, 2)}
  <path d="M 1404 345 L 1404 380 Q 1404 398 1386 398 L 775 398 Q 760 398 760 413 L 760 495" class="edge" stroke="${colors.amber}" marker-end="url(#arrow-amber)"/>
  ${label(860, 350, 238, "ownership stored", colors.purple, 3)}
  <path d="M 796 541 L 820 541 Q 836 541 836 525 L 836 438 Q 836 416 858 416 L 1388 416 Q 1404 416 1404 432 L 1404 495" class="edge" stroke="${colors.purple}" marker-end="url(#arrow-purple)"/>
  ${label(690, 604, 228, "leader branch", colors.work, 4)}
  <path d="M 796 541 L 852 541" class="edge" stroke="${colors.work}" marker-end="url(#arrow-work)"/>
  ${label(885, 842, 180, "state visible", colors.return, 5)}
  <path d="M 1012 587 L 1012 735" class="edge" stroke="${colors.return}" marker-end="url(#arrow-return)"/>
  ${label(1265, 856, 190, "metrics explain", colors.skip, 6)}
  <path d="M 1172 781 L 1244 781" class="edge" stroke="${colors.skip}" marker-end="url(#arrow-skip)"/>
  `;
  return shell(`${title} Architecture`, `${system} uses one elected worker while peers observe explicit skipped or failed outcomes.`, `Architecture diagram for ${title}.`, body);
}

function flow(file) {
  const { slug, title } = parseName(file);
  const [system, trigger, store] = domain(slug);
  const body = `
  <rect x="84" y="150" width="430" height="850" rx="8" class="band"/>
  <text x="112" y="184" class="bandTitle">Failure / Retry Lane</text>
  <text x="112" y="207" class="bandHint">errors and later attempts stay off the main lane</text>
  <rect x="552" y="150" width="576" height="850" rx="8" class="bandAlt"/>
  <text x="580" y="132" class="bandTitle">Main Scenario Lane</text>
  <text x="580" y="154" class="bandHint">vertical main path</text>
  <rect x="1166" y="150" width="430" height="850" rx="8" class="band"/>
  <text x="1194" y="184" class="bandTitle">Skipped / Next Lane</text>
  <text x="1194" y="207" class="bandHint">peer and loopback paths stay outside cards</text>
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
  ${label(930, 246, 210, "request context", colors.line, 1)}
  <path d="M 840 240 L 840 299" class="edge" stroke="${colors.line}" marker-end="url(#arrow-line)"/>
  ${label(930, 381, 205, "lock attempt", colors.work, 2)}
  <path d="M 840 375 L 840 434" class="edge" stroke="${colors.work}" marker-end="url(#arrow-work)"/>
  <path d="M 840 510 L 840 535" class="edge" stroke="${colors.amber}" marker-end="url(#arrow-amber)"/>
  ${label(542, 492, 185, "exception", colors.skip, 3)}
  <path d="M 698 570 L 530 570" class="edge" stroke="${colors.skip}" marker-end="url(#arrow-skip)"/>
  ${label(986, 492, 178, "not leader", colors.amber, 4)}
  <path d="M 982 570 L 1150 570" class="edge" stroke="${colors.amber}" marker-end="url(#arrow-amber)"/>
  ${label(930, 612, 178, "leader only", colors.work, 5)}
  <path d="M 840 605 L 840 652" class="edge" stroke="${colors.work}" marker-end="url(#arrow-work)"/>
  <path d="M 840 728 L 840 792" class="edge" stroke="${colors.return}" marker-end="url(#arrow-return)"/>
  <path d="M 840 868 L 840 884" class="edge" stroke="${colors.line}" marker-end="url(#arrow-line)"/>
  ${label(1235, 952, 186, "next cycle", colors.purple, 6)}
  <path d="M 840 960 L 840 986 Q 840 1000 854 1000 L 1530 1000 Q 1544 1000 1544 986 L 1544 140 Q 1544 126 1530 126 L 880 126 Q 840 126 840 140 L 840 164" class="edge dash" stroke="${colors.purple}"/>
  <polygon points="840,164 832,148 848,148" fill="${colors.purple}" stroke="${colors.purple}" stroke-width="0" stroke-dasharray="none" style="stroke-dasharray:none!important"/>
  `;
  return shell(`${title} Flow`, `${system} follows trigger, prepare, election, protected work, and observable outcome bands.`, `Flow diagram for ${title}.`, body);
}

function scenario(file) {
  const { slug, title } = parseName(file);
  const [system, trigger, store] = domain(slug);
  const body = `
  <rect x="84" y="150" width="430" height="938" rx="8" class="band"/>
  <text x="112" y="184" class="bandTitle">Retry Lane</text>
  <text x="112" y="207" class="bandHint">backoff path stays outside the main line</text>
  <rect x="552" y="150" width="576" height="938" rx="8" class="bandAlt"/>
  <text x="580" y="132" class="bandTitle">Main Scenario Lane</text>
  <text x="580" y="154" class="bandHint">vertical main path</text>
  <rect x="1166" y="150" width="430" height="938" rx="8" class="band"/>
  <text x="1194" y="184" class="bandTitle">Skipped Lane</text>
  <text x="1194" y="207" class="bandHint">non-leader path rejoins at visible result</text>
  ${card(630, 168, 420, 82, "1. Trigger", `${system}: ${trigger}`, "#eef7fb", colors.line)}
  ${card(630, 318, 420, 82, "2. Build Claim", `${store} key + policy`, "#ffffff", colors.slate)}
  <polygon points="840,455 1000,500 840,545 680,500" fill="#fff8ed" stroke="${colors.amber}" stroke-width="2.4" filter="url(#softShadow)"/>
  <text x="840" y="496" text-anchor="middle" class="cardTitle">3. Acquire?</text>
  <text x="840" y="518" text-anchor="middle" class="detail">single leader decision</text>
  ${card(210, 620, 320, 82, "Retry", "backoff or next tick", "#fdf7ff", colors.purple)}
  ${card(1150, 620, 320, 82, "Skipped", "peer returns quickly", "#fff8ed", colors.amber)}
  ${card(630, 690, 420, 82, "4. Leader Work", "perform protected branch", "#eef7f0", colors.work)}
  ${card(630, 840, 420, 82, "5. Persist Outcome", "state, metric, and audit event", "#fff7e8", colors.return)}
  ${card(630, 990, 420, 82, "6. Visible Result", "caller receives explicit outcome", "#eef7fb", colors.line)}
  ${label(930, 270, 160, "normalize", colors.line, 1)}
  <path d="M 840 250 L 840 318" class="edge" stroke="${colors.line}" marker-end="url(#arrow-line)"/>
  ${label(930, 420, 170, "contend", colors.amber, 2)}
  <path d="M 840 400 L 840 455" class="edge" stroke="${colors.amber}" marker-end="url(#arrow-amber)"/>
  ${label(930, 575, 190, "leader branch", colors.work, 3)}
  <path d="M 840 545 L 840 690" class="edge" stroke="${colors.work}" marker-end="url(#arrow-work)"/>
  ${label(458, 454, 160, "retry", colors.purple, 4)}
  <path d="M 680 500 L 370 500 Q 350 500 350 520 L 350 620" class="edge dash" stroke="${colors.purple}"/>
  <polygon points="350,620 342,604 358,604" fill="${colors.purple}" stroke="${colors.purple}" stroke-width="0" stroke-dasharray="none" style="stroke-dasharray:none!important"/>
  ${label(1112, 454, 160, "skipped", colors.amber, 5)}
  <path d="M 1000 500 L 1330 500 Q 1350 500 1350 520 L 1350 620" class="edge" stroke="${colors.amber}" marker-end="url(#arrow-amber)"/>
  ${label(930, 790, 210, "persist outcome", colors.return, 6)}
  <path d="M 840 772 L 840 840" class="edge" stroke="${colors.return}" marker-end="url(#arrow-return)"/>
  ${label(930, 940, 190, "visible result", colors.line, 7)}
  <path d="M 840 922 L 840 990" class="edge" stroke="${colors.line}" marker-end="url(#arrow-line)"/>
  <path d="M 370 702 L 370 1018 Q 370 1031 383 1031 L 630 1031" class="edge dash" stroke="${colors.purple}"/>
  <polygon points="630,1031 614,1023 614,1039" fill="${colors.purple}" stroke="${colors.purple}" stroke-width="0" stroke-dasharray="none" style="stroke-dasharray:none!important"/>
  <path d="M 1350 702 L 1350 1018 Q 1350 1031 1337 1031 L 1050 1031" class="edge" stroke="${colors.amber}" marker-end="url(#arrow-amber)"/>
  `;
  return shell(`${title} Scenario`, `${system} now follows a vertical trigger-to-result scenario with side branches kept off the main lane.`, `Scenario diagram for ${title}.`, body, 1680, 1160);
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
