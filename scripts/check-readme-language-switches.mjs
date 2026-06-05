#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { dirname, relative } from "node:path";

const root = process.cwd();
const localeOrder = [
  { code: "en", filename: "README.md", label: "English" },
  { code: "ko", filename: "README.ko.md", label: "한국어" },
  { code: "ja", filename: "README.ja.md", label: "日本語" },
  { code: "zh", filename: "README.zh.md", label: "中文" },
];
const localeByFilename = new Map(localeOrder.map((locale) => [locale.filename, locale]));

function sh(cmd, args) {
  return execFileSync(cmd, args, { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
}

function listReadmes() {
  return sh("find", [root, "-name", "README*.md", "-type", "f", "-print"])
    .trim()
    .split("\n")
    .filter(Boolean)
    .filter((path) => !path.includes("/.git/"))
    .filter((path) => !path.includes("/.gradle/"))
    .filter((path) => !path.includes("/.omx/"))
    .filter((path) => !path.includes("/build/"))
    .sort();
}

function groupReadmes(paths) {
  const groups = new Map();
  const failures = [];

  for (const path of paths) {
    const dir = dirname(path);
    const filename = path.slice(dir.length + 1);
    if (!localeByFilename.has(filename)) {
      failures.push(`${relative(root, path)}: unsupported localized README filename`);
      continue;
    }
    const files = groups.get(dir) ?? new Map();
    files.set(filename, path);
    groups.set(dir, files);
  }

  return { groups, failures };
}

function nonEmptyLineIndexes(lines) {
  return lines
    .map((line, index) => ({ line: line.trim(), index }))
    .filter((entry) => entry.line.length > 0);
}

function parseToken(token) {
  const link = token.match(/^\[([^\]]+)]\(([^)]+)\)$/);
  if (link) return { label: link[1], href: link[2], linked: true };
  return { label: token, href: "", linked: false };
}

function normalizeHref(href) {
  return href.replace(/^\.\//, "");
}

function validateFile(path, locale, expectedLocales) {
  const relativePath = relative(root, path);
  const lines = readFileSync(path, "utf8").split(/\r?\n/);
  const nonEmpty = nonEmptyLineIndexes(lines);
  const failures = [];

  if (!nonEmpty[0]?.line.startsWith("# ")) {
    failures.push(`${relativePath}: first non-empty line must be an H1 title`);
    return failures;
  }

  const switchEntry = nonEmpty[1];
  if (!switchEntry) {
    failures.push(`${relativePath}: missing language switch below title`);
    return failures;
  }

  const tokens = switchEntry.line.split("|").map((part) => parseToken(part.trim()));
  if (tokens.length !== expectedLocales.length) {
    failures.push(
      `${relativePath}:${switchEntry.index + 1}: expected ${expectedLocales.length} language entries, found ${tokens.length}`,
    );
    return failures;
  }

  tokens.forEach((token, index) => {
    const expected = expectedLocales[index];
    if (token.label !== expected.label) {
      failures.push(
        `${relativePath}:${switchEntry.index + 1}: entry ${index + 1} must be ${expected.label}, found ${token.label}`,
      );
      return;
    }
    if (expected.code === locale.code) {
      if (token.linked) {
        failures.push(`${relativePath}:${switchEntry.index + 1}: current locale ${expected.label} must be plain text`);
      }
      return;
    }
    if (!token.linked) {
      failures.push(`${relativePath}:${switchEntry.index + 1}: locale ${expected.label} must link to ${expected.filename}`);
      return;
    }
    if (normalizeHref(token.href) !== expected.filename) {
      failures.push(
        `${relativePath}:${switchEntry.index + 1}: locale ${expected.label} must link to ${expected.filename}, found ${token.href}`,
      );
    }
  });

  return failures;
}

const paths = listReadmes();
const { groups, failures } = groupReadmes(paths);
let checkedGroups = 0;
let checkedFiles = 0;

for (const [dir, files] of [...groups.entries()].sort(([a], [b]) => a.localeCompare(b))) {
  const presentLocales = localeOrder.filter((locale) => files.has(locale.filename));
  if (presentLocales.length <= 1) continue;

  checkedGroups += 1;
  if (!files.has("README.md")) {
    failures.push(`${relative(root, dir)}: localized README set must include README.md`);
    continue;
  }
  if (presentLocales.some((locale) => !["en", "ko"].includes(locale.code)) && !files.has("README.ko.md")) {
    failures.push(`${relative(root, dir)}: future README locales must append after README.ko.md`);
    continue;
  }

  for (const locale of presentLocales) {
    const path = files.get(locale.filename);
    checkedFiles += 1;
    failures.push(...validateFile(path, locale, presentLocales));
  }

}

if (failures.length) {
  console.error(`README language switch check failed: failures=${failures.length}`);
  for (const failure of failures) console.error(`- ${failure}`);
  process.exit(1);
}

console.log(`README language switches ok: groups=${checkedGroups}; files=${checkedFiles}; failures=0`);
