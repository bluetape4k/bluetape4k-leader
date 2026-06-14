import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";

export const diagramDir = "docs/images/readme-diagrams";
export const chartDir = "docs/images/readme-charts";
export const dot = process.env.GRAPHVIZ_DOT || "/opt/homebrew/bin/dot";
export const rsvgConvert = process.env.RSVG_CONVERT || "/opt/homebrew/bin/rsvg-convert";

export const palette = {
  blue: { fill: "#E8F3FF", stroke: "#5B8DEF", line: "#4F83BF" },
  green: { fill: "#EAF7EF", stroke: "#58A978", line: "#3E9868" },
  teal: { fill: "#E9F7F6", stroke: "#45A7A1", line: "#2E8F89" },
  amber: { fill: "#FFF3D9", stroke: "#D6A441", line: "#B9851B" },
  pink: { fill: "#FDECEF", stroke: "#DC6B82", line: "#C94D68" },
  purple: { fill: "#F1ECFF", stroke: "#8A72D6", line: "#755BC6" },
  olive: { fill: "#EEF6D9", stroke: "#8BA84D", line: "#718A35" },
  gray: { fill: "#F2F5F9", stroke: "#9AA8B8", line: "#758297" },
  indigo: { fill: "#EEF1FF", stroke: "#6477D8", line: "#4F63C7" },
};

export const routeTones = {
  call: palette.blue.line,
  success: palette.green.line,
  skip: palette.pink.line,
  contention: palette.amber.line,
  release: palette.purple.line,
  retry: palette.indigo.line,
  dependency: palette.teal.line,
  metric: palette.olive.line,
  neutral: palette.gray.line,
};

export function assertTools() {
  if (!existsSync(dot)) throw new Error(`Graphviz dot not found at ${dot}`);
  if (!existsSync(rsvgConvert)) throw new Error(`rsvg-convert not found at ${rsvgConvert}`);
}

