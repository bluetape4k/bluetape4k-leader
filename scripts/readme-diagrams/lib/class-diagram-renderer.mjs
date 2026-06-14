import { diagramDir, esc, escDot, palette, routeTones, svgShell, writeGraphvizEvidence, writeSvgPng } from "./svg-core.mjs";
import {
  assertBoundary,
  boundsOf,
  boxesOverlap,
  formatGeometrySummary,
  geometrySummaryBase,
  hasCleanEndpointAngle,
  measureMargins,
  segmentBoxClearance,
  segmentCrossesBox,
} from "./geometry-gates.mjs";

export function klass(id, title, stereotype, members, x, y, width, height, color = "blue") {
  return { id, title, stereotype, members, x, y, width, height, color };
}

export function relation(from, to, kind, route, label = "") {
  return { from, to, kind, route, label };
}

export function renderClassDiagram(diagram) {
  const layout = normalizeClassDiagramMargins(diagram);
  const geometry = validateClassDiagram(layout);
  const byId = new Map(layout.classes.map((item) => [item.id, item]));
  const routes = layout.relations.map((item) => renderRelation(item, byId)).join("\n");
  const classes = layout.classes.map(renderClass).join("\n");
  const legend = renderLegend(layout.legend || [], layout.height, layout.legendBox);
  const body = `${routes}\n${classes}\n${legend}`;
  const base = `${diagramDir}/${layout.slug}`;
  console.log(formatGeometrySummary(layout.slug, geometry));
  writeGraphvizEvidence(base, dotSourceForClass(layout));
  writeSvgPng(base, svgShell({ ...layout, body }));
  console.log(`${layout.slug}: kind=class classes=${layout.classes.length} relations=${layout.relations.length} evidence=dot/plain/graphviz/final`);
}

function normalizeClassDiagramMargins(diagram) {
  const targetMargin = diagram.marginTarget ?? 96;
  const contentBounds = boundsOf(diagram.classes);
  const legend = legendDimensions(diagram.legend || []);
  const width = Math.round(Math.max(contentBounds.right - contentBounds.left, legend.width) + 64 + targetMargin * 2);
  const dx = Math.round(32 + targetMargin - contentBounds.left);
  const dy = Math.round(132 + targetMargin - contentBounds.top);
  const shiftedBottom = contentBounds.bottom + dy;
  const legendBox = legend.width > 0
    ? {
      x: width - 32 - targetMargin - legend.width,
      y: shiftedBottom + 58,
      width: legend.width,
      height: legend.height,
    }
    : null;
  const height = Math.round((legendBox?.y ?? shiftedBottom) + (legendBox?.height ?? 0) + 28 + targetMargin);
  const movePoint = (point) => ({ x: point.x + dx, y: point.y + dy });
  return {
    ...diagram,
    width,
    height,
    legendBox,
    classes: diagram.classes.map((item) => ({ ...item, x: item.x + dx, y: item.y + dy })),
    relations: diagram.relations.map((item) => ({
      ...item,
      route: item.route?.map(movePoint),
    })),
  };
}

function renderClass(item) {
  const tone = palette[item.color] || palette.gray;
  const headerHeight = 66;
  const memberTop = item.y + headerHeight + 18;
  return `<g id="class-${item.id}">
  <rect x="${item.x}" y="${item.y}" width="${item.width}" height="${item.height}" rx="8" fill="${tone.fill}" stroke="${tone.stroke}" stroke-width="2.2" filter="url(#shadow)"/>
  <line x1="${item.x}" y1="${item.y + headerHeight}" x2="${item.x + item.width}" y2="${item.y + headerHeight}" stroke="${tone.stroke}" stroke-width="1.8"/>
  <text class="small" x="${item.x + item.width / 2}" y="${item.y + 24}" text-anchor="middle" dominant-baseline="middle">${esc(item.stereotype)}</text>
  <text class="cardTitle" x="${item.x + item.width / 2}" y="${item.y + 50}" text-anchor="middle" dominant-baseline="middle">${esc(item.title)}</text>
${item.members.map((member, index) => `  <text class="detail" x="${item.x + 18}" y="${memberTop + index * 21}" dominant-baseline="middle">${esc(member)}</text>`).join("\n")}
</g>`;
}

