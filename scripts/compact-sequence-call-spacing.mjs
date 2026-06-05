#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const root = process.cwd();
const diagramDir = join(root, "docs/images/readme-diagrams");

const targetSlugs = [
  "bluetape4k-leader-sequence-02",
  "bluetape4k-leader-sequence-03",
  "leader-core-sequence-02",
  "leader-core-sequence-03",
  "leader-redis-lettuce-sequence-02",
  "leader-redis-lettuce-sequence-03",
  "leader-redis-redisson-sequence-02",
  "leader-redis-redisson-sequence-03",
  "leader-hazelcast-sequence-02",
  "leader-hazelcast-sequence-03",
  "leader-spring-boot-sequence-02",
];

const firstMessageTop = 286;
const groupGap = 28;
const bottomPadding = 72;

function numericAttr(tag, name, fallback = 0) {
  const match = tag.match(new RegExp(`${name}="([^"]*)"`));
  const value = Number.parseFloat(match?.[1] ?? "");
  return Number.isFinite(value) ? value : fallback;
}

function setAttr(tag, name, value) {
  const rendered = Number.isInteger(value) ? String(value) : value.toFixed(1).replace(/\.0$/, "");
  if (new RegExp(`\\s${name}="[^"]*"`).test(tag)) {
    return tag.replace(new RegExp(`\\s${name}="[^"]*"`), ` ${name}="${rendered}"`);
  }
  const openingEnd = tag.endsWith("/>") ? tag.length - 2 : tag.indexOf(">");
  return `${tag.slice(0, openingEnd).trimEnd()} ${name}="${rendered}"${tag.slice(openingEnd)}`;
}

function pathNumbers(d) {
  return [...d.matchAll(/[-+]?\d*\.?\d+(?:e[-+]?\d+)?/gi)].map((match) => Number.parseFloat(match[0]));
}

function dAttr(tag) {
  return tag.match(/\sd="([^"]*)"/)?.[1] ?? "";
}

function translatePathD(d, deltaY) {
  let index = 0;
  return d.replace(/[-+]?\d*\.?\d+(?:e[-+]?\d+)?/gi, (token) => {
    const value = Number.parseFloat(token);
    const translated = index % 2 === 1 ? value + deltaY : value;
    index += 1;
    return Number.isInteger(translated) ? String(translated) : translated.toFixed(1).replace(/\.0$/, "");
  });
}

function translateToken(token, deltaY) {
  if (token.kind === "rect" || token.kind === "text") {
    return setAttr(token.value, "y", numericAttr(token.value, "y") + deltaY);
  }
  const d = dAttr(token.value);
  return token.value.replace(/\sd="[^"]*"/, ` d="${translatePathD(d, deltaY)}"`);
}

function rectBounds(tag) {
  const y = numericAttr(tag, "y");
  return { top: y, bottom: y + numericAttr(tag, "height", 0) };
}

function textBounds(tag) {
  const y = numericAttr(tag, "y");
  return { top: y - 14, bottom: y + 8 };
}

function pathBounds(tag) {
  const numbers = pathNumbers(dAttr(tag));
  const ys = [];
  for (let index = 1; index < numbers.length; index += 2) {
    ys.push(numbers[index]);
  }
  return { top: Math.min(...ys), bottom: Math.max(...ys) };
}

function tokenBounds(token) {
  if (token.kind === "rect") return rectBounds(token.value);
  if (token.kind === "text") return textBounds(token.value);
  return pathBounds(token.value);
}

function bodyTokens(svg) {
  const tokenRegex =
    /<rect\b[^>]*\/>|<text\b[^>]*class="[^"]*\bstrong\b[^"]*"[^>]*>[\s\S]*?<\/text>|<path\b[^>]*class="[^"]*\b(?:line|dashed)\b[^"]*"[^>]*\/>/g;
  return [...svg.matchAll(tokenRegex)]
    .map((match) => {
      const value = match[0];
      const kind = value.startsWith("<rect") ? "rect" : value.startsWith("<text") ? "text" : "path";
      return { start: match.index, end: match.index + value.length, value, kind };
    })
    .filter((token) => token.kind !== "rect" || numericAttr(token.value, "y") >= firstMessageTop);
}

