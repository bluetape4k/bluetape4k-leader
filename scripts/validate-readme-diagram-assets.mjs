#!/usr/bin/env node

import { readFileSync, readdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const diagramDir = "docs/images/readme-diagrams";
const chartDir = "docs/images/readme-charts";
const report = process.env.DIAGRAM_VALIDATION_REPORT || "/tmp/bluetape4k-leader-diagram-validation-report.json";
const forbidden = ["Inter", "Arial", "Helvetica", "Comic Sans", "Comic Neue", "sans-serif", "cursive"];
const files = process.env.DIAGRAM_VALIDATION_FILES
  ? process.env.DIAGRAM_VALIDATION_FILES.split(",").map((file) => file.trim()).filter(Boolean)
  : [
    ...safeList(diagramDir).filter((file) => file.endsWith(".svg") && !file.endsWith("-graphviz.svg")).map((file) => join(diagramDir, file)),
    ...safeList(chartDir).filter((file) => file.endsWith(".svg")).map((file) => join(chartDir, file)),
  ];

const rows = files.map((file) => validate(file, readFileSync(file, "utf8")));
const failures = rows.filter((row) => row.failures.length > 0);
writeFileSync(report, `${JSON.stringify({ total: rows.length, failed: failures.length, rows }, null, 2)}\n`);
for (const row of failures) {
  console.log(`${row.file}\t${row.failures.join("; ")}`);
}
console.error(`leader diagram validation: total=${rows.length} failed=${failures.length} report=${report}`);
if (failures.length > 0) process.exit(1);

function safeList(dir) {
  try {
    return readdirSync(dir);
  } catch {
    return [];
  }
}

function validate(file, svg) {
  const failures = [];
  for (const token of forbidden) {
    if (svg.includes(token)) failures.push(`forbidden font token ${token}`);
  }
  if (/Graphviz evidence|Source truth|geometry=PASS|validation evidence/i.test(svg)) {
    failures.push("internal evidence text leaked");
  }
  const title = svg.match(/<title[^>]*>([^<]+)<\/title>/)?.[1] || svg.match(/aria-label="([^"]+)"/)?.[1] || "";
  if (title && !/^[A-Z]/.test(title)) failures.push("title is not human-readable uppercase start");
  if (/diagram-01|architecture|overview|flow|sequence|class/i.test(file)) {
    if (!/data-intent="[^"]{90,}"/.test(svg)) failures.push("missing reader intent");
    if (!/data-source-read="[^"]*(README|settings\.gradle|src\/main|\.kt)/.test(svg)) failures.push("missing source-read marker");
  }
  if (/class/i.test(file)) {
    if (!/data-route-kind="inherit"/.test(svg)) failures.push("class diagram missing inheritance routes");
    if (!/(?:<<|&lt;&lt;)[A-Za-z0-9 -]+(?:>>|&gt;&gt;)/.test(svg)) failures.push("class diagram missing UML stereotypes");
    if (/leader-core-class/i.test(file) && !/LeaderRunResult|LeaderSlot/.test(svg)) failures.push("core class diagram missing result or slot support type");
  }
  if (/sequence/i.test(file)) {
    if (!/seqArrow-call|seqArrow-success|seqArrow-skip/.test(svg)) failures.push("sequence missing colored 5x5 arrow markers");
    if (!/class="pill"/.test(svg) || !/<circle\b/.test(svg)) failures.push("sequence missing numbered message pills");
    if (/<path[^>]*class="(?:line|dashed)"/.test(svg)) failures.push("sequence uses old line/dashed classes");
  }
  return { file, failures };
}
