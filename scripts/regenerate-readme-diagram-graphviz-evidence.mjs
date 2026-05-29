#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { basename, join, relative } from "node:path";

const root = process.cwd();
const diagramDir = join(root, "docs/images/readme-diagrams");
const artifactDir = join(root, ".omx/artifacts");
const scriptName = "scripts/regenerate-readme-diagram-graphviz-evidence.mjs";
const fontDirs = [
  `${process.env.HOME}/Library/Fonts`,
  `${process.env.HOME}/.local/share/fonts`,
  `${process.env.HOME}/.fonts`,
  "/Library/Fonts",
  "/System/Library/Fonts",
  "/opt/homebrew/share/fonts",
];

const args = new Set(process.argv.slice(2));
const checkOnly = args.has("--check");
const targetArg = process.argv.slice(2).find((arg) => !arg.startsWith("--"));

const colors = [
  "#5B8DEF",
  "#58A978",
  "#D6A441",
  "#DC6B82",
  "#45A7A1",
  "#8A72D6",
  "#B88A44",
  "#8BA84D",
];

function sh(cmd, args, options = {}) {
  return execFileSync(cmd, args, { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"], ...options });
}

function findFont(patterns) {
  for (const dir of fontDirs) {
    if (!existsSync(dir)) continue;
    const expression = ["find", dir, "-maxdepth", "3", "-type", "f", "("];
    patterns.forEach((pattern, index) => {
      if (index > 0) expression.push("-o");
      expression.push("-iname", pattern);
    });
    expression.push(")", "-print", "-quit");
    const found = sh(expression[0], expression.slice(1)).trim();
    if (found) return found;
  }
  return "";
}

function buildFontConfig(architectsFont, comicFont) {
  const fontsDir = join(artifactDir, "fontconfig");
  mkdirSync(fontsDir, { recursive: true });
  const fontsConf = join(fontsDir, "fonts.conf");
  const dirs = [...new Set([architectsFont, comicFont].filter(Boolean).map((path) => path.replace(/\/[^/]+$/, "")))];
  const content = `<?xml version="1.0"?>
<!DOCTYPE fontconfig SYSTEM "fonts.dtd">
<fontconfig>
${dirs.map((dir) => `  <dir>${xmlEscape(dir)}</dir>`).join("\n")}
  <alias><family>Architects Daughter</family><prefer><family>Architects Daughter</family></prefer></alias>
  <alias><family>Comic Mono</family><prefer><family>Comic Mono</family></prefer></alias>
</fontconfig>
`;
  writeFileSync(fontsConf, content);
  return fontsConf;
}

function resolveFonts() {
  const architectsFont = findFont(["*Architects*Daughter*.ttf", "*Architects*Daughter*.otf", "ArchitectsDaughter-Regular.ttf"]);
  const comicFont = findFont(["ComicMono.ttf", "ComicMono-Regular.ttf", "ComicMono*.otf", "ComicMono*.ttf"]);
  const failures = [];
  if (!architectsFont) failures.push("Architects Daughter font file not found after user/system font search");
  if (!comicFont) failures.push("Comic Mono font file not found after user/system font search");
  const fontConfig = failures.length ? "" : buildFontConfig(architectsFont, comicFont);
  return {
    architectsFont,
    comicFont,
    fontConfig,
    failures,
    env: fontConfig
      ? {
          ...process.env,
          FONTCONFIG_FILE: fontConfig,
          FONTCONFIG_PATH: fontConfig.replace(/\/[^/]+$/, ""),
        }
      : process.env,
  };
}

function listSvgFiles() {
  if (targetArg) {
    const path = targetArg.startsWith("/") ? targetArg : join(root, targetArg);
    return [path];
  }
  return sh("find", [diagramDir, "-maxdepth", "1", "-name", "*.svg", "-print"])
    .trim()
    .split("\n")
    .filter(Boolean)
    .filter((path) => !path.endsWith("-graphviz.svg"))
    .filter((path) => existsSync(path.replace(/\.svg$/, ".png")))
    .sort();
}

function attr(tag, name) {
  const match = tag.match(new RegExp(`${name}="([^"]*)"`));
  return match?.[1] ?? "";
}

function numericAttr(tag, name, fallback = 0) {
  const value = Number.parseFloat(attr(tag, name));
  return Number.isFinite(value) ? value : fallback;
}

function decodeText(value) {
  return value
    .replace(/<[^>]+>/g, "")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&amp;/g, "&")
    .replace(/\s+/g, " ")
    .trim();
}

function xmlEscape(value) {
  return value
    .replace(/&/g, "&amp;")
    .replace(/"/g, "&quot;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}

function dotEscape(value) {
  return value.replace(/\\/g, "\\\\").replace(/"/g, '\\"').replace(/\n/g, "\\n");
}

function center(node) {
  return { x: node.x + node.width / 2, y: node.y + node.height / 2 };
}

function graphvizPos(point, canvasHeight) {
  return { x: point.x, y: canvasHeight - point.y };
}

function distance(a, b) {
  return Math.hypot(a.x - b.x, a.y - b.y);
}

function extractTextElements(fragment, tx = 0, ty = 0) {
  const texts = [];
  const textRegex = /<text\b([^>]*)>([\s\S]*?)<\/text>/g;
  let match;
  while ((match = textRegex.exec(fragment))) {
    texts.push({
      className: attr(match[1], "class"),
      x: numericAttr(match[1], "x") + tx,
      y: numericAttr(match[1], "y") + ty,
      text: decodeText(match[2]),
    });
  }
  return texts.filter((text) => text.text);
}

function labelFromTexts(texts, fallback) {
  const preferred = texts.find((text) =>
    /entityTitle|smallLabel|label|title/.test(text.className) && !/^«/.test(text.text),
  );
  const selected = preferred ?? texts.find((text) => !/^«/.test(text.text)) ?? texts[0];
  return selected?.text ?? fallback;
}

function parseNodes(svg, slug) {
  const nodes = [];
  const consumed = [];
  const groupRegex = /<g\b([^>]*)>([\s\S]*?)<\/g>/g;
  let groupMatch;
  while ((groupMatch = groupRegex.exec(svg))) {
    const transform = attr(groupMatch[1], "transform");
    const translate = transform.match(/translate\(([-\d.]+),\s*([-\d.]+)\)/);
    const tx = translate ? Number.parseFloat(translate[1]) : 0;
    const ty = translate ? Number.parseFloat(translate[2]) : 0;
    const body = groupMatch[2];
    const rectMatch = body.match(/<rect\b([^>]*class="[^"]*(?:card|entity|group)[^"]*"[^>]*)\/?>/);
    if (!rectMatch) continue;
    const rectTag = rectMatch[1];
    const x = tx + numericAttr(rectTag, "x");
    const y = ty + numericAttr(rectTag, "y");
    const width = numericAttr(rectTag, "width");
    const height = numericAttr(rectTag, "height");
    if (width <= 0 || height <= 0) continue;
    const texts = extractTextElements(body, tx, ty);
    const id = `n${nodes.length}`;
    nodes.push({
      id,
      label: labelFromTexts(texts, `${slug} node ${nodes.length + 1}`),
      kind: attr(rectTag, "class"),
      compartmented: /class="[^"]*compartment/.test(body),
      x,
      y,
      width,
      height,
      color: attr(rectTag, "stroke") || colors[nodes.length % colors.length],
      textCount: texts.length,
      texts,
    });
    consumed.push([groupMatch.index, groupMatch.index + groupMatch[0].length]);
  }

  const globalTexts = extractTextElements(svg);
  const rectRegex = /<rect\b([^>]*class="[^"]*(?:card|entity|group)[^"]*"[^>]*)\/?>/g;
  let rectMatch;
  while ((rectMatch = rectRegex.exec(svg))) {
    if (consumed.some(([start, end]) => rectMatch.index >= start && rectMatch.index < end)) continue;
    const rectTag = rectMatch[1];
    const x = numericAttr(rectTag, "x");
    const y = numericAttr(rectTag, "y");
    const width = numericAttr(rectTag, "width");
    const height = numericAttr(rectTag, "height");
    if (width <= 0 || height <= 0) continue;
    const texts = globalTexts.filter((text) => text.x >= x && text.x <= x + width && text.y >= y && text.y <= y + height);
    const id = `n${nodes.length}`;
    nodes.push({
      id,
      label: labelFromTexts(texts, `${slug} node ${nodes.length + 1}`),
      kind: attr(rectTag, "class"),
      compartmented: false,
      x,
      y,
      width,
      height,
      color: attr(rectTag, "stroke") || colors[nodes.length % colors.length],
      textCount: texts.length,
      texts,
    });
  }

  return nodes;
}

function textHeight(text) {
  if (/title/.test(text.className)) return 43;
  if (/smallLabel/.test(text.className)) return 21;
  if (/(entityTitle|gtitle|label)/.test(text.className)) return 24;
  if (/strong/.test(text.className)) return 13;
  if (/(mono|small)/.test(text.className)) return 12;
  return 14;
}

function validateTextAlignment(nodes) {
  const failures = [];
  for (const node of nodes) {
    if (!/\bcard\b/.test(node.kind) || node.compartmented || node.texts.length === 0) continue;
    const labelTexts = node.texts.filter((text) => /(smallLabel|label|entityTitle|gtitle)/.test(text.className));
    const measuredTexts = labelTexts.length ? labelTexts : node.texts;
    const top = Math.min(...measuredTexts.map((text) => text.y - textHeight(text) * 0.72));
    const bottom = Math.max(...measuredTexts.map((text) => text.y + textHeight(text) * 0.28));
    const blockCenter = (top + bottom) / 2;
    const cardCenter = node.y + node.height / 2;
    const drift = Math.abs(blockCenter - cardCenter);
    const tolerance = Math.max(12, node.height * 0.16);
    if (drift > tolerance) {
      failures.push(`${node.id} "${node.label}" text block center drift ${drift.toFixed(1)}px`);
    }
  }
  return failures;
}

function validateNodeOverlaps(nodes) {
  const failures = [];
  for (let leftIndex = 0; leftIndex < nodes.length; leftIndex += 1) {
    for (let rightIndex = leftIndex + 1; rightIndex < nodes.length; rightIndex += 1) {
      const left = nodes[leftIndex];
      const right = nodes[rightIndex];
      const overlapX = Math.min(left.x + left.width, right.x + right.width) - Math.max(left.x, right.x);
      const overlapY = Math.min(left.y + left.height, right.y + right.height) - Math.max(left.y, right.y);
      if (overlapX > 1 && overlapY > 1) {
        failures.push(
          `${left.id} "${left.label}" overlaps ${right.id} "${right.label}" by ${overlapX.toFixed(1)}x${overlapY.toFixed(1)}px`,
        );
      }
    }
  }
  return failures;
}

function validateSequenceSpacing(svg, slug) {
  if (!/sequence/.test(slug)) return [];
  const failures = [];
  const svgTag = svg.match(/<svg\b([^>]*)>/)?.[1] ?? "";
  const canvasWidth = numericAttr(svgTag, "width");
  const participantHeaders = [...svg.matchAll(/<rect\b([^>]*class="[^"]*\bcard\b[^"]*"[^>]*)\/?>/g)]
    .map((match) => ({
      x: numericAttr(match[1], "x"),
      y: numericAttr(match[1], "y"),
      width: numericAttr(match[1], "width"),
      height: numericAttr(match[1], "height"),
    }))
    .filter((rect) => rect.y >= 150 && rect.y <= 180 && rect.height >= 60);
  if (participantHeaders.length >= 2 && canvasWidth > 0) {
    const leftMargin = Math.min(...participantHeaders.map((rect) => rect.x));
    const rightMargin = canvasWidth - Math.max(...participantHeaders.map((rect) => rect.x + rect.width));
    if (leftMargin < 60 || rightMargin < 60) {
      failures.push(`sequence participant outer margins too small: left=${leftMargin.toFixed(1)}px right=${rightMargin.toFixed(1)}px`);
    }
  }

  const labelArrowRegex =
    /<rect x="[^"]+" y="([\d.]+)" width="[^"]+" height="40"[^>]*\/?>\n<text class="strong"[^>]*>[^<]+<\/text>\n<path class="(?:line|dashed)" d="M[^ ]+ ([\d.]+)/g;
  for (const match of svg.matchAll(labelArrowRegex)) {
    const gap = Number.parseFloat(match[2]) - (Number.parseFloat(match[1]) + 40);
    if (gap < 0 || gap > 10) {
      failures.push(`sequence label-to-arrow gap ${gap.toFixed(1)}px`);
    }
  }
  return failures;
}

function pathPoints(d) {
  const numbers = [...d.matchAll(/[-+]?\d*\.?\d+(?:e[-+]?\d+)?/gi)].map((match) => Number.parseFloat(match[0]));
  const points = [];
  for (let index = 0; index + 1 < numbers.length; index += 2) {
    points.push({ x: numbers[index], y: numbers[index + 1] });
  }
  return points;
}

function nearestNode(point, nodes) {
  return nodes
    .map((node) => ({ node, distance: distance(point, center(node)) }))
    .sort((a, b) => a.distance - b.distance)[0]?.node;
}

function parseEdges(svg, nodes) {
  const edges = [];
  const pathRegex = /<path\b([^>]*)\/?>/g;
  let pathMatch;
  while ((pathMatch = pathRegex.exec(svg))) {
    const tag = pathMatch[1];
    const className = attr(tag, "class");
    if (!/(^|\s)(line|dashed|inheritLine|implLine|rel)(\s|$)/.test(className)) continue;
    const d = attr(tag, "d");
    const points = pathPoints(d);
    if (points.length < 2) continue;
    const from = nearestNode(points[0], nodes);
    const to = nearestNode(points[points.length - 1], nodes);
    if (!from || !to) continue;
    edges.push({
      from: from.id,
      to: to.id,
      className,
      pointCount: points.length,
      points,
      self: from.id === to.id,
    });
  }
  return edges;
}

function buildDot(slug, title, nodes, edges) {
  const width = Math.max(...nodes.map((node) => node.x + node.width), 1000);
  const height = Math.max(...nodes.map((node) => node.y + node.height), 700);
  const rankdir = /sequence/.test(slug) ? "TB" : "LR";
  const lines = [
    `digraph "${dotEscape(slug)}" {`,
    `  graph [layout=neato, rankdir=${rankdir}, splines=ortho, overlap=false, outputorder=edgesfirst, nodesep=0.55, ranksep=0.75, bgcolor="transparent", label="${dotEscape(title)}", labelloc=t];`,
    '  node [shape=box, style="rounded,filled", fontname="Architects Daughter", fontsize=18, margin="0.16,0.10"];',
    '  edge [color="#758297", penwidth=1.8, arrowsize=0.8, fontname="Comic Mono", fontsize=10];',
  ];
  for (const node of nodes) {
    const pos = graphvizPos(center(node), height);
    const widthIn = Math.max(node.width / 72, 1.2).toFixed(2);
    const heightIn = Math.max(node.height / 72, 0.55).toFixed(2);
    lines.push(
      `  ${node.id} [label="${dotEscape(node.label)}", fillcolor="#ffffff", color="${node.color}", width=${widthIn}, height=${heightIn}, pos="${pos.x.toFixed(2)},${pos.y.toFixed(2)}!"];`,
    );
  }
  for (const [index, edge] of edges.entries()) {
    const styleAttr = /dashed|implLine/.test(edge.className) ? "style=dashed" : "";
    const arrowAttr = /inheritLine|implLine/.test(edge.className) ? "arrowhead=empty" : "";
    const waypoints = edge.points.slice(1, -1).map((point, pointIndex) => {
      const waypointId = `r${index + 1}p${pointIndex + 1}`;
      const pos = graphvizPos(point, height);
      lines.push(
        `  ${waypointId} [label="", shape=point, width=0.03, height=0.03, fixedsize=true, style=invis, pos="${pos.x.toFixed(2)},${pos.y.toFixed(2)}!"];`,
      );
      return waypointId;
    });
    const chain = [edge.from, ...waypoints, edge.to];
    for (let chainIndex = 0; chainIndex < chain.length - 1; chainIndex++) {
      const isLast = chainIndex === chain.length - 2;
      const attrs = [styleAttr, isLast ? arrowAttr : "arrowhead=none"].filter(Boolean);
      lines.push(`  ${chain[chainIndex]} -> ${chain[chainIndex + 1]} [${attrs.join(", ")}];`);
    }
  }
  lines.push("}");
  return `${lines.join("\n")}\n`;
}

function insertMetadata(svg, slug) {
  const metadata =
    `  <metadata id="graphviz-evidence">Regenerated with Graphviz evidence: ${slug}.dot, ${slug}.plain, ${slug}-graphviz.svg via ${scriptName}.</metadata>\n`;
  if (svg.includes('id="graphviz-evidence"')) {
    return svg.replace(/\s*<metadata id="graphviz-evidence">[\s\S]*?<\/metadata>\n?/, `\n${metadata}`);
  }
  return svg.replace(/(<svg\b[^>]*>\n?)/, `$1${metadata}`);
}

function normalizeFontFamilies(svg) {
  return svg
    .replace(
      /font-family:"Architects Daughter","Comic Sans MS","Comic Sans",cursive/g,
      'font-family:"Architects Daughter","Comic Mono","Comic Sans MS","Comic Sans",cursive',
    )
    .replace(
      /font-family:"Comic Sans MS","Comic Sans","Comic Neue",Arial,sans-serif/g,
      'font-family:"Comic Mono","Comic Sans MS","Comic Sans","Comic Neue",Arial,sans-serif',
    );
}

function writeIfChanged(path, content) {
  const previous = existsSync(path) ? readFileSync(path, "utf8") : null;
  if (previous !== content) {
    if (!checkOnly) writeFileSync(path, content);
    return true;
  }
  return false;
}

function regeneratePng(svgPath, fontEnv) {
  const pngPath = svgPath.replace(/\.svg$/, ".png");
  if (checkOnly) return;
  execFileSync("rsvg-convert", ["-f", "png", "-o", pngPath, svgPath], { stdio: "pipe", env: fontEnv });
}

function processSvg(svgPath, fontState) {
  const slug = basename(svgPath, ".svg");
  const svg = readFileSync(svgPath, "utf8");
  const title =
    decodeText(svg.match(/<text\b[^>]*class="title"[^>]*>([\s\S]*?)<\/text>/)?.[1] ?? "") ||
    decodeText(svg.match(/<title\b[^>]*>([\s\S]*?)<\/title>/)?.[1] ?? "") ||
    slug;
  const nodes = parseNodes(svg, slug);
  const edges = parseEdges(svg, nodes);
  const failures = [];
  if (nodes.length === 0) failures.push("no final card/entity nodes detected");
  const hasFinalConnectorCandidates = /<path\b[^>]*class="[^"]*(?:line|dashed|inheritLine|implLine|rel)[^"]*"/.test(svg);
  if (hasFinalConnectorCandidates && edges.length === 0 && nodes.length > 1) failures.push("no final connector routes detected");
  failures.push(...validateNodeOverlaps(nodes));
  failures.push(...validateTextAlignment(nodes));
  failures.push(...validateSequenceSpacing(svg, slug));

  const dot = buildDot(slug, title, nodes, edges);
  const dotPath = join(diagramDir, `${slug}.dot`);
  const plainPath = join(diagramDir, `${slug}.plain`);
  const sketchPath = join(diagramDir, `${slug}-graphviz.svg`);

  writeIfChanged(dotPath, dot);
  if (!checkOnly) {
    writeFileSync(plainPath, sh("neato", ["-n2", "-Tplain", dotPath]));
    writeFileSync(sketchPath, sh("neato", ["-n2", "-Tsvg", dotPath]));
    writeFileSync(sketchPath.replace(/\.svg$/, ".png"), sh("neato", ["-n2", "-Tpng", dotPath], { encoding: "buffer" }));
  }

  const plain = checkOnly && existsSync(plainPath) ? readFileSync(plainPath, "utf8") : existsSync(plainPath) ? readFileSync(plainPath, "utf8") : "";
  const plainNodeCount = (plain.match(/^node /gm) ?? []).length;
  const plainEdgeCount = (plain.match(/^edge /gm) ?? []).length;
  if (!checkOnly && plainNodeCount < nodes.length) failures.push(`Graphviz plain node count too small: final=${nodes.length} plain=${plainNodeCount}`);
  if (!checkOnly && plainEdgeCount < edges.length) failures.push(`Graphviz plain edge count too small: final=${edges.length} plain=${plainEdgeCount}`);

  const updatedSvg = normalizeFontFamilies(insertMetadata(svg, slug));
  writeIfChanged(svgPath, updatedSvg);
  failures.push(...fontState.failures);
  regeneratePng(svgPath, fontState.env);

  const summary = {
    diagram: relative(root, svgPath),
    nodes: nodes.length,
    routes: edges.length,
    plainNodes: plainNodeCount,
    plainRoutes: plainEdgeCount,
    failures,
  };
  return summary;
}

mkdirSync(artifactDir, { recursive: true });
const fontState = resolveFonts();
const summaries = listSvgFiles().map((svgPath) => processSvg(svgPath, fontState));
const failed = summaries.filter((summary) => summary.failures.length > 0);
const report = [
  "# Leader README Diagram Graphviz Evidence",
  "",
  `- mode: ${checkOnly ? "check" : "write"}`,
  `- diagrams: ${summaries.length}`,
  `- failures: ${failed.length}`,
  `- Architects Daughter: ${fontState.architectsFont || "not found"}`,
  `- Comic Mono: ${fontState.comicFont || "not found"}`,
  `- FONTCONFIG_FILE: ${fontState.fontConfig || "not configured"}`,
  "",
  "| Diagram | Nodes | Routes | Plain nodes | Plain routes | Status |",
  "|---|---:|---:|---:|---:|---|",
  ...summaries.map((summary) =>
    `| ${summary.diagram} | ${summary.nodes} | ${summary.routes} | ${summary.plainNodes} | ${summary.plainRoutes} | ${
      summary.failures.length ? summary.failures.join("; ") : "ok"
    } |`,
  ),
  "",
].join("\n");
if (!checkOnly) writeFileSync(join(artifactDir, "leader-readme-diagram-graphviz-evidence.md"), report);
console.log(`diagrams=${summaries.length} failures=${failed.length}`);
for (const summary of summaries) {
  console.log(`${summary.failures.length ? "FAIL" : "OK"} ${summary.diagram} nodes=${summary.nodes} routes=${summary.routes}`);
}
if (failed.length) {
  process.exitCode = 1;
}
