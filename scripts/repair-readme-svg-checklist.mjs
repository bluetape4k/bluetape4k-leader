#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const args = new Set(process.argv.slice(2));
const write = args.has("--write");
const targets = [
  "docs/images/readme-diagrams",
  "docs/images/readme-charts",
].flatMap((dir) =>
  fs
    .readdirSync(path.join(root, dir))
    .filter((name) => name.endsWith(".svg"))
    .map((name) => path.join(dir, name)),
);

const number = "[-+]?(?:\\d*\\.\\d+|\\d+)(?:[eE][-+]?\\d+)?";
const tokenRe = new RegExp(`[A-Za-z]|${number}`, "g");

function format(n) {
  return Number.isInteger(n) ? String(n) : n.toFixed(2).replace(/0+$/, "").replace(/\.$/, "");
}

function parseLinePath(d) {
  const tokens = d.match(tokenRe) ?? [];
  const points = [];
  let i = 0;
  let cmd = null;
  while (i < tokens.length) {
    if (/^[A-Za-z]$/.test(tokens[i])) {
      cmd = tokens[i++];
    }
    if (!cmd) return null;
    const upper = cmd.toUpperCase();
    if (upper !== "M" && upper !== "L") return null;
    if (i + 1 >= tokens.length || /^[A-Za-z]$/.test(tokens[i]) || /^[A-Za-z]$/.test(tokens[i + 1])) {
      return null;
    }
    points.push({ cmd: upper, x: Number(tokens[i++]), y: Number(tokens[i++]) });
    if (upper === "M") cmd = cmd === "m" ? "l" : "L";
  }
  return points.length >= 3 ? points : null;
}

function parsePath(d) {
  const tokens = d.match(tokenRe) ?? [];
  const ops = [];
  let i = 0;
  let cmd = null;
  while (i < tokens.length) {
    if (/^[A-Za-z]$/.test(tokens[i])) cmd = tokens[i++];
    if (!cmd) return null;
    const upper = cmd.toUpperCase();
    const count = upper === "M" || upper === "L" ? 2 : upper === "Q" ? 4 : upper === "C" ? 6 : 0;
    if (!count) return null;
    if (i + count - 1 >= tokens.length || tokens.slice(i, i + count).some((token) => /^[A-Za-z]$/.test(token))) {
      return null;
    }
    ops.push({ cmd: upper, values: tokens.slice(i, i + count).map(Number) });
    i += count;
    if (upper === "M") cmd = cmd === "m" ? "l" : "L";
  }
  return ops;
}

function endpoints(d) {
  const match = d.match(tokenRe) ?? [];
  if (match.length < 5 || match[0].toUpperCase() !== "M") return null;
  const numbers = match.filter((token) => !/^[A-Za-z]$/.test(token)).map(Number);
  if (numbers.length < 4) return null;
  return {
    start: { x: numbers[0], y: numbers[1] },
    end: { x: numbers.at(-2), y: numbers.at(-1) },
  };
}

function isSimpleConnectorLine(d) {
  const tokens = d.match(tokenRe) ?? [];
  if (tokens.length !== 6) return false;
  return tokens[0].toUpperCase() === "M" && tokens[3].toUpperCase() === "L";
}

function axisDistance(a, b) {
  if (Math.abs(a.x - b.x) < 0.001) return Math.abs(a.y - b.y);
  if (Math.abs(a.y - b.y) < 0.001) return Math.abs(a.x - b.x);
  return 0;
}

function stepToward(from, to, distance) {
  const total = axisDistance(from, to);
  if (!total) return { x: from.x, y: from.y };
  const ratio = Math.min(1, distance / total);
  return {
    x: from.x + (to.x - from.x) * ratio,
    y: from.y + (to.y - from.y) * ratio,
  };
}

