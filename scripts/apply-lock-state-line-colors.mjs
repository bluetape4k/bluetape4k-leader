#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { basename, join } from "node:path";

const root = process.cwd();
const diagramDir = join(root, "docs/images/readme-diagrams");

const routeColors = {
  neutral: "#758297",
  leader: "#43A76B",
  skipped: "#EF5B7A",
  contention: "#D99A2B",
  reacquire: "#8B6EEB",
};

const targetToneOrder = {
  "bluetape4k-leader-sequence-02": [
    "leader",
    "skipped",
    "leader",
    "skipped",
    "leader",
    "contention",
    "leader",
    "skipped",
    "skipped",
  ],
  "bluetape4k-leader-sequence-03": [
    "neutral",
    "leader",
    "leader",
    "leader",
    "leader",
    "contention",
    "leader",
    "skipped",
  ],
  "leader-core-sequence-02": [
    "neutral",
    "leader",
    "leader",
    "leader",
    "leader",
    "contention",
    "leader",
    "skipped",
    "skipped",
  ],
  "leader-core-sequence-03": [
    "neutral",
    "leader",
    "leader",
    "leader",
    "leader",
    "contention",
    "leader",
    "skipped",
  ],
  "leader-redis-lettuce-sequence-02": [
    "leader",
    "leader",
    "leader",
    "leader",
    "leader",
    "leader",
    "contention",
    "contention",
    "contention",
    "reacquire",
    "reacquire",
    "contention",
    "reacquire",
    "reacquire",
    "reacquire",
  ],
  "leader-redis-lettuce-sequence-03": [
    "leader",
    "leader",
    "leader",
    "contention",
    "contention",
    "contention",
    "skipped",
    "skipped",
    "skipped",
    "reacquire",
    "reacquire",
    "reacquire",
  ],
  "leader-redis-redisson-sequence-02": [
    "neutral",
    "neutral",
    "neutral",
    "leader",
    "leader",
    "leader",
    "leader",
    "leader",
    "contention",
    "contention",
    "reacquire",
    "reacquire",
    "contention",
    "reacquire",
    "reacquire",
  ],
  "leader-redis-redisson-sequence-03": [
    "leader",
    "leader",
    "leader",
    "contention",
    "contention",
    "skipped",
    "skipped",
    "reacquire",
    "reacquire",
  ],
  "leader-hazelcast-sequence-02": [
    "leader",
    "leader",
    "leader",
    "leader",
    "skipped",
    "skipped",
    "skipped",
    "contention",
    "contention",
    "contention",
    "contention",
    "contention",
    "contention",
    "contention",
    "skipped",
  ],
  "leader-hazelcast-sequence-03": [
    "neutral",
    "leader",
    "leader",
    "leader",
    "leader",
    "contention",
    "leader",
    "skipped",
  ],
  "leader-k8s-sequence-02": [
    "neutral",
    "neutral",
    "neutral",
    "leader",
    "leader",
    "skipped",
    "leader",
    "contention",
    "contention",
  ],
  "leader-spring-boot-sequence-02": [
    "neutral",
    "neutral",
    "neutral",
    "leader",
    "leader",
    "leader",
    "leader",
    "leader",
    "leader",
    "leader",
    "leader",
    "leader",
    "leader",
    "leader",
    "leader",
    "contention",
    "leader",
    "contention",
    "contention",
  ],
};

function semanticMarkerBlock() {
  return `  <!-- semantic lock-state route markers -->
${Object.entries(routeColors).map(([tone, color]) => `  <marker id="semanticArrow-${tone}" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto" markerUnits="strokeWidth"><path d="M 1 1 L 7 4 L 1 7" fill="none" stroke="${color}" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"/></marker>`).join("\n")}
  <!-- /semantic lock-state route markers -->
`;
}

function ensureMarkers(svg) {
  const withoutOldBlock = svg.replace(/\s*<!-- semantic lock-state route markers -->[\s\S]*?<!-- \/semantic lock-state route markers -->\n?/g, "\n");
  return withoutOldBlock.replace("</defs>", `${semanticMarkerBlock()}</defs>`);
}