export function esc(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

export function cleanSvg(svg) {
  const forbidden = ["Inter", "Arial", "Helvetica", "Comic Sans", "Comic Neue", "sans-serif", "cursive"];
  for (const token of forbidden) {
    if (svg.includes(token)) throw new Error(`forbidden final SVG font token: ${token}`);
  }
  if (/Graphviz evidence|Source truth|geometry=PASS|validation evidence/i.test(svg)) {
    throw new Error("internal validation/evidence text leaked into final SVG");
  }
  return `${svg.replace(/[ \t]+$/gm, "").trimEnd()}\n`;
}

export function writeSvgPng(base, svg) {
  assertTools();
  mkdirSync(dirname(base), { recursive: true });
  const svgPath = `${base}.svg`;
  const pngPath = `${base}.png`;
  writeFileSync(svgPath, cleanSvg(svg));
  execFileSync(rsvgConvert, ["--format=png", "--output", pngPath, svgPath], { stdio: "inherit" });
  return { svgPath, pngPath };
}

export function writeGraphvizEvidence(base, dotSource) {
  assertTools();
  writeFileSync(`${base}.dot`, dotSource);
  execFileSync(dot, ["-Tplain", `${base}.dot`, "-o", `${base}.plain`], { stdio: "inherit" });
  execFileSync(dot, ["-Tsvg", `${base}.dot`, "-o", `${base}-graphviz.svg`], { stdio: "inherit" });
  execFileSync(dot, ["-Tpng", `${base}.dot`, "-o", `${base}-graphviz.png`], { stdio: "inherit" });
}

export function defs({ sequence = false } = {}) {
  const routeMarkers = Object.entries(routeTones)
    .map(([name, color]) => `<marker id="${sequence ? "seqArrow" : "arrow"}-${name}" markerWidth="5" markerHeight="5" refX="4.5" refY="2.5" orient="auto" markerUnits="strokeWidth"><path d="M 0.5 0.5 L 4.5 2.5 L 0.5 4.5 Z" fill="${color}"/></marker>`)
    .join("\n    ");
  return `<defs>
    <filter id="shadow" x="-8%" y="-8%" width="116%" height="116%"><feDropShadow dx="0" dy="6" stdDeviation="7" flood-color="#203040" flood-opacity="0.10"/></filter>
    ${routeMarkers}
    <style>
      .canvas{fill:#F7FAFC}
      .frame{fill:#FFFFFF;stroke:#D7E2EC;stroke-width:2}
      .title{font-family:"Architects Daughter";font-size:44px;fill:#22344A;font-weight:400}
      .subtitle{font-family:"Comic Mono";font-size:16px;fill:#536476;font-weight:400}
      .band{fill:#F3F7FB;stroke:#D7E2EC;stroke-width:2}
      .bandTitle{font-family:"Architects Daughter";font-size:24px;fill:#22344A;font-weight:400;paint-order:stroke;stroke:#F3F7FB;stroke-width:5px;stroke-linejoin:round}
      .card{filter:url(#shadow);stroke-width:2}
      .cardTitle{font-family:"Architects Daughter";font-size:23px;fill:#22344A;font-weight:400}
      .detail{font-family:"Comic Mono";font-size:13px;fill:#42556B;font-weight:400}
      .small{font-family:"Comic Mono";font-size:12px;fill:#627184;font-weight:400}
      .route{fill:none;stroke-width:2.45;stroke-linejoin:round;stroke-linecap:round}
      .seq{fill:none;stroke-width:3;stroke-linecap:round}
      .seqReturn{fill:none;stroke-width:2.7;stroke-linecap:round;stroke-dasharray:8 7}
      .lifeline{stroke:#A7B4C3;stroke-width:1.8;stroke-dasharray:8 8}
      .pill{fill:#FFFFFF;stroke:#D6E3EF;stroke-width:1.4}
      .branchBox{fill:#FFFFFF;fill-opacity:.42;stroke:#D6A441;stroke-width:1.8;stroke-dasharray:8 8}
    </style>
  </defs>`;
}

export function svgShell({ width, height, title, subtitle, desc, intent, evidence, sourceRead, sequence = false, body }) {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" role="img" aria-labelledby="title desc" data-intent="${esc(intent)}" data-evidence="${esc(evidence)}" data-source-read="${esc(sourceRead)}">
  <title id="title">${esc(title)}</title>
  <desc id="desc">${esc(desc || subtitle)}</desc>
  ${defs({ sequence })}
  <rect class="canvas" width="${width}" height="${height}"/>
  <rect class="frame" x="32" y="28" width="${width - 64}" height="${height - 56}" rx="28"/>
  <text class="title" x="66" y="82">${esc(title)}</text>
  <text class="subtitle" x="70" y="116">${esc(subtitle)}</text>
${body}
</svg>`;
}

export function dotSourceFor(diagram) {
  const nodes = diagram.nodes.map((node) => `  "${node.id}" [label="${escDot(node.title)}"];`).join("\n");
  const edges = diagram.edges.map((edge) => `  "${edge.from}" -> "${edge.to}" [label="${escDot(edge.label || edge.tone || "")}"];`).join("\n");
  return `digraph "${diagram.slug}" {
  graph [rankdir=LR, bgcolor="transparent", splines=ortho, nodesep=0.65, ranksep=0.9];
  node [shape=box, style="rounded,filled", fillcolor="#F8FAFC", color="#94A3B8", fontname="Comic Mono"];
  edge [color="#758297", fontname="Comic Mono", fontsize=10];
${nodes}
${edges}
}
`;
}

export function escDot(value) {
  return String(value ?? "").replaceAll("\\", "\\\\").replaceAll('"', '\\"');
}

export function wrap(text, max) {
  const words = String(text).split(/\s+/);
  const lines = [];
  let line = "";
  for (const word of words) {
    if ((line + " " + word).trim().length > max && line) {
      lines.push(line);
      line = word;
    } else {
      line = (line + " " + word).trim();
    }
  }
  if (line) lines.push(line);
  return lines;
}
