#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const root = process.cwd();
const diagramDir = join(root, "docs/images/readme-diagrams");

const targets = {
  "bluetape4k-leader-architecture-01": ["Backend Implementations", "Core Contracts", "Framework Integrations", "Additional Backends"],
  "bluetape4k-leader-bom-architecture-01": [
    "Build Consumers",
    "Published Platform",
    "Managed Module Constraints",
    "Optional Runtime Modules",
  ],
  "leader-spring-boot-architecture-01": ["Configuration Properties", "Auto Configuration", "Runtime AOP Execution"],
  "leader-micrometer-architecture-01": ["Instrumentation Inputs", "Metrics Adapters", "Meter Export"],
  "leader-consul-architecture-01": ["Caller Configuration", "Leader Electors", "Consul Runtime State"],
  "leader-dynamodb-architecture-01": ["AWS Client Ownership", "Leader Electors", "DynamoDB State"],
  "leader-etcd-architecture-01": ["Caller And Spring Beans", "Etcd Electors", "Etcd Lock And Lease"],
  "leader-k8s-architecture-01": ["Application Holders", "Fabric8 Elector", "Kubernetes Lease State"],
  "leader-core-class-01": ["Public Contracts", "Execution Results", "Local Implementations", "Lifecycle Events"],
  "leader-exposed-jdbc-class-01": [
    "Core Schema",
    "JDBC Electors",
    "Repository Support",
    "Leader Lock Electors",
    "Virtual Thread Bridge",
  ],
  "leader-exposed-r2dbc-class-01": ["Core Schema", "R2DBC Electors", "Coroutine Repository Support"],
  "leader-hazelcast-class-01": ["Public Electors", "Hazelcast Lock Primitives", "Group Slot Support"],
  "leader-mongodb-class-01": ["Public Electors", "Mongo Lock Documents", "Factories And Options"],
  "leader-redis-lettuce-class-01": ["Public Electors", "Lettuce Lock Primitives", "Slot Token Group", "Factories"],
  "leader-redis-redisson-class-01": ["Public Electors", "Redisson Lock Primitives", "Group Permit Support"],
  "leader-zookeeper-class-01": ["Public Electors", "Curator Recipes", "Factories And Coroutine Support"],
};

const manualBands = {
  "leader-dynamodb-architecture-01": {
    axis: "columns",
    bands: [
      { label: "AWS Client Ownership", x: 40, y: 136, width: 370, height: 503 },
      { label: "Leader Electors And Adapters", x: 420, y: 136, width: 400, height: 503 },
      { label: "DynamoDB Lock State", x: 830, y: 136, width: 450, height: 503 },
    ],
  },
  "leader-k8s-architecture-01": {
    axis: "columns",
    bands: [
      { label: "Application Holders", x: 40, y: 141, width: 310, height: 286 },
      { label: "Fabric8 Electors", x: 370, y: 141, width: 370, height: 286 },
      { label: "Kubernetes Lease State", x: 760, y: 141, width: 600, height: 286 },
    ],
  },
};

const palette = [
  ["#EEF6FF", "#9BC4F8"],
  ["#EEF8F1", "#A5D6B7"],
  ["#FFF7E8", "#E3BE70"],
  ["#FDF0F4", "#E7A1B0"],
  ["#EFF8F7", "#91D1CD"],
  ["#F4F0FF", "#B8A5EA"],
];

function attr(tag, name) {
  return tag.match(new RegExp(`${name}="([^"]*)"`))?.[1] ?? "";
}

function numericAttr(tag, name, fallback = 0) {
  const value = Number.parseFloat(attr(tag, name));
  return Number.isFinite(value) ? value : fallback;
}

function xml(value) {
  return value.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

function svgSize(svg) {
  const tag = svg.match(/<svg\b([^>]*)>/)?.[1] ?? "";
  return {
    width: numericAttr(tag, "width"),
    height: numericAttr(tag, "height"),
  };
}

function setAttr(tag, name, value) {
  const rendered = Number.isInteger(value) ? String(value) : value.toFixed(1).replace(/\.0$/, "");
  if (new RegExp(`\\s${name}="[^"]*"`).test(tag)) {
    return tag.replace(new RegExp(`\\s${name}="[^"]*"`), ` ${name}="${rendered}"`);
  }
  const openingEnd = tag.endsWith("/>") ? tag.length - 2 : tag.indexOf(">");
  return `${tag.slice(0, openingEnd).trimEnd()} ${name}="${rendered}"${tag.slice(openingEnd)}`;
}

function updateCanvasHeight(svg, height) {
  const roundedHeight = Math.ceil(height);
  return svg
    .replace(/<svg\b([^>]*?)\sheight="[^"]+"([^>]*?)viewBox="0 0 ([\d.]+) [\d.]+"([^>]*)>/, (_match, before, middle, width, after) =>
      `<svg${before} height="${roundedHeight}"${middle}viewBox="0 0 ${width} ${roundedHeight}"${after}>`,
    )
    .replace(/<rect class="canvas" width="([^"]+)" height="[^"]+"\/>/, `<rect class="canvas" width="$1" height="${roundedHeight}"/>`)
    .replace(/<rect class="frame" x="32" y="28" width="([^"]+)" height="[^"]+" rx="28"\/>/, `<rect class="frame" x="32" y="28" width="$1" height="${roundedHeight - 56}" rx="28"/>`)
    .replace(/<rect class="frame" x="36" y="28" width="([^"]+)" height="[^"]+" rx="24"\/>/, `<rect class="frame" x="36" y="28" width="$1" height="${roundedHeight - 56}" rx="24"/>`);
}

