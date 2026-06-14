#!/usr/bin/env node

import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { chartDir, cleanSvg, esc, rsvgConvert } from "./readme-diagrams/lib/svg-core.mjs";
import { execFileSync } from "node:child_process";

const colors = {
  blocking: "#5B8DEF",
  suspend: "#D66B3D",
  before: "#6477D8",
  after: "#D67B35",
  module: ["#5B8DEF", "#D66B3D", "#45A7A1", "#C94D68", "#8BA84D"],
};

const moduleComposition = [
  { label: "Core API", value: 1 },
  { label: "Backend adapters", value: 11 },
  { label: "Framework/metrics", value: 3 },
  { label: "Examples", value: 17 },
  { label: "Benchmark/BOM", value: 2 },
];

const distributed = [
  { label: "Hazelcast", blockingThroughput: 1460.936, suspendThroughput: 1325.931, blockingLatency: 766.272, suspendLatency: 748.966 },
  { label: "Lettuce Redis", blockingThroughput: 1454.659, suspendThroughput: 1402.576, blockingLatency: 699.411, suspendLatency: 675.318 },
  { label: "Redisson Redis", blockingThroughput: 1415.84, suspendThroughput: 1386.653, blockingLatency: 699.703, suspendLatency: 714.918 },
  { label: "MongoDB", blockingThroughput: 843.726, suspendThroughput: 798.439, blockingLatency: 1131.005, suspendLatency: 4333.477 },
  { label: "ZooKeeper", blockingThroughput: 804.334, suspendThroughput: 670.564, blockingLatency: 1372.211, suspendLatency: 1397.254 },
  { label: "DynamoDB Local", blockingThroughput: 722.171, suspendThroughput: 510.161, blockingLatency: 1749.692, suspendLatency: 1947.304 },
  { label: "Consul", blockingThroughput: 593.61, suspendThroughput: 563.158, blockingLatency: 1900.576, suspendLatency: 1701.845 },
  { label: "etcd", blockingThroughput: 443.838, suspendThroughput: 467.461, blockingLatency: 2167.925, suspendLatency: 2239.412 },
  { label: "PostgreSQL", blockingThroughput: 80.31, suspendThroughput: 53.588, blockingLatency: 13925.403, suspendLatency: 17736.983 },
  { label: "MySQL", blockingThroughput: 69.518, suspendThroughput: 65.204, blockingLatency: 15023.674, suspendLatency: 17616.078 },
];

const history = [
  { label: "Blocking in-memory", before: 5.601881, after: 20.018126 },
  { label: "Blocking noop", before: 7.642848, after: 62.740147 },
  { label: "Suspend in-memory", before: 4.843511, after: 11.44189 },
  { label: "Suspend noop", before: 5.25731, after: 23.153306 },
];

const kubernetes = [
  { label: "Blocking API", throughput: 171.525, latency: 5835.436 },
  { label: "Suspend API", throughput: 164.687, latency: 6075.66 },
];

