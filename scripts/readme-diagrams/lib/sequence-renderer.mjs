import { diagramDir, esc, escDot, palette, routeTones, svgShell, wrap, writeGraphvizEvidence, writeSvgPng } from "./svg-core.mjs";
import { boxesOverlap, formatGeometrySummary, geometrySummaryBase, measureMargins } from "./geometry-gates.mjs";

export function msg(from, to, label, tone = "call", returns = false) {
  return { from, to, label, tone, returns };
}

export function branch(label) {
  return { branch: true, label };
}

export function renderSequenceDiagram(diagram) {
  validateSequence(diagram);
  const events = diagram.events;
  const width = diagram.width || Math.max(1280, 210 + diagram.participants.length * 340);
  const participantW = Math.min(260, Math.max(210, Math.floor((width - 256) / diagram.participants.length) - 50));
  const left = 128;
  const right = width - 128;
  const step = diagram.participants.length > 1 ? (right - left - participantW) / (diagram.participants.length - 1) : 0;
  const centers = diagram.participants.map((_, index) => left + participantW / 2 + index * step);
  const headerY = 228;
  const headerH = 76;
  const rowStart = 388;
  const rowGap = 82;
  const height = rowStart + events.length * rowGap + 124;
  const bottom = height - 124;
  const geometry = validateSequenceGeometry({ ...diagram, width, height }, centers, participantW, headerY, headerH, bottom);
  const participantSvg = diagram.participants.map((participant, index) => renderParticipant(participant, centers[index], participantW, headerY, headerH, bottom)).join("\n");
  const eventSvg = events.map((event, index) => event.branch
    ? renderBranch(event.label, 128, rowStart + index * rowGap - 35, width - 256, centers)
    : renderMessage(event, index + 1, centers, rowStart + index * rowGap)).join("\n");
  const svg = svgShell({ ...diagram, width, height, sequence: true, body: `${participantSvg}\n${eventSvg}` });
  const base = `${diagramDir}/${diagram.slug}`;
  console.log(formatGeometrySummary(diagram.slug, geometry));
  writeGraphvizEvidence(base, sequenceDot(diagram));
  writeSvgPng(base, svg);
  console.log(`${diagram.slug}: kind=sequence participants=${diagram.participants.length} events=${events.length} baseline=projects-sequence`);
}

function sequenceDot(diagram) {
  const nodes = diagram.participants.map((participant) => `  "${participant.id}" [label="${escDot(participant.label)}"];`).join("\n");
  const edges = diagram.events
    .filter((event) => !event.branch)
    .map((event) => `  "${diagram.participants[event.from].id}" -> "${diagram.participants[event.to].id}" [label="${escDot(event.label)}", color="${routeTones[event.tone] || routeTones.call}"];`)
    .join("\n");
  return `digraph "${diagram.slug}" {
  graph [rankdir=LR, bgcolor="transparent", splines=ortho, nodesep=0.7, ranksep=0.9];
  node [shape=box, style="rounded,filled", fillcolor="#F8FAFC", color="#94A3B8", fontname="Comic Mono"];
  edge [fontname="Comic Mono", fontsize=10, arrowsize=0.7];
${nodes}
${edges}
}
`;
}

function renderParticipant(participant, cx, width, y, height, bottom) {
  const color = palette[participant.color] || palette.gray;
  const lines = wrap(participant.label, 18).slice(0, 2);
  const first = lines.length === 1 ? y + 45 : y + 32;
  return `<g id="participant-${participant.id}">
  <rect class="card" x="${cx - width / 2}" y="${y}" width="${width}" height="${height}" rx="10" fill="${color.fill}" stroke="${color.stroke}"/>
${lines.map((line, index) => `  <text class="cardTitle" x="${cx}" y="${first + index * 23}" text-anchor="middle">${esc(line)}</text>`).join("\n")}
  <line class="lifeline" x1="${cx}" y1="${y + height}" x2="${cx}" y2="${bottom}"/>
</g>`;
}

function renderBranch(label, x, y, width, centers) {
  const maxGap = centers.length > 1 ? Math.max(170, centers[1] - centers[0] - 42) : 320;
  const pillW = Math.min(maxGap, Math.max(180, label.length * 7.5 + 44));
  const firstGapMid = centers.length > 1 ? (centers[0] + centers[1]) / 2 : x + 24 + pillW / 2;
  const pillX = Math.min(x + width - pillW - 24, Math.max(x + 24, firstGapMid - pillW / 2));
  return `<g>
  <rect class="branchBox" x="${x}" y="${y}" width="${width}" height="46" rx="15"/>
  <rect class="pill" x="${pillX}" y="${y - 6}" width="${pillW}" height="30" rx="8"/>
  <text class="detail" x="${pillX + pillW / 2}" y="${y + 10}" text-anchor="middle" dominant-baseline="middle">${esc(label)}</text>
</g>`;
}

