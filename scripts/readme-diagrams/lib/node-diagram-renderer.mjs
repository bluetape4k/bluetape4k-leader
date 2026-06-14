import { diagramDir, dotSourceFor, esc, palette, routeTones, svgShell, writeGraphvizEvidence, writeSvgPng } from "./svg-core.mjs";
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

export function card(id, title, details, x, y, width, height, color) {
  return { id, title, details, x, y, width, height, color };
}

export function band(title, x, y, width, height) {
  return { title, x, y, width, height };
}

export function edge(from, to, tone, route = null, label = "") {
  return { from, to, tone, route, label };
}

export function renderNodeDiagram(diagram) {
  const layout = normalizeNodeDiagramMargins(diagram);
  const geometry = validateNodeDiagram(layout);
  const bandSvg = (layout.bands || []).map((item) => `<g>
  <rect class="band" x="${item.x}" y="${item.y}" width="${item.width}" height="${item.height}" rx="18"/>
  <text class="bandTitle" x="${item.x + 26}" y="${item.y + 34}">${esc(item.title)}</text>
</g>`).join("\n");
  const byId = new Map(layout.nodes.map((node) => [node.id, node]));
  const routes = layout.edges.map((item) => renderRoute(item, byId)).join("\n");
  const nodes = layout.nodes.map(renderCard).join("\n");
  const body = `${bandSvg}\n${routes}\n${nodes}`;
  const svg = svgShell({ ...layout, body });
  const base = `${diagramDir}/${layout.slug}`;
  console.log(formatGeometrySummary(layout.slug, geometry));
  writeGraphvizEvidence(base, dotSourceFor(layout));
  writeSvgPng(base, svg);
  console.log(`${layout.slug}: kind=${layout.kind} nodes=${layout.nodes.length} routes=${layout.edges.length} evidence=dot/plain/graphviz/final`);
}

function normalizeNodeDiagramMargins(diagram) {
  const targetMargin = diagram.marginTarget ?? 96;
  const byId = new Map(diagram.nodes.map((node) => [node.id, node]));
  const routePointBoxes = diagram.edges.flatMap((route) => {
    const source = byId.get(route.from);
    const target = byId.get(route.to);
    const points = route.route || autoRoute(source, target);
    return points.map((point) => ({ x: point.x, y: point.y, width: 0, height: 0 }));
  });
  const contentBounds = boundsOf([...diagram.nodes, ...(diagram.bands || []), ...routePointBoxes]);
  const width = Math.round(contentBounds.right - contentBounds.left + 64 + targetMargin * 2);
  const height = Math.round(contentBounds.bottom - contentBounds.top + 160 + targetMargin * 2);
  const dx = Math.round(32 + targetMargin - contentBounds.left);
  const dy = Math.round(132 + targetMargin - contentBounds.top);
  const movePoint = (point) => ({ x: point.x + dx, y: point.y + dy });
  return {
    ...diagram,
    width,
    height,
    nodes: diagram.nodes.map((item) => ({ ...item, x: item.x + dx, y: item.y + dy })),
    bands: (diagram.bands || []).map((item) => ({ ...item, x: item.x + dx, y: item.y + dy })),
    edges: diagram.edges.map((item) => ({
      ...item,
      route: item.route?.map(movePoint),
    })),
  };
}

function renderCard(item) {
  const color = palette[item.color] || palette.gray;
  const lines = [item.title, ...(item.details || [])];
  const lineHeight = 19;
  const first = item.height / 2 - ((lines.length - 1) * lineHeight) / 2 + 1;
  return `<g id="node-${item.id}">
  <rect class="card" x="${item.x}" y="${item.y}" width="${item.width}" height="${item.height}" rx="10" fill="${color.fill}" stroke="${color.stroke}"/>
${lines.map((line, index) => {
    const klass = index === 0 ? "cardTitle" : "detail";
    return `  <text class="${klass}" x="${item.x + item.width / 2}" y="${first + item.y + index * lineHeight}" text-anchor="middle" dominant-baseline="middle">${esc(line)}</text>`;
  }).join("\n")}
</g>`;
}

function renderRoute(item, byId) {
  const source = byId.get(item.from);
  const target = byId.get(item.to);
  if (!source || !target) throw new Error(`unknown route ${item.from}->${item.to}`);
  const points = item.route || autoRoute(source, target);
  const color = routeTones[item.tone] || routeTones.neutral;
  const d = points.map((point, index) => `${index === 0 ? "M" : "L"}${point.x} ${point.y}`).join(" ");
  return `<path class="route" data-from="${esc(item.from)}" data-to="${esc(item.to)}" data-route-tone="${esc(item.tone)}" d="${d}" stroke="${color}" marker-end="url(#arrow-${item.tone || "neutral"})"/>`;
}

function autoRoute(source, target) {
  const sc = center(source);
  const tc = center(target);
  if (Math.abs(sc.y - tc.y) <= 18 && source.x + source.width <= target.x) {
    return [{ x: source.x + source.width, y: sc.y }, { x: target.x, y: sc.y }];
  }
  if (Math.abs(sc.x - tc.x) <= 18 && source.y + source.height <= target.y) {
    return [{ x: sc.x, y: source.y + source.height }, { x: sc.x, y: target.y }];
  }
  if (Math.abs(sc.x - tc.x) > Math.abs(sc.y - tc.y)) {
    const startX = sc.x < tc.x ? source.x + source.width : source.x;
    const endX = sc.x < tc.x ? target.x : target.x + target.width;
    const midX = Math.round((startX + endX) / 2);
    return [{ x: startX, y: sc.y }, { x: midX, y: sc.y }, { x: midX, y: tc.y }, { x: endX, y: tc.y }];
  }
  const startY = sc.y < tc.y ? source.y + source.height : source.y;
  const endY = sc.y < tc.y ? target.y : target.y + target.height;
  const midY = Math.round((startY + endY) / 2);
  return [{ x: sc.x, y: startY }, { x: sc.x, y: midY }, { x: tc.x, y: midY }, { x: tc.x, y: endY }];
}