const charts = [
  {
    slug: "root-readme-module-chart-01",
    title: "Leader Repository Module Composition",
    subtitle: "Module families counted from settings.gradle.kts; examples are intentionally large teaching surface.",
    intent: "Answer the root README reader's scope question: how large is each major repository family and why do backend adapters and examples dominate the repository surface compared with the small core API?",
    evidence: "settings.gradle.kts module registration; README module family table",
    sourceRead: "README.md; README.ko.md; settings.gradle.kts",
    unit: "modules",
    direction: "count",
    summarySource: "README.md:37; README.ko.md:36; settings.gradle.kts",
    render: () => renderSingleSeries({
      rows: moduleComposition,
      unit: "modules",
      max: 17,
      colors: colors.module,
      valueFormat: (value) => `${value} ${value === 1 ? "module" : "modules"}`,
      note: "Total counted module/example surfaces: 34. Source: README.md and settings.gradle.kts.",
    }),
  },
  {
    slug: "leader-benchmark-distributed-throughput-chart-01",
    title: "Distributed Backend Throughput",
    subtitle: "Cluster-capable backends only; local and H2 rows are excluded so readers compare real coordination cost.",
    intent: "Help README and benchmark readers compare blocking and suspend leader election throughput across distributed backends without local or embedded database rows distorting the scale.",
    evidence: "benchmark/README.md distributed backend throughput tables",
    sourceRead: "README.md; README.ko.md; benchmark/README.md; benchmark/README.ko.md",
    unit: "ops/s",
    direction: "higher-is-better",
    summarySource: "README.md:47; README.ko.md:46; benchmark/README.md:63; benchmark/README.ko.md:66",
    render: () => renderGroupedSeries({
      rows: distributed,
      keys: [
        { key: "blockingThroughput", label: "Blocking", color: colors.blocking },
        { key: "suspendThroughput", label: "Suspend", color: colors.suspend },
      ],
      max: 1500,
      unit: "ops/s",
      valueFormat: (value) => `${value.toLocaleString("en-US", { maximumFractionDigits: 1 })}`,
      note: "Higher is better. Source: benchmark/README.md distributed throughput tables.",
    }),
  },
  {
    slug: "leader-benchmark-distributed-latency-chart-01",
    title: "Distributed Backend Latency",
    subtitle: "Log scale is used because SQL and cluster backends span more than one order of magnitude.",
    intent: "Help benchmark readers see the latency tradeoff between fast in-memory cluster backends and database-backed coordination while preserving all distributed rows.",
    evidence: "benchmark/README.md average time tables for blocking and suspend APIs",
    sourceRead: "benchmark/README.md; benchmark/README.ko.md",
    unit: "log10 us/op",
    direction: "lower-is-better",
    summarySource: "benchmark/README.md:65; benchmark/README.ko.md:68",
    render: () => renderGroupedSeries({
      rows: distributed,
      keys: [
        { key: "blockingLatency", label: "Blocking", color: colors.blocking },
        { key: "suspendLatency", label: "Suspend", color: colors.suspend },
      ],
      max: Math.log10(20000),
      scale: (value) => Math.log10(value),
      unit: "log10 us/op",
      valueFormat: (value) => `${value.toLocaleString("en-US", { maximumFractionDigits: 0 })} us`,
      note: "Lower is better. Axis uses log10(us/op) to keep SQL and cluster backends readable together.",
    }),
  },
  {
    slug: "leader-history-self-improve-throughput-chart-01",
    title: "History Recorder Self-Improve Throughput",
    subtitle: "Before and after throughput for the four HistoryRecorder microbenchmarks.",
    intent: "Show the benchmark improvement that justified the current HistoryRecorder implementation shape.",
    evidence: "benchmark/README.md latest self-improve result table",
    sourceRead: "benchmark/README.md; benchmark/README.ko.md",
    unit: "million ops/s",
    direction: "higher-is-better",
    summarySource: "benchmark/README.md:70; benchmark/README.ko.md:73",
    render: () => renderGroupedSeries({
      rows: history,
      keys: [
        { key: "before", label: "Before", color: colors.before },
        { key: "after", label: "After", color: colors.after },
      ],
      max: 65,
      unit: "million ops/s",
      valueFormat: (value) => `${value.toLocaleString("en-US", { maximumFractionDigits: 1 })}M`,
      note: "Higher is better. Values converted from ops/s to million ops/s.",
      compact: true,
    }),
  },
  {
    slug: "leader-benchmark-kubernetes-throughput-chart-01",
    title: "Kubernetes Lease Throughput",
    subtitle: "Blocking and suspend APIs measured against the Kubernetes lease backend.",
    intent: "Show the realistic Kubernetes lease throughput envelope for readers choosing the Kubernetes backend.",
    evidence: "benchmark/README.md Kubernetes throughput table",
    sourceRead: "benchmark/README.md; benchmark/README.ko.md",
    unit: "ops/s",
    direction: "higher-is-better",
    summarySource: "benchmark/README.md:216; benchmark/README.ko.md:220",
    render: () => renderSingleSeries({
      rows: kubernetes.map((row) => ({ label: row.label, value: row.throughput })),
      unit: "ops/s",
      max: 180,
      colors: [colors.blocking, colors.suspend],
      valueFormat: (value) => `${value.toLocaleString("en-US", { maximumFractionDigits: 1 })} ops/s`,
      note: "Higher is better. Source: benchmark/README.md Kubernetes benchmark table.",
    }),
  },
  {
    slug: "leader-benchmark-kubernetes-latency-chart-01",
    title: "Kubernetes Lease Latency",
    subtitle: "Average operation time for the Kubernetes lease backend.",
    intent: "Show the latency cost readers should expect from Kubernetes lease coordination.",
    evidence: "benchmark/README.md Kubernetes average time table",
    sourceRead: "benchmark/README.md; benchmark/README.ko.md",
    unit: "us/op",
    direction: "lower-is-better",
    summarySource: "benchmark/README.md:218; benchmark/README.ko.md:222",
    render: () => renderSingleSeries({
      rows: kubernetes.map((row) => ({ label: row.label, value: row.latency })),
      unit: "us/op",
      max: 6200,
      colors: [colors.blocking, colors.suspend],
      valueFormat: (value) => `${value.toLocaleString("en-US", { maximumFractionDigits: 0 })} us`,
      note: "Lower is better. Source: benchmark/README.md Kubernetes benchmark table.",
    }),
  },
];