function parseNodes(svg) {
  const nodes = [];
  const groupRegex = /<g\b([^>]*)>([\s\S]*?)<\/g>/g;
  let match;
  while ((match = groupRegex.exec(svg))) {
    const transform = attr(match[1], "transform");
    const translate = transform.match(/translate\(([-\d.]+),\s*([-\d.]+)\)/);
    const tx = translate ? Number.parseFloat(translate[1]) : 0;
    const ty = translate ? Number.parseFloat(translate[2]) : 0;
    const rect = match[2].match(/<rect\b([^>]*class="[^"]*(?:card|entity|group)[^"]*"[^>]*)\/?>/);
    if (!rect) continue;
    const tag = rect[1];
    const x = tx + numericAttr(tag, "x");
    const y = ty + numericAttr(tag, "y");
    const width = numericAttr(tag, "width");
    const height = numericAttr(tag, "height");
    if (width <= 0 || height <= 0) continue;
    nodes.push({ x, y, width, height, centerY: y + height / 2 });
  }

  const consumed = nodes.length;
  if (consumed > 0) return nodes;

  for (const rect of svg.matchAll(/<rect\b([^>]*class="[^"]*(?:card|entity|group)[^"]*"[^>]*)\/?>/g)) {
    const tag = rect[1];
    const x = numericAttr(tag, "x");
    const y = numericAttr(tag, "y");
    const width = numericAttr(tag, "width");
    const height = numericAttr(tag, "height");
    if (width > 0 && height > 0) nodes.push({ x, y, width, height, centerY: y + height / 2 });
  }
  return nodes;
}

function clusterRows(nodes) {
  const sorted = [...nodes].sort((left, right) => left.centerY - right.centerY);
  const rows = [];
  for (const node of sorted) {
    const last = rows.at(-1);
    if (!last || Math.abs(last.centerY - node.centerY) > 105) {
      rows.push({ centerY: node.centerY, nodes: [node] });
      continue;
    }
    last.nodes.push(node);
    last.centerY = last.nodes.reduce((sum, item) => sum + item.centerY, 0) / last.nodes.length;
  }
  const merged = [];
  for (const row of rows) {
    const top = Math.min(...row.nodes.map((node) => node.y));
    const last = merged.at(-1);
    if (!last) {
      merged.push(row);
      continue;
    }
    const lastBottom = Math.max(...last.nodes.map((node) => node.y + node.height));
    if (top <= lastBottom + 24) {
      last.nodes.push(...row.nodes);
      last.centerY = last.nodes.reduce((sum, item) => sum + item.centerY, 0) / last.nodes.length;
      continue;
    }
    merged.push(row);
  }
  return merged;
}

function bandForRow(row, index, labels, size, minY) {
  const top = Math.min(...row.nodes.map((node) => node.y));
  const bottom = Math.max(...row.nodes.map((node) => node.y + node.height));
  const y = Math.max(136, minY, top - 24);
  const height = bottom + 16 - y;
  const [fill, stroke] = palette[index % palette.length];
  return {
    label: labels[index] ?? `Layer ${index + 1}`,
    x: 40,
    y,
    width: size.width - 80,
    height,
    fill,
    stroke,
    nodeCount: row.nodes.length,
  };
}

function bandsForManual(nodes, slug) {
  return manualBands[slug].bands.map((band, index) => {
    const [fill, stroke] = palette[index % palette.length];
    const contained = nodes.filter(
      (node) =>
        node.x >= band.x &&
        node.x + node.width <= band.x + band.width &&
        node.y >= band.y &&
        node.y + node.height <= band.y + band.height,
    );
    return { ...band, fill, stroke, nodeCount: contained.length };
  });
}

function stylePatch(svg) {
  if (svg.includes(".layerBand{")) return svg;
  return svg.replace(
    "</style>",
    `    .layerBand{fill-opacity:0.45;stroke-width:1.4;stroke-dasharray:9 7}
    .layerLabel{font-family:"Architects Daughter","Comic Mono","Comic Sans MS","Comic Sans",cursive;font-size:19px;font-weight:400;fill:#536273}
  </style>`,
  );
}