function setAttr(tag, name, value) {
  if (new RegExp(`\\s${name}="[^"]*"`).test(tag)) {
    return tag.replace(new RegExp(`\\s${name}="[^"]*"`), ` ${name}="${value}"`);
  }
  const openingEnd = tag.endsWith("/>") ? tag.length - 2 : tag.indexOf(">");
  if (openingEnd < 0) {
    throw new Error(`Cannot add ${name} to malformed tag: ${tag}`);
  }
  return `${tag.slice(0, openingEnd).trimEnd()} ${name}="${value}"${tag.slice(openingEnd)}`;
}

function applyToneToPath(tag, tone) {
  const color = routeColors[tone];
  let updated = tag.replace(/\sdata-route-tone="[^"]*"/, "");
  updated = updated.replace(/\sstyle="[^"]*"/, "");
  updated = setAttr(updated, "data-route-tone", tone);
  return setAttr(updated, "style", `stroke:${color};marker-end:url(#semanticArrow-${tone})`);
}

function applyToneToText(tag, tone) {
  const color = routeColors[tone];
  const style = tag.match(/\sstyle="([^"]*)"/)?.[1] ?? "";
  const parts = style
    .split(";")
    .map((part) => part.trim())
    .filter((part) => part && !part.startsWith("fill:"));
  parts.push(`fill:${color}`);
  return setAttr(tag, "style", parts.join(";"));
}

function collectTokenMatches(svg) {
  const tokenRegex = /<text\b[^>]*class="[^"]*\bstrong\b[^"]*"[^>]*>[\s\S]*?<\/text>|<path\b[^>]*class="[^"]*\b(?:line|dashed|dash)\b[^"]*"[^>]*\/>/g;
  return [...svg.matchAll(tokenRegex)].map((match) => ({
    start: match.index,
    end: match.index + match[0].length,
    value: match[0],
    kind: match[0].startsWith("<text") ? "text" : "path",
  }));
}

function replaceRanges(svg, replacements) {
  let cursor = 0;
  let output = "";
  for (const replacement of replacements.sort((a, b) => a.start - b.start)) {
    output += svg.slice(cursor, replacement.start);
    output += replacement.value;
    cursor = replacement.end;
  }
  output += svg.slice(cursor);
  return output;
}

function applySemanticTones(svg, slug, tones) {
  const replacements = [];
  let pendingText = null;
  let pathIndex = 0;
  for (const token of collectTokenMatches(svg)) {
    if (token.kind === "text") {
      pendingText = token;
      continue;
    }
    const tone = tones[pathIndex];
    if (!tone) {
      throw new Error(`${slug}: missing semantic tone for path ${pathIndex + 1}`);
    }
    replacements.push({
      start: token.start,
      end: token.end,
      value: applyToneToPath(token.value, tone),
    });
    if (pendingText) {
      replacements.push({
        start: pendingText.start,
        end: pendingText.end,
        value: applyToneToText(pendingText.value, tone),
      });
      pendingText = null;
    }
    pathIndex += 1;
  }
  if (pathIndex !== tones.length) {
    throw new Error(`${slug}: expected ${tones.length} semantic paths but found ${pathIndex}`);
  }
  return replaceRanges(svg, replacements);
}

for (const [slug, tones] of Object.entries(targetToneOrder)) {
  const svgPath = join(diagramDir, `${slug}.svg`);
  const pngPath = join(diagramDir, `${slug}.png`);
  if (!existsSync(svgPath)) {
    throw new Error(`${slug}: missing SVG at ${svgPath}`);
  }
  const before = readFileSync(svgPath, "utf8");
  const after = applySemanticTones(ensureMarkers(before), slug, tones);
  writeFileSync(svgPath, after);
  execFileSync("rsvg-convert", ["-f", "png", "-o", pngPath, svgPath]);
  const counts = tones.reduce((acc, tone) => {
    acc[tone] = (acc[tone] ?? 0) + 1;
    return acc;
  }, {});
  console.log(`${basename(svgPath)} semantic routes=${tones.length} ${JSON.stringify(counts)}`);
}