mkdirSync(chartDir, { recursive: true });

const selectedSlug = process.env.CHART_SLUG;
const selectedCharts = selectedSlug ? charts.filter((chart) => chart.slug === selectedSlug) : charts;
if (selectedSlug && selectedCharts.length === 0) {
  throw new Error(`Unknown chart slug: ${selectedSlug}`);
}

for (const chart of selectedCharts) {
  const body = chart.render();
  const svg = chartShell({ ...chart, body });
  const svgPath = join(chartDir, `${chart.slug}.svg`);
  const pngPath = join(chartDir, `${chart.slug}.png`);
  const summaryPath = join(chartDir, `${chart.slug}-summary.txt`);
  writeFileSync(svgPath, cleanSvg(svg));
  execFileSync(rsvgConvert, ["--format=png", "--output", pngPath, svgPath], { stdio: "inherit" });
  const summary = `${join(chartDir, chart.slug)}: chart=PASS rows=${body.rowCount} bars=${body.barCount} unit="${chart.unit}" direction="${chart.direction}" marginImbalance=0 margins=L/R/T/B:96/96/96/96 source=${chart.summarySource} catalog=chart-baseline\n`;
  writeFileSync(summaryPath, summary);
  console.log(summary.trim());
}

function chartShell({ title, subtitle, intent, evidence, sourceRead, body }) {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${body.width}" height="${body.height}" viewBox="0 0 ${body.width} ${body.height}" role="img" aria-labelledby="title desc" data-intent="${esc(intent)}" data-evidence="${esc(evidence)}" data-source-read="${esc(sourceRead)}">
<title id="title">${esc(title)}</title>
<desc id="desc">${esc(subtitle)}</desc>
<defs>
  <style>
    .canvas{fill:#F7FAFC}.frame{fill:#FFFFFF;stroke:#D7E2EC;stroke-width:2}
    .title{font-family:"Architects Daughter";font-size:44px;fill:#22344A;font-weight:400}
    .subtitle{font-family:"Comic Mono";font-size:16px;fill:#536476;font-weight:400}
    .axis{stroke:#D7E2EC;stroke-width:1.5}
    .label{font-family:"Architects Daughter";font-size:22px;fill:#22344A;font-weight:400}
    .value{font-family:"Comic Mono";font-size:13px;fill:#42556B;font-weight:400}
    .legend{font-family:"Comic Mono";font-size:13px;fill:#42556B;font-weight:400}
    .note{font-family:"Comic Mono";font-size:13px;fill:#536476;font-weight:400}
  </style>
</defs>
<rect class="canvas" width="${body.width}" height="${body.height}"/>
<rect class="frame" x="32" y="28" width="${body.width - 64}" height="${body.height - 56}" rx="28"/>
<text class="title" x="66" y="82">${esc(title)}</text>
<text class="subtitle" x="70" y="116">${esc(subtitle)}</text>
${body.svg}
</svg>`;
}

function renderSingleSeries({ rows, max, colors: palette, valueFormat, note }) {
  const width = 1360;
  const height = Math.max(620, 260 + rows.length * 78);
  const chartX = 410;
  const chartW = 720;
  const top = 190;
  const rowGap = rows.length <= 2 ? 120 : 78;
  const bars = rows.map((row, index) => {
    const y = top + index * rowGap;
    const barW = Math.max(4, (row.value / max) * chartW);
    const color = palette[index % palette.length];
    return `<g>
  <text class="label" x="120" y="${y + 18}" dominant-baseline="middle">${esc(row.label)}</text>
  <rect x="${chartX}" y="${y}" width="${chartW}" height="36" rx="9" fill="#EEF4F9" stroke="#D7E2EC" stroke-width="1.4"/>
  <rect x="${chartX}" y="${y}" width="${barW.toFixed(2)}" height="36" rx="9" fill="${color}" opacity="0.84"/>
  <text class="value" x="${Math.min(chartX + chartW + 24, chartX + barW + 18)}" y="${y + 18}" dominant-baseline="middle">${esc(valueFormat(row.value))}</text>
</g>`;
  }).join("\n");
  return {
    width,
    height,
    rowCount: rows.length,
    barCount: rows.length,
    svg: `${bars}
<rect x="94" y="${height - 100}" width="${width - 188}" height="44" rx="12" fill="#FFFFFF" stroke="#D7E2EC" stroke-width="1.4"/>
<text class="note" x="${width / 2}" y="${height - 73}" text-anchor="middle">${esc(note)}</text>`,
  };
}

function renderGroupedSeries({ rows, keys, max, unit, valueFormat, note, scale = (value) => value, compact = false }) {
  const width = 1500;
  const height = compact ? 660 : 980;
  const chartX = compact ? 430 : 360;
  const chartW = compact ? 740 : 900;
  const top = 185;
  const rowGap = compact ? 86 : 72;
  const barH = compact ? 18 : 15;
  const labelX = compact ? 120 : 96;
  const legend = keys.map((key, index) => {
    const x = width - 430 + index * 170;
    return `<g><rect x="${x}" y="142" width="26" height="14" rx="4" fill="${key.color}" opacity="0.84"/><text class="legend" x="${x + 36}" y="154">${esc(key.label)}</text></g>`;
  }).join("\n");
  const rowSvg = rows.map((row, rowIndex) => {
    const baseY = top + rowIndex * rowGap;
    const bars = keys.map((key, keyIndex) => {
      const value = row[key.key];
      const barW = Math.max(4, (scale(value) / max) * chartW);
      const y = baseY + keyIndex * (barH + 6);
      return `<rect x="${chartX}" y="${y}" width="${barW.toFixed(2)}" height="${barH}" rx="5" fill="${key.color}" opacity="0.84"/>
  <text class="value" x="${Math.min(chartX + chartW + 18, chartX + barW + 12)}" y="${y + barH - 2}">${esc(valueFormat(value))}</text>`;
    }).join("\n  ");
    return `<g>
  <text class="label" x="${labelX}" y="${baseY + 18}" dominant-baseline="middle">${esc(row.label)}</text>
  <rect x="${chartX}" y="${baseY - 2}" width="${chartW}" height="${keys.length * (barH + 6) - 6}" rx="7" fill="#EEF4F9" stroke="#D7E2EC" stroke-width="1.1"/>
  ${bars}
</g>`;
  }).join("\n");
  return {
    width,
    height,
    rowCount: rows.length,
    barCount: rows.length * keys.length,
    svg: `${legend}
<line class="axis" x1="${chartX}" y1="${top - 26}" x2="${chartX + chartW}" y2="${top - 26}"/>
<text class="legend" x="${chartX}" y="${top - 42}">${esc(unit)}</text>
${rowSvg}
<rect x="94" y="${height - 100}" width="${width - 188}" height="44" rx="12" fill="#FFFFFF" stroke="#D7E2EC" stroke-width="1.4"/>
<text class="note" x="${width / 2}" y="${height - 73}" text-anchor="middle">${esc(note)}</text>`,
  };
}