function renderRelation(item, byId) {
  const source = byId.get(item.from);
  const target = byId.get(item.to);
  if (!source || !target) throw new Error(`unknown class relation ${item.from}->${item.to}`);
  const points = item.route || autoRoute(source, target);
  const tone = relationTone(item.kind);
  const d = points.map((point, index) => `${index === 0 ? "M" : "L"}${point.x} ${point.y}`).join(" ");
  const marker = item.kind === "inherit" ? `marker-end="url(#uml-${item.kind}-${item.from}-${item.to})"` : `marker-end="url(#arrow-${tone.marker})"`;
  const dash = item.kind === "dependency" || item.kind === "uses" ? ` stroke-dasharray="8 7"` : "";
  const label = item.label ? renderLabel(item, points, tone.color) : "";
  const localMarker = item.kind === "inherit"
    ? `<defs><marker id="uml-${item.kind}-${item.from}-${item.to}" markerWidth="5" markerHeight="5" refX="4.5" refY="2.5" orient="auto" markerUnits="strokeWidth"><path d="M 0.5 0.5 L 4.5 2.5 L 0.5 4.5 Z" fill="#FFFFFF" stroke="${tone.color}" stroke-width="0.7"/></marker></defs>`
    : "";
  return `${localMarker}
<path class="route" data-from="${esc(item.from)}" data-to="${esc(item.to)}" data-route-kind="${esc(item.kind)}" d="${d}" stroke="${tone.color}"${dash} ${marker}/>
${label}`;
}

function renderLabel(item, points, color) {
  const mid = segmentMidpoint(points);
  const text = esc(item.label);
  const width = Math.max(96, text.length * 7.4 + 28);
  return `<g>
  <rect class="pill" x="${mid.x - width / 2}" y="${mid.y - 14}" width="${width}" height="28" rx="14"/>
  <text class="small" x="${mid.x}" y="${mid.y + 1}" text-anchor="middle" dominant-baseline="middle" fill="${color}">${text}</text>
</g>`;
}

function segmentMidpoint(points) {
  let best = { a: points[0], b: points[1], length: 0 };
  for (let index = 1; index < points.length; index += 1) {
    const a = points[index - 1];
    const b = points[index];
    const length = Math.abs(a.x - b.x) + Math.abs(a.y - b.y);
    if (length > best.length) best = { a, b, length };
  }
  return {
    x: Math.round((best.a.x + best.b.x) / 2),
    y: Math.round((best.a.y + best.b.y) / 2),
  };
}

function renderLegend(items, diagramHeight, legendBox) {
  if (!items.length) return "";
  const box = legendBox || { x: 1110, y: diagramHeight - (88 + items.length * 30) - 62, width: 506, height: 88 + items.length * 30 };
  const x = box.x;
  const boxHeight = box.height;
  const y = box.y;
  const rows = items.map((item, index) => {
    const yy = y + 64 + index * 30;
    const tone = relationTone(item.kind);
    return `<g>
  <line x1="${x + 26}" y1="${yy}" x2="${x + 96}" y2="${yy}" stroke="${tone.color}" stroke-width="2.6" stroke-linecap="round"${item.kind === "dependency" || item.kind === "uses" ? ` stroke-dasharray="8 7"` : ""}/>
  <text class="small" x="${x + 112}" y="${yy + 1}" dominant-baseline="middle">${esc(item.label)}</text>
</g>`;
  }).join("\n");
  return `<g>
  <rect class="band" x="${x}" y="${y}" width="${box.width}" height="${boxHeight}" rx="16"/>
  <text class="bandTitle" x="${x + 24}" y="${y + 30}">Relationship Legend</text>
${rows}
</g>`;
}

function legendDimensions(items) {
  if (!items.length) return { width: 0, height: 0 };
  return { width: 506, height: 88 + items.length * 30 };
}

function relationTone(kind) {
  switch (kind) {
    case "inherit":
      return { color: routeTones.call, marker: "call" };
    case "result":
      return { color: routeTones.success, marker: "success" };
    case "slot":
      return { color: routeTones.release, marker: "release" };
    case "event":
      return { color: routeTones.dependency, marker: "dependency" };
    case "local":
      return { color: routeTones.contention, marker: "contention" };
    case "dependency":
    case "uses":
      return { color: routeTones.neutral, marker: "neutral" };
    default:
      return { color: routeTones.neutral, marker: "neutral" };
  }
}

function autoRoute(source, target) {
  const sx = source.x + source.width / 2;
  const sy = source.y;
  const tx = target.x + target.width / 2;
  const ty = target.y + target.height;
  const mid = Math.round((sy + ty) / 2);
  return [{ x: sx, y: sy }, { x: sx, y: mid }, { x: tx, y: mid }, { x: tx, y: ty }];
}

function dotSourceForClass(diagram) {
  const nodes = diagram.classes.map((node) => {
    const label = [node.stereotype, node.title, ...node.members].join("\\n");
    return `  "${node.id}" [label="${escDot(label)}"];`;
  }).join("\n");
  const edges = diagram.relations.map((edge) => `  "${edge.from}" -> "${edge.to}" [label="${escDot(edge.label || edge.kind)}", color="${relationTone(edge.kind).color}"];`).join("\n");
  return `digraph "${diagram.slug}" {
  graph [rankdir=BT, bgcolor="transparent", splines=ortho, nodesep=0.75, ranksep=0.9];
  node [shape=box, style="rounded,filled", fillcolor="#F8FAFC", color="#94A3B8", fontname="Comic Mono"];
  edge [fontname="Comic Mono", fontsize=10, arrowsize=0.8];
${nodes}
${edges}
}
`;
}