function roundedPath(d) {
  const points = parseLinePath(d);
  if (!points) return d;

  const out = [`M ${format(points[0].x)} ${format(points[0].y)}`];
  const eps = 0.001;
  for (let i = 1; i < points.length - 1; i += 1) {
    const prev = points[i - 1];
    const cur = points[i];
    const next = points[i + 1];
    const before = axisDistance(prev, cur);
    const after = axisDistance(cur, next);
    const prevVertical = Math.abs(prev.x - cur.x) < eps;
    const nextVertical = Math.abs(cur.x - next.x) < eps;
    const isCorner = before > 0 && after > 0 && prevVertical !== nextVertical;
    if (!isCorner) {
      out.push(`L ${format(cur.x)} ${format(cur.y)}`);
      continue;
    }
    const r = Math.min(18, Math.max(6, Math.min(before, after) / 3));
    const a = stepToward(cur, prev, r);
    const b = stepToward(cur, next, r);
    out.push(`L ${format(a.x)} ${format(a.y)}`);
    out.push(`Q ${format(cur.x)} ${format(cur.y)} ${format(b.x)} ${format(b.y)}`);
  }
  const last = points.at(-1);
  out.push(`L ${format(last.x)} ${format(last.y)}`);
  return out.join(" ");
}

function isAxisCollinear(a, b, c) {
  return (
    Math.abs(a.x - b.x) < 0.001 && Math.abs(b.x - c.x) < 0.001
  ) || (
    Math.abs(a.y - b.y) < 0.001 && Math.abs(b.y - c.y) < 0.001
  );
}

function simplifyDegenerateCurves(d) {
  const ops = parsePath(d);
  if (!ops) return d;

  let changed = false;
  let cur = null;
  const out = [];
  for (const op of ops) {
    if (op.cmd === "M") {
      cur = { x: op.values[0], y: op.values[1] };
      out.push(`M ${format(cur.x)} ${format(cur.y)}`);
      continue;
    }
    if (op.cmd === "L") {
      cur = { x: op.values[0], y: op.values[1] };
      out.push(`L ${format(cur.x)} ${format(cur.y)}`);
      continue;
    }
    if (op.cmd === "Q" && cur) {
      const control = { x: op.values[0], y: op.values[1] };
      const end = { x: op.values[2], y: op.values[3] };
      if (isAxisCollinear(cur, control, end)) {
        changed = true;
        out.push(`L ${format(end.x)} ${format(end.y)}`);
      } else {
        out.push(`Q ${format(control.x)} ${format(control.y)} ${format(end.x)} ${format(end.y)}`);
      }
      cur = end;
      continue;
    }
    if (op.cmd === "C" && cur) {
      const c1 = { x: op.values[0], y: op.values[1] };
      const c2 = { x: op.values[2], y: op.values[3] };
      const end = { x: op.values[4], y: op.values[5] };
      if (isAxisCollinear(cur, c1, end) && isAxisCollinear(cur, c2, end)) {
        changed = true;
        out.push(`L ${format(end.x)} ${format(end.y)}`);
      } else {
        out.push(`C ${op.values.map(format).join(" ")}`);
      }
      cur = end;
      continue;
    }
    return d;
  }
  return changed ? out.join(" ") : d;
}

function orthogonalPath(d) {
  const ends = endpoints(d);
  if (!ends) return d;
  const { start, end } = ends;
  if (Math.abs(start.x - end.x) < 0.001 || Math.abs(start.y - end.y) < 0.001) {
    return `M ${format(start.x)} ${format(start.y)} L ${format(end.x)} ${format(end.y)}`;
  }
  const midX = (start.x + end.x) / 2;
  return roundedPath(
    `M ${format(start.x)} ${format(start.y)} L ${format(midX)} ${format(start.y)} L ${format(midX)} ${format(end.y)} L ${format(end.x)} ${format(end.y)}`,
  );
}

function orthogonalizeLineSegments(d) {
  const points = parseLinePath(d);
  if (!points) return d;

  const expanded = [{ cmd: "M", x: points[0].x, y: points[0].y }];
  let cur = { x: points[0].x, y: points[0].y };
  for (const point of points.slice(1)) {
    const next = { x: point.x, y: point.y };
    if (Math.abs(cur.x - next.x) > 0.001 && Math.abs(cur.y - next.y) > 0.001) {
      expanded.push({ cmd: "L", x: next.x, y: cur.y });
    }
    expanded.push({ cmd: "L", x: next.x, y: next.y });
    cur = next;
  }

  return roundedPath(
    expanded
      .map((point) => `${point.cmd} ${format(point.x)} ${format(point.y)}`)
      .join(" "),
  );
}

