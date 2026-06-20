#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const iconRoot = "/Users/debop/work/bluetape4k/bluetape4k-wiki/docs/icons";
const args = new Set(process.argv.slice(2));
const write = args.has("--write");
const verbose = args.has("--verbose");
const targets = fs
  .readdirSync(path.join(root, "docs/images/readme-diagrams"))
  .filter((name) => name.endsWith(".svg"))
  .map((name) => path.join("docs/images/readme-diagrams", name));

const icons = {
  redis: "redis/redis-icon.svg",
  dynamodb: "testcontainers/aws/amazon-dynamodb.svg",
  kubernetes: "testcontainers/generic/kubernetes.svg",
  mongodb: "testcontainers/storage/mongodb.svg",
  zookeeper: "testcontainers/infra/apache-zookeeper.svg",
  hazelcast: "cache/hazelcast.svg",
  consul: "testcontainers/infra/consul.svg",
  etcd: "testcontainers/infra/etcd.svg",
  prometheus: "testcontainers/infra/prometheus.svg",
  grafana: "testcontainers/infra/grafana.svg",
  database: "generic/database-server.svg",
  postgresql: "testcontainers/database/postgresql.svg",
  mysql: "testcontainers/database/mysql.svg",
  springBoot: "spring/spring-boot.svg",
};

const iconData = new Map();
for (const [key, relative] of Object.entries(icons)) {
  const file = path.join(iconRoot, relative);
  const svg = fs.readFileSync(file);
  iconData.set(key, {
    relative,
    href: `data:image/svg+xml;base64,${svg.toString("base64")}`,
  });
}

const rectRe = /<rect\b[^>]*>/g;
const textRe = /<text\b([^>]*)>([^<]+)<\/text>/g;

function attr(tag, name) {
  const match = tag.match(new RegExp(`\\b${name}="([^"]+)"`));
  return match ? match[1] : null;
}

function num(tag, name, fallback = 0) {
  const value = attr(tag, name);
  return value == null ? fallback : Number(value);
}

function iconFor(file, text) {
  const t = text.replace(/&gt;/g, ">").replace(/&lt;/g, "<");
  const lower = t.toLowerCase();
  const basename = path.basename(file).toLowerCase();

  if (/github\.com|->/.test(t)) return null;
  if (/,|\/bluetape4k/.test(t)) return null;
  if (t.length > 58) return null;
  if ((t.match(/\s+/g) ?? []).length > 6) return null;
  const techFamilies = [
    /redis|lettuce|redisson/i,
    /dynamodb/i,
    /kubernetes|\bk8s\b/i,
    /mongodb/i,
    /zookeeper/i,
    /hazelcast/i,
    /consul/i,
    /\betcd\b|jetcd/i,
    /prometheus/i,
    /grafana/i,
    /mysql/i,
    /postgresql/i,
  ].filter((re) => re.test(t)).length;
  if (techFamilies > 1) return null;

  const codeOnly =
    /(\b[A-Za-z]*Elector\b|Scheduler|Runner|Report|Options|Endpoint|Factory|Client|Registry|Strategy|Configuration|DTO|Result|LeaseLock|LockHandle|Delegate|RedissonLeader|RedissonSuspend)/.test(
      t,
    ) && !/(api server|table|lock table|export table|kv lock|kv ownership|session api|redis lock|redis zset|redis lua|redis backend|prometheus|grafana|rlock)/i.test(t);
  if (codeOnly) return null;
  if (/KubernetesLease|DynamoDb[A-Z]|Hazelcast[A-Z]|ZooKeeper[A-Z]/.test(t)) return null;
  if (/keyPrefix|operation|activated only when/i.test(t)) return null;

  if (/prometheus/i.test(t)) return "prometheus";
  if (/grafana/i.test(t)) return "grafana";
  if (/redis|redisson rlock|redisson backend|redis zset|redis lua/i.test(t)) return "redis";
  if (/dynamodb|lock table|export table/i.test(t) && basename.includes("dynamodb")) return "dynamodb";
  if (/kubernetes|\bk8s\b|api server|lease object/i.test(t) && /(k8s|kubernetes)/.test(basename)) return "kubernetes";
  if (/mongodb/i.test(t)) return "mongodb";
  if (/zookeeper/i.test(t)) return "zookeeper";
  if (/hazelcast imap|hazelcast\b/i.test(t) && !/LeaderElector|Factory/.test(t)) return "hazelcast";
  if (/consul agent|consul kv|consul session|session api|kv ownership|consul keyprefix/i.test(t)) return "consul";
  if (/\betcd\b|jetcd|lease key|leader key/i.test(t)) return "etcd";
  if (/postgresql/i.test(t)) return "postgresql";
  if (/mysql/i.test(t)) return "mysql";
  if (/database|shared database|leaderlocktable|migrationmarkertable|r2dbc leader table|runtime tables|table erd/i.test(t)) {
    return "database";
  }
  if (/spring boot|actuator/i.test(t) && /spring|prometheus/.test(basename)) return "springBoot";
  return null;
}