function validateClassDiagram(diagram) {
  const failures = [];
  const summary = geometrySummaryBase(diagram.classes.length, diagram.relations.length);
  if (!/^[A-Z]/.test(diagram.title)) failures.push("title must start with uppercase human-readable phrase");
  if (!diagram.intent || diagram.intent.length < 90) failures.push("missing source-derived reader intent");
  if (!diagram.sourceRead || !/README|src\/main|\.kt/i.test(diagram.sourceRead)) failures.push("missing inspected source marker");
  if (diagram.width / diagram.height > 2.25) failures.push("class diagram is too panoramic for README scale");
  const contentBounds = boundsOf(diagram.legendBox ? [...diagram.classes, diagram.legendBox] : diagram.classes);
  Object.assign(summary, measureMargins(diagram, contentBounds));
  if (summary.marginImbalance > 8) {
    failures.push(`outer margins are imbalanced L/R/T/B=${summary.margins.left}/${summary.margins.right}/${summary.margins.top}/${summary.margins.bottom}`);
  }
  for (const item of diagram.classes) {
    if (!item.stereotype || !item.title || !item.members?.length) failures.push(`${item.id} lacks UML compartment content`);
    if (item.members.some((member) => member.length > 54)) failures.push(`${item.id} has member row too long`);
  }
  for (let leftIndex = 0; leftIndex < diagram.classes.length; leftIndex += 1) {
    for (let rightIndex = leftIndex + 1; rightIndex < diagram.classes.length; rightIndex += 1) {
      if (boxesOverlap(diagram.classes[leftIndex], diagram.classes[rightIndex], 0)) {
        summary.nodeOverlaps += 1;
        failures.push(`${diagram.classes[leftIndex].id} overlaps ${diagram.classes[rightIndex].id}`);
      }
    }
  }
  if (diagram.legendBox) {
    for (const item of diagram.classes) {
      if (boxesOverlap(item, diagram.legendBox, 12)) {
        summary.nodeOverlaps += 1;
        failures.push(`legend overlaps ${item.id}`);
      }
    }
  }
  for (const relationItem of diagram.relations) {
    const source = diagram.classes.find((item) => item.id === relationItem.from);
    const target = diagram.classes.find((item) => item.id === relationItem.to);
    const points = relationItem.route || autoRoute(source, target);
    assertBoundary(diagram.slug, relationItem, points[0], source, "start", "class");
    assertBoundary(diagram.slug, relationItem, points.at(-1), target, "end", "class");
    if (!hasCleanEndpointAngle(points[0], points[1], source)) {
      summary.badEndpointAngle += 1;
      failures.push(`${relationItem.from}->${relationItem.to} has bad source endpoint angle`);
    }
    if (!hasCleanEndpointAngle(points.at(-1), points.at(-2), target)) {
      summary.badEndpointAngle += 1;
      failures.push(`${relationItem.from}->${relationItem.to} has bad target endpoint angle`);
    }
    if (relationItem.kind === "inherit" && target.y >= source.y) failures.push(`${relationItem.from}->${relationItem.to} parent is not above child`);
    for (let index = 1; index < points.length; index += 1) {
      const a = points[index - 1];
      const b = points[index];
      summary.segments += 1;
      if (a.x !== b.x && a.y !== b.y) {
        summary.badBends += 1;
        failures.push(`${relationItem.from}->${relationItem.to} has non-orthogonal segment`);
      }
      for (const klassItem of diagram.classes) {
        if (klassItem.id === relationItem.from || klassItem.id === relationItem.to) continue;
        const clearance = segmentBoxClearance(a, b, klassItem);
        if (Number.isFinite(clearance)) summary.minLaneClearance = Math.min(summary.minLaneClearance, clearance);
        if (segmentCrossesBox(a, b, klassItem, 0)) {
          summary.interiorCrossings += 1;
          failures.push(`${relationItem.from}->${relationItem.to} crosses ${klassItem.id}`);
        } else if (segmentCrossesBox(a, b, klassItem, 8)) {
          summary.laneClearance += 1;
          failures.push(`${relationItem.from}->${relationItem.to} is too close to ${klassItem.id}`);
        }
      }
    }
  }
  if (failures.length > 0) throw new Error(`${diagram.slug}: ${failures.join("; ")}`);
  return summary;
}