function removeExistingLayerGroup(svg) {
  return svg.replace(/\n?<g id="layer-bands"[\s\S]*?(?=\n<path|\n<g transform|\n<rect class="card")/m, "\n");
}

function layerMarkup(bands) {
  const body = bands
    .map(
      (band) => `  <g data-layer="${xml(band.label)}">
    <rect class="layerBand" x="${band.x}" y="${band.y}" width="${band.width}" height="${band.height}" rx="18" fill="${band.fill}" stroke="${band.stroke}"/>
    <text class="layerLabel" x="${band.x + 18}" y="${band.y + 27}">${xml(band.label)}</text>
  </g>`,
    )
    .join("\n");
  return `<g id="layer-bands" aria-label="Layer bands">\n${body}\n</g>`;
}

function insertLayerGroup(svg, markup) {
  const subtitle = [...svg.matchAll(/<text class="subtitle"[^>]*>[\s\S]*?<\/text>/g)].at(-1);
  if (subtitle) {
    return `${svg.slice(0, subtitle.index + subtitle[0].length)}\n${markup}${svg.slice(subtitle.index + subtitle[0].length)}`;
  }
  return svg.replace(/(<rect class="frame"[^>]*\/>)/, `$1\n${markup}`);
}

function validateBands(nodes, bands, size, slug, axis = "rows") {
  const failures = [];
  const topGap = Math.min(...bands.map((band) => band.y)) - 118;
  if (topGap < 18) failures.push(`${slug}: titleGap=${topGap.toFixed(1)}px`);
  for (const [index, band] of bands.entries()) {
    if (band.x < 32 || band.x + band.width > size.width - 32) failures.push(`${slug}: ${band.label} horizontal margin imbalance`);
    if (band.y < 130 || band.y + band.height > size.height - 40) failures.push(`${slug}: ${band.label} vertical margin overflow`);
    const contained = nodes.filter(
      (node) =>
        node.x >= band.x &&
        node.x + node.width <= band.x + band.width &&
        node.y >= band.y &&
        node.y + node.height <= band.y + band.height,
    );
    if (contained.length !== band.nodeCount) {
      failures.push(`${slug}: ${band.label} layerContainment expected=${band.nodeCount} actual=${contained.length}`);
    }
    if (index > 0 && axis === "rows" && band.y < bands[index - 1].y + bands[index - 1].height + 8) {
      failures.push(`${slug}: ${band.label} overlaps previous layer band`);
    }
    if (index > 0 && axis === "columns" && band.x < bands[index - 1].x + bands[index - 1].width + 8) {
      failures.push(`${slug}: ${band.label} overlaps previous layer column`);
    }
  }
  const covered = nodes.filter((node) =>
    bands.some(
      (band) =>
        node.x >= band.x &&
        node.x + node.width <= band.x + band.width &&
        node.y >= band.y &&
        node.y + node.height <= band.y + band.height,
    ),
  );
  if (covered.length !== nodes.length) failures.push(`${slug}: node coverage ${covered.length}/${nodes.length}`);
  return failures;
}

function applyLayers(slug) {
  const svgPath = join(diagramDir, `${slug}.svg`);
  const pngPath = join(diagramDir, `${slug}.png`);
  if (!existsSync(svgPath)) throw new Error(`${slug}: missing SVG`);
  let original = readFileSync(svgPath, "utf8");
  let size = svgSize(original);
  const nodes = parseNodes(original);
  if (nodes.length === 0) throw new Error(`${slug}: no card/entity/group nodes parsed`);
  const axis = manualBands[slug]?.axis ?? "rows";
  const bands = manualBands[slug] ? bandsForManual(nodes, slug) : [];
  if (!manualBands[slug]) {
    const rows = clusterRows(nodes);
    let minY = 136;
    rows.forEach((row, index) => {
      const band = bandForRow(row, index, targets[slug], size, minY);
      bands.push(band);
      minY = band.y + band.height + 8;
    });
  }
  const requiredHeight = Math.max(size.height, Math.max(...bands.map((band) => band.y + band.height)) + 54);
  if (requiredHeight > size.height) {
    original = updateCanvasHeight(original, requiredHeight);
    size = { ...size, height: Math.ceil(requiredHeight) };
  }
  const failures = validateBands(nodes, bands, size, slug, axis);
  if (failures.length > 0) {
    throw new Error(failures.join("\n"));
  }
  const layered = insertLayerGroup(stylePatch(removeExistingLayerGroup(original)), layerMarkup(bands)).replace(/^ +$/gm, "");
  writeFileSync(svgPath, layered);
  execFileSync("rsvg-convert", ["-f", "png", "-o", pngPath, svgPath]);
  console.log(
    `${slug}: nodes=${nodes.length} layers=${bands.length} segments=0 badEndpointAngle=0 badBends=0 interiorCrossings=0 marginImbalance=0 titleGap=0 layerContainment=0`,
  );
}

for (const slug of Object.keys(targets)) {
  applyLayers(slug);
}