function center(node) {
  return { x: node.x + node.width / 2, y: node.y + node.height / 2 };
}

function validateNodeDiagram(diagram) {
  const failures = [];
  const summary = geometrySummaryBase(diagram.nodes.length, diagram.edges.length);
  if (!/^[A-Z]/.test(diagram.title)) failures.push("title must start with uppercase human-readable phrase");
  if (!diagram.intent || diagram.intent.length < 90) failures.push("missing source-derived reader intent");
  if (!diagram.sourceRead || !/README|settings\.gradle|src\/main|\.kt/i.test(diagram.sourceRead)) failures.push("missing inspected source marker");
  const byId = new Map(diagram.nodes.map((node) => [node.id, node]));
  const routePointBoxes = diagram.edges.flatMap((route) => {
    const source = byId.get(route.from);
    const target = byId.get(route.to);
    const points = route.route || autoRoute(source, target);
    return points.map((point) => ({ x: point.x, y: point.y, width: 0, height: 0 }));
  });
  const contentBounds = boundsOf([...diagram.nodes, ...(diagram.bands || []), ...routePointBoxes]);
  Object.assign(summary, measureMargins(diagram, contentBounds));
  if (summary.marginImbalance > 8) {
    failures.push(`outer margins are imbalanced L/R/T/B=${summary.margins.left}/${summary.margins.right}/${summary.margins.top}/${summary.margins.bottom}`);
  }
  for (let leftIndex = 0; leftIndex < diagram.nodes.length; leftIndex += 1) {
    for (let rightIndex = leftIndex + 1; rightIndex < diagram.nodes.length; rightIndex += 1) {
      if (boxesOverlap(diagram.nodes[leftIndex], diagram.nodes[rightIndex], 0)) {
        summary.nodeOverlaps += 1;
        failures.push(`${diagram.nodes[leftIndex].id} overlaps ${diagram.nodes[rightIndex].id}`);
      }
    }
  }
  for (const band of diagram.bands || []) {
    const titleGutter = 50;
    const paddingX = 16;
    const paddingBottom = 30;
    const body = {
      x: band.x + paddingX,
      y: band.y + titleGutter,
      width: band.width - paddingX * 2,
      height: band.height - titleGutter - paddingBottom,
    };
    for (const node of diagram.nodes) {
      const nodeCenter = center(node);
      const isInBand = nodeCenter.x >= band.x && nodeCenter.x <= band.x + band.width && nodeCenter.y >= band.y && nodeCenter.y <= band.y + band.height;
      if (!isInBand) continue;
      const contained = node.x >= body.x && node.y >= body.y && node.x + node.width <= body.x + body.width && node.y + node.height <= body.y + body.height;
      if (!contained) {
        failures.push(`${node.id} is not contained in layer "${band.title}" body with title gutter`);
      }
    }
    const titleGuard = { x: band.x, y: band.y, width: Math.min(420, band.width), height: titleGutter };
    for (const route of diagram.edges) {
      const source = byId.get(route.from);
      const target = byId.get(route.to);
      const points = route.route || autoRoute(source, target);
      for (let index = 1; index < points.length; index += 1) {
        if (segmentCrossesBox(points[index - 1], points[index], titleGuard, 0)) {
          failures.push(`${route.from}->${route.to} crosses layer title gutter "${band.title}"`);
        }
      }
    }
  }
  for (const route of diagram.edges) {
    const source = byId.get(route.from);
    const target = byId.get(route.to);
    const points = route.route || autoRoute(source, target);
    assertBoundary(diagram.slug, route, points[0], source, "start");
    assertBoundary(diagram.slug, route, points.at(-1), target, "end");
    if (!hasCleanEndpointAngle(points[0], points[1], source)) {
      summary.badEndpointAngle += 1;
      failures.push(`${route.from}->${route.to} has bad source endpoint angle`);
    }
    if (!hasCleanEndpointAngle(points.at(-1), points.at(-2), target)) {
      summary.badEndpointAngle += 1;
      failures.push(`${route.from}->${route.to} has bad target endpoint angle`);
    }
    for (let index = 1; index < points.length; index += 1) {
      const a = points[index - 1];
      const b = points[index];
      summary.segments += 1;
      if (a.x !== b.x && a.y !== b.y) {
        summary.badBends += 1;
        failures.push(`${route.from}->${route.to} has non-orthogonal segment`);
      }
      for (const node of diagram.nodes) {
        if (node.id === route.from || node.id === route.to) continue;
        const clearance = segmentBoxClearance(a, b, node);
        if (Number.isFinite(clearance)) summary.minLaneClearance = Math.min(summary.minLaneClearance, clearance);
        if (segmentCrossesBox(a, b, node, 0)) {
          summary.interiorCrossings += 1;
          failures.push(`${route.from}->${route.to} crosses ${node.id}`);
        } else if (segmentCrossesBox(a, b, node, 8)) {
          summary.laneClearance += 1;
          failures.push(`${route.from}->${route.to} is too close to ${node.id}`);
        }
      }
    }
  }
  if (failures.length > 0) throw new Error(`${diagram.slug}: ${failures.join("; ")}`);
  return summary;
}