function distance(a, b) {
  return Math.hypot(a.x - b.x, a.y - b.y);
}

function relaxTightQuadratics(d) {
  return d;
}

function attrValue(tag, name) {
  return tag.match(new RegExp(`\\b${name}="([^"]*)"`))?.[1] ?? "";
}

function setAttr(tag, name, value) {
  if (new RegExp(`\\b${name}=`).test(tag)) {
    return tag.replace(new RegExp(`\\b${name}="[^"]*"`), `${name}="${value}"`);
  }
  return tag.replace(/<marker\b/, `<marker ${name}="${value}"`);
}

function markerColor(open, body) {
  const id = attrValue(open, "id").toLowerCase();
  const explicit = body.match(/\b(?:fill|stroke)="(#[0-9a-fA-F]{3,8}|[a-zA-Z]+)"/)?.[1];
  if (explicit && explicit.toLowerCase() !== "none" && explicit.toLowerCase() !== "transparent") return explicit;
  if (id.includes("red")) return "#dc2626";
  if (id.includes("amber")) return "#cf8428";
  if (id.includes("orange") || id.includes("skip")) return "#ea580c";
  if (id.includes("green")) return "#059669";
  if (id.includes("purple")) return "#7c3aed";
  if (id.includes("blue")) return "#2563eb";
  return "#334155";
}

function normalizeMarker(open, body) {
  const id = attrValue(open, "id");
  const isArrow = /(arrow|return|skip|flow|call|state|slot)/i.test(id);
  const isHollowOrOpen = /(hollow|triangle|impl|uses|dependency)/i.test(id + body);
  if (!isArrow || isHollowOrOpen) return { open, body };

  const color = markerColor(open, body);
  let markerOpen = open;
  for (const [name, value] of [
    ["viewBox", "0 0 10 10"],
    ["markerWidth", "10"],
    ["markerHeight", "10"],
    ["refX", "9"],
    ["refY", "5"],
    ["markerUnits", "userSpaceOnUse"],
  ]) {
    markerOpen = setAttr(markerOpen, name, value);
  }
  const markerBody = `<path d="M 0 0 L 10 5 L 0 10 Z" fill="${color}" stroke="${color}" stroke-width="0" stroke-dasharray="none" style="stroke-dasharray:none!important"/>`;
  return { open: markerOpen, body: markerBody };
}