function renderMessage(event, number, centers, y) {
  const x1 = centers[event.from];
  const x2 = centers[event.to];
  const tone = event.tone || "call";
  const color = routeTones[tone] || routeTones.call;
  const minX = Math.min(x1, x2);
  const maxX = Math.max(x1, x2);
  const available = Math.max(240, maxX - minX - 28);
  const lines = wrap(event.label, Math.max(26, Math.floor((available - 110) / 7.2))).slice(0, 2);
  const pillW = Math.max(230, Math.min(available, Math.max(...lines.map((line) => line.length)) * 7.2 + 104));
  const pillH = lines.length > 1 ? 42 : 28;
  const pillX = (x1 + x2) / 2 - pillW / 2;
  const pillY = y - pillH - 17;
  const klass = event.returns ? "seqReturn" : "seq";
  const textStart = lines.length > 1 ? pillY + 13 : pillY + pillH / 2 + 1;
  return `<g id="m${number}">
  <rect class="pill" x="${pillX}" y="${pillY}" width="${pillW}" height="${pillH}" rx="7"/>
  <circle cx="${pillX + 19}" cy="${pillY + pillH / 2}" r="12" fill="${color}"/>
  <text class="detail" x="${pillX + 19}" y="${pillY + pillH / 2 + 1}" text-anchor="middle" dominant-baseline="middle" style="fill:#fff;font-size:12px">${number}</text>
${lines.map((line, index) => `  <text class="detail" x="${(x1 + x2) / 2 + 18}" y="${textStart + index * 15}" text-anchor="middle" dominant-baseline="middle">${esc(line)}</text>`).join("\n")}
  <path class="${klass}" d="M${x1} ${y} L${x2} ${y}" stroke="${color}" marker-end="url(#seqArrow-${tone})"/>
</g>`;
}

function validateSequence(diagram) {
  const failures = [];
  if (!/^[A-Z]/.test(diagram.title)) failures.push("title must start uppercase");
  if (!diagram.intent || diagram.intent.length < 90) failures.push("missing reader intent");
  for (const [index, event] of diagram.events.entries()) {
    if (event.branch) continue;
    if (!event.label || /^\d+\.?$/.test(event.label) || /undefined|source to target/i.test(event.label)) failures.push(`invalid message ${index + 1}`);
  }
  if (failures.length > 0) throw new Error(`${diagram.slug}: ${failures.join("; ")}`);
}

function validateSequenceGeometry(diagram, centers, participantW, headerY, headerH, bottom) {
  const failures = [];
  const routeCount = diagram.events.filter((event) => !event.branch).length;
  const summary = geometrySummaryBase(diagram.participants.length, routeCount);
  summary.segments = routeCount;
  const participants = centers.map((cx) => ({
    x: cx - participantW / 2,
    y: headerY,
    width: participantW,
    height: bottom - headerY,
  }));
  for (let leftIndex = 0; leftIndex < participants.length; leftIndex += 1) {
    for (let rightIndex = leftIndex + 1; rightIndex < participants.length; rightIndex += 1) {
      if (boxesOverlap(participants[leftIndex], participants[rightIndex], 0)) {
        summary.nodeOverlaps += 1;
        failures.push(`participant ${leftIndex + 1} overlaps participant ${rightIndex + 1}`);
      }
    }
  }
  const contentBounds = {
    left: 128,
    right: diagram.width - 128,
    top: headerY,
    bottom,
  };
  Object.assign(summary, measureMargins(diagram, {
    ...contentBounds,
    x: contentBounds.left,
    y: contentBounds.top,
    width: contentBounds.right - contentBounds.left,
    height: contentBounds.bottom - contentBounds.top,
  }));
  if (summary.marginImbalance > 8) {
    failures.push(`outer margins are imbalanced L/R/T/B=${summary.margins.left}/${summary.margins.right}/${summary.margins.top}/${summary.margins.bottom}`);
  }
  if (failures.length > 0) throw new Error(`${diagram.slug}: ${failures.join("; ")}`);
  return summary;
}