function textItems(fragment) {
  const items = [];
  for (const match of fragment.matchAll(textRe)) {
    items.push({
      tag: match[0],
      attrs: match[1],
      text: match[2],
      x: Number(attr(match[1], "x")),
      y: Number(attr(match[1], "y")),
    });
  }
  return items;
}

function containedTexts(file, rect, fragment) {
  const x = num(rect, "x", 0);
  const y = num(rect, "y", 0);
  const width = num(rect, "width", 0);
  const height = num(rect, "height", 0);
  if (width < 52 || height < 44) return [];
  if (width > 430 || height > 190) return [];

  const items = textItems(fragment);
  const contained = items.filter((item) => {
    if (!Number.isFinite(item.x) || !Number.isFinite(item.y)) return false;
    const absolute = item.x >= x - 2 && item.x <= x + width + 2 && item.y >= y - 2 && item.y <= y + height + 34;
    const local = item.x >= -2 && item.x <= width + 2 && item.y >= -2 && item.y <= height + 34;
    return absolute || local;
  });
  const topLines = contained.slice(0, 3).map((item) => item.text).join(" ");
  const firstLine = contained[0]?.text ?? "";
  const firstLineIsCodeOnly = /(\b[A-Za-z]*Elector\b|Scheduler|Runner|Report|Options|Endpoint|Factory|Client|Registry|Strategy|Configuration|DTO|Result|LeaseLock|LockHandle|Delegate|RedissonLeader|RedissonSuspend)/.test(
    firstLine,
  );
  if (firstLineIsCodeOnly && !/(api server|table|lock table|export table|kv lock|kv ownership|session api|redis lock|redis zset|redis lua|redis backend|prometheus|grafana|rlock)/i.test(firstLine)) return [];
  const topLinesAreCodeOnly =
    /(\b[A-Za-z]*Elector\b|Scheduler|Runner|Report|Options|Endpoint|Factory|Client|Registry|Strategy|Configuration|DTO|Result|LeaseLock|LockHandle|Delegate|RedissonLeader|RedissonSuspend)/.test(
      topLines,
    ) && !/(api server|table|lock table|export table|kv lock|kv ownership|session api|redis lock|redis zset|redis lua|redis backend|prometheus|grafana|rlock)/i.test(topLines);
  if (topLinesAreCodeOnly) return [];
  return contained.map((item) => item.text).filter((text) => iconFor(file, text));
}

function iconElement(file, rect, iconKey) {
  const x = num(rect, "x", 0);
  const y = num(rect, "y", 0);
  const size = Math.min(24, Math.max(18, Math.min(num(rect, "width", 0), num(rect, "height", 0)) * 0.18));
  const ix = x + 10;
  const iy = y + 8;
  const icon = iconData.get(iconKey);
  return `<image data-bluetape4k-icon="${iconKey}" data-icon-source="${icon.relative}" x="${ix}" y="${iy}" width="${size}" height="${size}" href="${icon.href}" preserveAspectRatio="xMidYMid meet"/>`;
}

function applyIcons(file, svg) {
  const source = svg.replace(/<image\b[^>]*data-bluetape4k-icon="[^"]+"[^>]*\/>/g, "");
  let out = "";
  let cursor = 0;
  let count = 0;
  for (const match of source.matchAll(rectRe)) {
    const rect = match[0];
    const start = match.index;
    const end = start + rect.length;
    out += source.slice(cursor, end);
    cursor = end;

    const lookaheadEnd = Math.min(source.length, end + 1400);
    const nextRect = source.indexOf("<rect", end);
    const nextCloseGroup = source.indexOf("</g>", end);
    const stop = [nextRect, nextCloseGroup, lookaheadEnd].filter((n) => n > end).sort((a, b) => a - b)[0] ?? lookaheadEnd;
    const fragment = source.slice(end, stop);
    if (fragment.includes("data-bluetape4k-icon")) continue;
    const candidates = containedTexts(file, rect, fragment);
    if (!candidates.length) continue;

    const iconKey = iconFor(file, candidates.join(" "));
    if (!iconKey) continue;
    if (verbose) console.log(`  ${file}: ${iconKey} <= ${candidates.join(" | ")}`);
    out += iconElement(file, rect, iconKey);
    count += 1;
  }
  out += source.slice(cursor);
  return { svg: out, count };
}

let changed = 0;
let iconCount = 0;
for (const file of targets) {
  const full = path.join(root, file);
  const original = fs.readFileSync(full, "utf8");
  const next = applyIcons(file, original);
  if (next.svg !== original) {
    changed += 1;
    iconCount += next.count;
    console.log(`${file}: icons=${next.count}`);
    if (write) fs.writeFileSync(full, next.svg);
  }
}

console.log(`${write ? "updated" : "would_update"}=${changed} icons=${iconCount}`);