function repairSvg(svg) {
  let text = svg;
  const isSequence = /\bsequence\b/i.test(svg.slice(0, 1200));
  text = text.replace(/(\.[\w-]*(?:route|edge|connector|flow|call|return|skip|cancel|caller-zone)[\w-]*\s*\{[^}]*?stroke-width\s*:\s*)([0-9.]+)(\s*;[^}]*\})/gi, (all, prefix, width, suffix) => {
    return Number(width) < 2.5 ? `${prefix}3${suffix}` : all;
  });
  text = text.replace(/(\.[\w-]*card[\w-]*\s*\{[^}]*?stroke-width\s*:\s*)([0-9.]+)(\s*;[^}]*\})/g, (all, prefix, width, suffix) => {
    return Number(width) < 1.8 ? `${prefix}1.8${suffix}` : all;
  });
  text = text.replace(/<path\b([^>]*\bmarker-end="[^"]+"[^>]*)\/>/g, (all, attrs) => {
    if (!/\bstroke-width="/.test(attrs)) return all;
    const next = attrs.replace(/\bstroke-width="([0-9.]+)"/, (widthAttr, width) => {
      return Number(width) < 2.5 ? 'stroke-width="2.5"' : widthAttr;
    });
    return `<path${next}/>`;
  });
  text = text.replace(/<line\b([^>]*\bmarker-end="[^"]+"[^>]*)\/>/g, (all, attrs) => {
    if (!/\bstroke-width="/.test(attrs)) return all;
    const next = attrs.replace(/\bstroke-width="([0-9.]+)"/, (widthAttr, width) => {
      return Number(width) < 2.5 ? 'stroke-width="2.5"' : widthAttr;
    });
    return `<line${next}/>`;
  });
  text = text.replace(/\bmarkerUnits="strokeWidth"/g, 'markerUnits="userSpaceOnUse"');
  text = text.replace(/<marker\b(?![^>]*\bmarkerUnits=)([^>]*)>/g, '<marker markerUnits="userSpaceOnUse"$1>');
  text = text.replace(/(<marker\b[^>]*>)([\s\S]*?)(<\/marker>)/g, (all, open, body, close) => {
    const isArrow = /(hollow|open|triangle|arrow|impl|uses|dependency|return|skip)/i.test(open + body);
    if (!isArrow) return all;
    let { open: markerOpen, body: markerBody } = normalizeMarker(open, body);
    if (isSequence && /\barrow/i.test(open)) {
      const fill = markerColor(markerOpen, markerBody);
      markerOpen = markerOpen
        .replace(/\bviewBox="[^"]*"/, 'viewBox="0 0 10 10"')
        .replace(/\bmarkerWidth="[^"]*"/, 'markerWidth="10"')
        .replace(/\bmarkerHeight="[^"]*"/, 'markerHeight="10"')
        .replace(/\brefX="[^"]*"/, 'refX="9"')
        .replace(/\brefY="[^"]*"/, 'refY="5"');
      if (!/\bviewBox=/.test(markerOpen)) markerOpen = markerOpen.replace(/<marker\b/, '<marker viewBox="0 0 10 10"');
      markerBody = `<path d="M 0 0 L 10 5 L 0 10 Z" fill="${fill}" stroke="${fill}" stroke-width="0" stroke-dasharray="none" style="stroke-dasharray:none!important"/>`;
    }
    const repairedBody = markerBody.replace(/<(path|polygon|polyline)\b([^>]*)\/>/g, (shapeTag, shape, attrs) => {
      let next = attrs;
      if (!/stroke-dasharray=/.test(next)) next += ' stroke-dasharray="none"';
      if (!/style=/.test(next)) next += ' style="stroke-dasharray:none!important"';
      else if (!/stroke-dasharray:none/.test(next)) next = next.replace(/\bstyle="([^"]*)"/, 'style="$1;stroke-dasharray:none!important"');
      return `<${shape}${next}/>`;
    });
    return markerOpen + repairedBody + close;
  });
  text = text.replace(/<path\b([^>]*?)\bd="([^"]+)"([^>]*)\/>/g, (all, before, d, after) => {
    const joined = `${before} ${after}`;
    if (!/(marker-end|class="[^"]*(?:arrow|edge|call|return|flow|connector|dependency|route|uses|holds|implements|line|tap|skip|blue|green|orange|purple|pink|amber)[^"]*")/.test(joined)) {
      return all;
    }
    const shouldOrthogonalize = /\bC\b/.test(d) || isSimpleConnectorLine(d);
    const next = shouldOrthogonalize ? orthogonalPath(d) : orthogonalizeLineSegments(d);
    const relaxed = simplifyDegenerateCurves(next);
    if (relaxed === d) return all;
    return `<path${before}d="${relaxed}"${after}/>`;
  });
  text = text.replace(/<path\b([^>]*)\bd="([^"]+)"([^>]*)\/>/g, (all, before, d, after) => {
    const joined = `${before} ${after}`;
    if (!/(marker-end|class="[^"]*(?:arrow|edge|call|return|flow|connector|dependency|route|uses|holds|implements|line|blue|green|orange|purple|pink|amber|dash)[^"]*")/.test(joined)) {
      return all;
    }
    if (!isSimpleConnectorLine(d)) return all;
    const ends = endpoints(d);
    if (!ends) return all;
    if (Math.abs(ends.start.x - ends.end.x) < 0.001 || Math.abs(ends.start.y - ends.end.y) < 0.001) return all;
    const next = orthogonalPath(d);
    return `<path${before}d="${next}"${after}/>`;
  });
  return text;
}

let changed = 0;
for (const file of targets) {
  const original = fs.readFileSync(path.join(root, file), "utf8");
  const next = repairSvg(original);
  if (next !== original) {
    changed += 1;
    console.log(file);
    if (write) fs.writeFileSync(path.join(root, file), next);
  }
}

console.log(`${write ? "updated" : "would_update"}=${changed}`);