function sequenceGroups(tokens) {
  const groups = [];
  let pending = [];
  for (const token of tokens) {
    pending.push(token);
    if (token.kind !== "path") continue;
    groups.push(pending);
    pending = [];
  }
  if (pending.length > 0) {
    throw new Error(`Unmatched trailing sequence label tokens: ${pending.map((token) => token.value).join(" ")}`);
  }
  return groups;
}

function groupBounds(group) {
  const bounds = group.map(tokenBounds);
  return {
    top: Math.min(...bounds.map((bound) => bound.top)),
    bottom: Math.max(...bounds.map((bound) => bound.bottom)),
  };
}

function replaceRanges(svg, replacements) {
  let output = "";
  let cursor = 0;
  for (const replacement of replacements.sort((left, right) => left.start - right.start)) {
    output += svg.slice(cursor, replacement.start);
    output += replacement.value;
    cursor = replacement.end;
  }
  output += svg.slice(cursor);
  return output;
}

function updateCanvasHeight(svg, height) {
  const roundedHeight = Math.ceil(height);
  return svg
    .replace(/<svg\b([^>]*?)\sheight="[^"]+"([^>]*?)viewBox="0 0 ([\d.]+) [\d.]+"([^>]*)>/, (_match, before, middle, width, after) =>
      `<svg${before} height="${roundedHeight}"${middle}viewBox="0 0 ${width} ${roundedHeight}"${after}>`,
    )
    .replace(/<rect class="canvas" width="([^"]+)" height="[^"]+"\/>/, `<rect class="canvas" width="$1" height="${roundedHeight}"/>`)
    .replace(/<rect class="frame" x="32" y="28" width="([^"]+)" height="[^"]+" rx="28"\/>/, `<rect class="frame" x="32" y="28" width="$1" height="${roundedHeight - 56}" rx="28"/>`)
    .replace(/(<line class="lifeline"[^>]*\sy2=")[^"]+("[^>]*\/>)/g, `$1${roundedHeight - 60}$2`);
}

function compactSvg(svg, slug) {
  const tokens = bodyTokens(svg);
  const groups = sequenceGroups(tokens);
  const replacements = [];
  let nextTop = firstMessageTop;
  let lastBottom = firstMessageTop;

  for (const group of groups) {
    const bounds = groupBounds(group);
    const newTop = Math.max(nextTop, firstMessageTop);
    const deltaY = newTop - bounds.top;
    for (const token of group) {
      replacements.push({
        start: token.start,
        end: token.end,
        value: translateToken(token, deltaY),
      });
    }
    const newBottom = bounds.bottom + deltaY;
    lastBottom = Math.max(lastBottom, newBottom);
    nextTop = newBottom + groupGap;
  }

  const compacted = replaceRanges(svg, replacements);
  const newHeight = lastBottom + bottomPadding;
  const beforeHeight = numericAttr(svg.match(/<svg\b([^>]*)>/)?.[1] ?? "", "height");
  const after = updateCanvasHeight(compacted, newHeight);
  const afterHeight = Math.ceil(newHeight);
  console.log(`${slug}: groups=${groups.length} height ${beforeHeight}->${afterHeight}`);
  return after;
}

for (const slug of targetSlugs) {
  const svgPath = join(diagramDir, `${slug}.svg`);
  const pngPath = join(diagramDir, `${slug}.png`);
  if (!existsSync(svgPath)) throw new Error(`${slug}: missing ${svgPath}`);
  const svg = readFileSync(svgPath, "utf8");
  const compacted = compactSvg(svg, slug);
  writeFileSync(svgPath, compacted);
  execFileSync("rsvg-convert", ["-f", "png", "-o", pngPath, svgPath]);
}
