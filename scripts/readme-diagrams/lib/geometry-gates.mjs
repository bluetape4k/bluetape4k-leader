export function boundsOf(items) {
  return {
    left: Math.min(...items.map((item) => item.x)),
    right: Math.max(...items.map((item) => item.x + item.width)),
    top: Math.min(...items.map((item) => item.y)),
    bottom: Math.max(...items.map((item) => item.y + item.height)),
  };
}

export function boxesOverlap(a, b, pad = 0) {
  return a.x < b.x + b.width + pad
    && a.x + a.width + pad > b.x
    && a.y < b.y + b.height + pad
    && a.y + a.height + pad > b.y;
}

export function segmentCrossesBox(a, b, node, pad = 0) {
  if (a.x === b.x) {
    return a.x > node.x - pad && a.x < node.x + node.width + pad && Math.max(a.y, b.y) > node.y - pad && Math.min(a.y, b.y) < node.y + node.height + pad;
  }
  if (a.y === b.y) {
    return a.y > node.y - pad && a.y < node.y + node.height + pad && Math.max(a.x, b.x) > node.x - pad && Math.min(a.x, b.x) < node.x + node.width + pad;
  }
  return false;
}

export function segmentBoxClearance(a, b, node) {
  if (a.x === b.x) {
    const minY = Math.min(a.y, b.y);
    const maxY = Math.max(a.y, b.y);
    if (maxY < node.y || minY > node.y + node.height) return Infinity;
    if (a.x >= node.x && a.x <= node.x + node.width) return 0;
    return Math.min(Math.abs(a.x - node.x), Math.abs(a.x - node.x - node.width));
  }
  if (a.y === b.y) {
    const minX = Math.min(a.x, b.x);
    const maxX = Math.max(a.x, b.x);
    if (maxX < node.x || minX > node.x + node.width) return Infinity;
    if (a.y >= node.y && a.y <= node.y + node.height) return 0;
    return Math.min(Math.abs(a.y - node.y), Math.abs(a.y - node.y - node.height));
  }
  return Infinity;
}

export function assertBoundary(slug, route, point, node, where, noun = "card") {
  const eps = 0.5;
  const onX = point.x >= node.x - eps && point.x <= node.x + node.width + eps;
  const onY = point.y >= node.y - eps && point.y <= node.y + node.height + eps;
  const boundary = ((Math.abs(point.x - node.x) <= eps || Math.abs(point.x - node.x - node.width) <= eps) && onY)
    || ((Math.abs(point.y - node.y) <= eps || Math.abs(point.y - node.y - node.height) <= eps) && onX);
  if (!boundary) throw new Error(`${slug}: ${route.from}->${route.to} ${where} endpoint is not on ${noun} boundary`);
}

export function endpointSide(point, node) {
  const eps = 0.5;
  if (Math.abs(point.x - node.x) <= eps) return "left";
  if (Math.abs(point.x - node.x - node.width) <= eps) return "right";
  if (Math.abs(point.y - node.y) <= eps) return "top";
  if (Math.abs(point.y - node.y - node.height) <= eps) return "bottom";
  return "";
}

export function hasCleanEndpointAngle(point, neighbor, node) {
  const side = endpointSide(point, node);
  if (!side || !neighbor) return false;
  if (side === "left") return neighbor.y === point.y && neighbor.x < point.x;
  if (side === "right") return neighbor.y === point.y && neighbor.x > point.x;
  if (side === "top") return neighbor.x === point.x && neighbor.y < point.y;
  if (side === "bottom") return neighbor.x === point.x && neighbor.y > point.y;
  return false;
}

export function geometrySummaryBase(nodes, routes) {
  return {
    nodes,
    routes,
    segments: 0,
    badEndpointAngle: 0,
    badBends: 0,
    interiorCrossings: 0,
    nodeOverlaps: 0,
    laneClearance: 0,
    minLaneClearance: Infinity,
    marginImbalance: 0,
    margins: { left: 0, right: 0, top: 0, bottom: 0 },
    titleGap: 0,
  };
}

export function measureMargins(diagram, bounds) {
  const margins = {
    left: Math.round(bounds.left - 32),
    right: Math.round(diagram.width - 32 - bounds.right),
    top: Math.round(bounds.top - 132),
    bottom: Math.round(diagram.height - 28 - bounds.bottom),
  };
  return {
    margins,
    titleGap: margins.top,
    marginImbalance: Math.max(...Object.values(margins)) - Math.min(...Object.values(margins)),
  };
}

export function formatGeometrySummary(slug, summary) {
  const margins = `${summary.margins.left}/${summary.margins.right}/${summary.margins.top}/${summary.margins.bottom}`;
  const minLaneClearance = Number.isFinite(summary.minLaneClearance) ? Math.round(summary.minLaneClearance) : 999;
  return `${slug}: geometry=PASS nodes=${summary.nodes} routes=${summary.routes} segments=${summary.segments} badEndpointAngle=${summary.badEndpointAngle} badBends=${summary.badBends} interiorCrossings=${summary.interiorCrossings} nodeOverlaps=${summary.nodeOverlaps} laneClearance=${summary.laneClearance} minLaneClearance=${minLaneClearance} marginImbalance=${summary.marginImbalance} margins=L/R/T/B:${margins} titleGap=${summary.titleGap}`;
}
