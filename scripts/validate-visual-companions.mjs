#!/usr/bin/env node

import { readFile, realpath } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPOSITORY = 'bluetape4k/bluetape4k-leader';
const RELEASE_REF = '0.4.0';
const RELEASE_COMMIT = '17ab7f872c1f96318c73d3580729cac20a67e017';
const DESIGN_BASELINE = 'ed346a14bf223b0eb456198fdee617b608795b54';
const DESIGN_FILE = '2026-07-30-leader-election-visual-companions-design.md';

const forbidden = [
  /<script\b[^>]*\bsrc\s*=/i,
  /<link\b[^>]*\brel\s*=\s*["']?stylesheet\b/i,
  /<(?:img|iframe|audio|video|source)\b[^>]*\bsrc\s*=\s*["'](?!data:|#)[^"']+["']/i,
  /<form\b/i,
  /\bfetch\s*\(/,
  /\bXMLHttpRequest\b/,
  /\bWebSocket\s*\(/,
  /\bnavigator\.sendBeacon\s*\(/,
  /\bEventSource\s*\(/,
];

const commonPatterns = [
  [/^\s*<!doctype html>/i, 'must start with doctype'],
  [/<meta\b[^>]*name=["']color-scheme["'][^>]*content=["']light dark["']/i, 'must support light and dark color schemes'],
  [/:root\[data-theme=["']light["']\]/i, 'must define light theme tokens'],
  [/:root\[data-theme=["']dark["']\]/i, 'must define dark theme tokens'],
  [/prefers-reduced-motion/i, 'must respect reduced motion'],
  [new RegExp(`data-design-baseline=["']${DESIGN_BASELINE}["']`, 'i'), 'must pin the design baseline'],
  [new RegExp(`data-release-ref=["']${RELEASE_REF.replace('.', '\\.')}["']`, 'i'), 'must pin the release ref'],
  [new RegExp(`data-release-commit=["']${RELEASE_COMMIT}["']`, 'i'), 'must pin the release commit'],
  [/data-step=["']model["']/i, 'must expose the model step'],
  [/data-step=["']settings["']/i, 'must expose the settings step'],
  [/data-step=["']direct-api["']/i, 'must expose the direct API step'],
  [/data-step=["']spring["']/i, 'must expose the Spring step'],
  [/data-step=["']recovery["']/i, 'must expose the recovery step'],
  [/aria-live=["']polite["']/i, 'must expose polite live status'],
  [/aria-pressed=/i, 'must expose pressed state'],
];

const contracts = {
  'leader-elector': {
    sections: ['model', 'settings', 'direct-api', 'spring', 'recovery', 'sources'],
    scenarios: ['contention', 'expiry', 'extension'],
    controls: ['action-time', 'lease-time'],
    candidates: ['node-a', 'node-b', 'node-c'],
    markers: [
      'LeaderElector',
      'runIfLeader',
      'runIfLeaderResult',
      'LeaderRunResult.Elected',
      'LeaderRunResult.Skipped',
      'LeaderRunResult.ActionFailed',
      'waitTime',
      'leaseTime',
      'minLeaseTime',
      'autoExtend',
      '@LeaderElection',
      'token',
      'TTL',
      'stale',
    ],
    sourceLinks: [
      'LeaderElector.kt',
      'LeaderElectionOptions.kt',
      'LeaderRunResult.kt',
      'LettuceLeaderElector.kt',
      'LeaderElection.kt',
      'runtime-model.md',
    ],
  },
  'leader-group-elector': {
    sections: ['model', 'settings', 'direct-api', 'spring', 'recovery', 'sources'],
    scenarios: ['capacity', 'saturation', 'expiry'],
    controls: ['candidate-count', 'max-leaders', 'action-time', 'lease-time'],
    stateFields: ['activeCount', 'availableSlots', 'isFull'],
    markers: [
      'LeaderGroupElector',
      'LeaderGroupState',
      'maxLeaders',
      'activeCount',
      'availableSlots',
      'isFull',
      '@LeaderGroupElection',
      'Flux',
      'Flow',
      'unique work',
      'token',
    ],
    sourceLinks: [
      'LeaderGroupElector.kt',
      'LeaderGroupElectionOptions.kt',
      'LeaderGroupState.kt',
      'LettuceLeaderGroupElector.kt',
      'LettuceSlotTokenGroup.kt',
      'LeaderGroupElection.kt',
      'single-group-strategic.md',
    ],
  },
};

function contained(root, relative) {
  if (typeof relative !== 'string' || relative.length === 0) {
    throw new Error('path must be a non-empty string');
  }
  const absolute = path.resolve(root, relative);
  if (absolute !== root && !absolute.startsWith(`${root}${path.sep}`)) {
    throw new Error(`path escapes repository: ${relative}`);
  }
  return absolute;
}

async function readContained(root, relative, errors, label, encoding = 'utf8') {
  let absolute;
  try {
    absolute = contained(root, relative);
  } catch (error) {
    errors.push(`${label} ${error.message}`);
    return null;
  }
  try {
    const resolved = await realpath(absolute);
    if (resolved !== root && !resolved.startsWith(`${root}${path.sep}`)) {
      errors.push(`${label} resolves outside repository`);
      return null;
    }
    return await readFile(resolved, encoding);
  } catch {
    errors.push(`${label} does not exist: ${relative}`);
    return null;
  }
}

function values(content, pattern) {
  return [...content.matchAll(pattern)].map((match) => match[1]).sort();
}

function requireText(errors, content, value, message) {
  if (!content.includes(value)) errors.push(message);
}

function requirePattern(errors, content, pattern, message) {
  if (!pattern.test(content)) errors.push(message);
}

function crc32(buffer) {
  let crc = 0xffffffff;
  for (const byte of buffer) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit += 1) {
      crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1));
    }
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function parsePngDimensions(buffer) {
  const signature = '89504e470d0a1a0a';
  if (buffer.length < 8 || buffer.subarray(0, 8).toString('hex') !== signature) {
    throw new Error('must be a PNG file');
  }
  if (buffer.length < 45) {
    throw new Error('must contain a complete PNG structure');
  }

  let offset = 8;
  let width;
  let height;
  let chunkIndex = 0;
  let hasImageData = false;
  let hasEnd = false;

  while (offset < buffer.length) {
    if (buffer.length - offset < 12) {
      throw new Error('must contain a complete PNG structure');
    }
    const length = buffer.readUInt32BE(offset);
    const chunkEnd = offset + 12 + length;
    if (chunkEnd > buffer.length) {
      throw new Error('must contain a complete PNG structure');
    }

    const typeStart = offset + 4;
    const dataStart = offset + 8;
    const type = buffer.subarray(typeStart, dataStart).toString('ascii');
    const storedCrc = buffer.readUInt32BE(dataStart + length);
    const computedCrc = crc32(buffer.subarray(typeStart, dataStart + length));
    if (storedCrc !== computedCrc) {
      throw new Error(`contains an invalid ${type || 'unknown'} chunk checksum`);
    }

    if (chunkIndex === 0) {
      if (type !== 'IHDR' || length !== 13) {
        throw new Error('must begin with a valid IHDR chunk');
      }
      width = buffer.readUInt32BE(dataStart);
      height = buffer.readUInt32BE(dataStart + 4);
      if (width === 0 || height === 0) {
        throw new Error('must have non-zero dimensions');
      }
    } else if (type === 'IHDR') {
      throw new Error('must contain exactly one IHDR chunk');
    }

    if (type === 'IDAT') hasImageData = true;
    if (type === 'IEND') {
      if (length !== 0 || chunkEnd !== buffer.length) {
        throw new Error('must end with a valid IEND chunk');
      }
      hasEnd = true;
      offset = chunkEnd;
      break;
    }

    offset = chunkEnd;
    chunkIndex += 1;
  }

  if (!hasImageData || !hasEnd || offset !== buffer.length) {
    throw new Error('must contain a complete PNG structure');
  }
  return { width, height };
}

function validatePresentation(document, field, errors) {
  const presentation = document.presentation;
  if (
    presentation?.mode !== 'simulation'
    || presentation?.defaultView !== 'simulation'
    || presentation?.views?.length !== 1
    || presentation.views[0] !== 'simulation'
  ) {
    errors.push(`${field}.presentation must expose only the simulation view`);
  }
}

async function validateLocale(root, document, locale, errors) {
  const localeEntry = document.locales?.[locale];
  const prefix = `${document.id}.${locale}`;
  if (!localeEntry?.title || !localeEntry?.html || !localeEntry?.route || !localeEntry?.fallback) {
    errors.push(`${prefix} must define title, html, route, and fallback`);
    return null;
  }

  const expectedRoute = `${locale === 'ko' ? '/ko' : ''}/visual-companions/bluetape4k-leader/${document.id}/`;
  if (localeEntry.route !== expectedRoute) {
    errors.push(`${prefix}.route must be ${expectedRoute}`);
  }

  const content = await readContained(root, localeEntry.html, errors, `${prefix}.html`);
  const fallback = await readContained(root, localeEntry.fallback, errors, `${prefix}.fallback`, null);
  if (fallback) {
    try {
      const { width, height } = parsePngDimensions(fallback);
      if (width < 2000 || height < 1200) {
        errors.push(`${prefix}.fallback must be a 2x desktop capture`);
      }
    } catch (error) {
      errors.push(`${prefix}.fallback ${error.message}`);
    }
  }
  if (content === null) return null;

  requirePattern(errors, content, new RegExp(`<html\\b[^>]*lang=["']${locale}["']`, 'i'), `${prefix} must set lang=${locale}`);
  for (const [pattern, message] of commonPatterns) {
    requirePattern(errors, content, pattern, `${prefix} ${message}`);
  }
  if (forbidden.some((pattern) => pattern.test(content))) {
    errors.push(`${prefix} contains a forbidden external surface`);
  }

  const contract = contracts[document.id];
  for (const marker of contract.markers) {
    requireText(errors, content, marker, `${prefix} must include ${marker}`);
  }
  for (const section of contract.sections) {
    requirePattern(errors, content, new RegExp(`<section\\b[^>]*id=["']${section}["']`, 'i'), `${prefix} must include #${section}`);
  }
  for (const scenario of contract.scenarios) {
    requirePattern(errors, content, new RegExp(`data-scenario=["']${scenario}["']`, 'i'), `${prefix} must include scenario ${scenario}`);
  }
  for (const control of contract.controls) {
    requirePattern(errors, content, new RegExp(`data-control=["']${control}["']`, 'i'), `${prefix} must include control ${control}`);
  }
  for (const candidate of contract.candidates ?? []) {
    requirePattern(errors, content, new RegExp(`data-candidate=["']${candidate}["']`, 'i'), `${prefix} must include candidate ${candidate}`);
  }
  for (const stateField of contract.stateFields ?? []) {
    requirePattern(errors, content, new RegExp(`data-state-field=["']${stateField}["']`, 'i'), `${prefix} must include state field ${stateField}`);
  }
  for (const sourceLink of contract.sourceLinks) {
    requirePattern(errors, content, new RegExp(`href=["'][^"']*${sourceLink.replaceAll('.', '\\.')}[^"']*["']`, 'i'), `${prefix} must link ${sourceLink}`);
  }
  requirePattern(errors, content, new RegExp(`href=["'][^"']*${DESIGN_FILE.replaceAll('.', '\\.')}["']`, 'i'), `${prefix} must link the design`);

  const opposite = path.posix.basename(document.locales[locale === 'en' ? 'ko' : 'en'].html);
  requirePattern(errors, content, new RegExp(`href=["'][^"']*${opposite.replaceAll('.', '\\.')}["']`, 'i'), `${prefix} must link the opposite locale`);
  const sibling = document.id === 'leader-elector' ? 'leader-group-elector' : 'leader-elector';
  requirePattern(errors, content, new RegExp(`href=["'][^"']*${sibling}[^"']*["']`, 'i'), `${prefix} must link the sibling companion`);

  return content;
}

function validateLocaleParity(document, localeContents, errors) {
  const en = localeContents.en;
  const ko = localeContents.ko;
  if (!en || !ko) return;

  const patterns = {
    sections: /<section\b[^>]*id=["']([^"']+)/gi,
    steps: /data-step=["']([^"']+)/gi,
    scenarios: /data-scenario=["']([^"']+)/gi,
    controls: /data-control=["']([^"']+)/gi,
    candidates: /data-candidate=["']([^"']+)/gi,
    stateFields: /data-state-field=["']([^"']+)/gi,
    eventFields: /data-event-field=["']([^"']+)/gi,
  };
  for (const [label, pattern] of Object.entries(patterns)) {
    const enValues = values(en, pattern);
    const koValues = values(ko, pattern);
    if (JSON.stringify(enValues) !== JSON.stringify(koValues)) {
      errors.push(`${document.id} English/Korean ${label} drift`);
    }
  }
  const enCodeCount = (en.match(/<pre\b/g) ?? []).length;
  const koCodeCount = (ko.match(/<pre\b/g) ?? []).length;
  if (enCodeCount !== koCodeCount) errors.push(`${document.id} English/Korean code block drift`);
}

export async function validateRepository(inputRoot = process.cwd()) {
  const root = await realpath(inputRoot);
  const errors = [];
  const manifestBuffer = await readContained(
    root,
    'docs/visual-companions/manifest.json',
    errors,
    'manifest',
  );
  if (manifestBuffer === null) throw new Error(errors.join('\n'));

  let manifest;
  try {
    manifest = JSON.parse(manifestBuffer);
  } catch (error) {
    throw new Error(`manifest is not valid JSON: ${error.message}`);
  }

  if (manifest.schemaVersion !== 1) errors.push('manifest.schemaVersion must be 1');
  if (manifest.repository !== REPOSITORY) errors.push(`manifest.repository must be ${REPOSITORY}`);
  if (manifest.release?.ref !== RELEASE_REF) errors.push(`manifest.release.ref must be ${RELEASE_REF}`);
  if (manifest.release?.commit !== RELEASE_COMMIT) {
    errors.push(`manifest.release.commit must be ${RELEASE_COMMIT}`);
  }
  if (!Array.isArray(manifest.documents) || manifest.documents.length !== 2) {
    errors.push('manifest.documents must contain the two approved documents');
  }

  const ids = new Set();
  let localeFileCount = 0;
  let fallbackCount = 0;
  for (const [index, document] of (manifest.documents ?? []).entries()) {
    const field = `documents[${index}]`;
    if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(document.id ?? '')) {
      errors.push(`${field}.id must be kebab-case`);
    }
    if (ids.has(document.id)) errors.push(`${field}.id is duplicated`);
    ids.add(document.id);
    if (!contracts[document.id]) errors.push(`${field}.id has no validation contract`);
    if (document.status !== 'approved' || document.public !== true) {
      errors.push(`${field} must be approved and public`);
    }
    validatePresentation(document, field, errors);
    await readContained(root, document.source, errors, `${field}.source`);
    for (const [manualIndex, manual] of (document.manuals ?? []).entries()) {
      await readContained(root, manual, errors, `${field}.manuals[${manualIndex}]`);
    }

    const localeContents = {};
    for (const locale of ['en', 'ko']) {
      const before = errors.length;
      localeContents[locale] = await validateLocale(root, document, locale, errors);
      if (localeContents[locale] !== null) localeFileCount += 1;
      if (!errors.slice(before).some((error) => error.startsWith(`${document.id}.${locale}.fallback`))) {
        fallbackCount += 1;
      }
    }
    validateLocaleParity(document, localeContents, errors);
  }

  for (const requiredId of Object.keys(contracts)) {
    if (!ids.has(requiredId)) errors.push(`manifest must include ${requiredId}`);
  }
  if (errors.length > 0) throw new Error(errors.join('\n'));
  return { documentCount: manifest.documents.length, localeFileCount, fallbackCount };
}

const isMain = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  try {
    const result = await validateRepository();
    console.log(
      `Visual companion validation passed: ${result.documentCount} documents / ${result.localeFileCount} locale files / ${result.fallbackCount} fallbacks`,
    );
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
