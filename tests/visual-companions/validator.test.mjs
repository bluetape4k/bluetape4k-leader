import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { mkdir, mkdtemp, readFile, rm, unlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { promisify } from 'node:util';
import test from 'node:test';
import { deflateSync } from 'node:zlib';

import { validateRepository } from '../../scripts/validate-visual-companions.mjs';

const execute = promisify(execFile);
const root = new URL('../../', import.meta.url);
const designBaseline = 'ed346a14bf223b0eb456198fdee617b608795b54';
const releaseCommit = '17ab7f872c1f96318c73d3580729cac20a67e017';

async function validate() {
  return execute('node', ['scripts/validate-visual-companions.mjs'], { cwd: root });
}

function values(content, pattern) {
  return [...content.matchAll(pattern)].map((match) => match[1]).sort();
}

test('approved leader visual companions satisfy the repository contract', async () => {
  const { stdout } = await validate();
  assert.match(stdout, /2 documents \/ 4 locale files \/ 4 fallbacks/);
});

test('LeaderElector English companion exposes the approved deterministic model', async () => {
  const content = await readFile(
    new URL('docs/superpowers/specs/2026-07-30-leader-elector-visual-companion.html', root),
    'utf8',
  );
  assert.deepEqual(
    values(content, /<section\b[^>]*id=["']([^"']+)/gi),
    ['direct-api', 'model', 'recovery', 'settings', 'sources', 'spring'],
  );
  assert.deepEqual(
    [...new Set(values(content, /data-step=["']([^"']+)/gi))],
    ['direct-api', 'model', 'recovery', 'settings', 'spring'],
  );
  assert.deepEqual(
    [...new Set(values(content, /data-scenario=["']([^"']+)/gi))],
    ['contention', 'expiry', 'extension'],
  );
  assert.deepEqual(
    [...new Set(values(content, /data-control=["']([^"']+)/gi))],
    ['action-time', 'lease-time'],
  );
  assert.deepEqual(
    [...new Set(values(content, /data-candidate=["']([^"']+)/gi).filter((value) => !value.includes('$')))],
    ['node-a', 'node-b', 'node-c'],
  );
  assert.deepEqual(
    [...new Set(values(content, /data-event-field=["']([^"']+)/gi))],
    ['candidate', 'operation', 'outcome', 'tick', 'token', 'ttl'],
  );
  for (const marker of [
    'lockName',
    'owner',
    'token',
    'TTL',
    'waitTime',
    'leaseTime',
    'minLeaseTime',
    'autoExtend',
    'runIfLeader',
    'runIfLeaderResult',
    'LeaderRunResult.Elected',
    'LeaderRunResult.Skipped',
    'LeaderRunResult.ActionFailed',
    '@LeaderElection',
    'AspectJ',
    '@EnableAspectJAutoProxy',
    'streamBounded',
    'stale',
    'idempotent',
  ]) {
    assert.match(content, new RegExp(marker));
  }
  assert.doesNotMatch(content, /Math\.random|Date\.now|performance\.now/);
});

test('LeaderElector locale documents expose equivalent structure and behavior', async () => {
  const en = await readFile(
    new URL('docs/superpowers/specs/2026-07-30-leader-elector-visual-companion.html', root),
    'utf8',
  );
  const ko = await readFile(
    new URL('docs/superpowers/specs/2026-07-30-leader-elector-visual-companion.ko.html', root),
    'utf8',
  );
  const parityPatterns = [
    /<section\b[^>]*id=["']([^"']+)/gi,
    /data-step=["']([^"']+)/gi,
    /data-scenario=["']([^"']+)/gi,
    /data-control=["']([^"']+)/gi,
    /data-candidate=["']([^"'$]+)/gi,
    /data-event-field=["']([^"']+)/gi,
  ];
  for (const pattern of parityPatterns) {
    assert.deepEqual(values(en, pattern), values(ko, pattern));
  }
  assert.equal((en.match(/<pre\b/g) ?? []).length, (ko.match(/<pre\b/g) ?? []).length);
  for (const source of [
    'LeaderElector.kt',
    'LeaderElectionOptions.kt',
    'LeaderRunResult.kt',
    'LettuceLeaderElector.kt',
    'LeaderElection.kt',
    'runtime-model.md',
  ]) {
    assert.match(ko, new RegExp(source.replaceAll('.', '\\.')));
  }
  for (const phrase of [
    '정상적인 락 경쟁',
    '소유권 token',
    '리스 만료',
    '오래된 token',
    '멱등',
  ]) {
    assert.match(ko, new RegExp(phrase));
  }
  assert.doesNotMatch(ko, /Math\.random|Date\.now|performance\.now/);
});

test('LeaderGroupElector English companion explains the 1-to-N slot delta', async () => {
  const content = await readFile(
    new URL('docs/superpowers/specs/2026-07-30-leader-group-elector-visual-companion.html', root),
    'utf8',
  );
  assert.deepEqual(
    values(content, /<section\b[^>]*id=["']([^"']+)/gi),
    ['direct-api', 'model', 'recovery', 'settings', 'sources', 'spring'],
  );
  assert.deepEqual(
    [...new Set(values(content, /data-scenario=["']([^"']+)/gi))],
    ['capacity', 'expiry', 'saturation'],
  );
  assert.deepEqual(
    [...new Set(values(content, /data-control=["']([^"']+)/gi))],
    ['action-time', 'candidate-count', 'lease-time', 'max-leaders'],
  );
  assert.deepEqual(
    [...new Set(values(content, /data-state-field=["']([^"']+)/gi))],
    ['activeCount', 'availableSlots', 'isFull'],
  );
  for (const marker of [
    'LeaderElector',
    'LeaderGroupElector',
    '1 → N',
    'LeaderGroupElectionOptions',
    'LeaderGroupState',
    'maxLeaders',
    'activeCount',
    'availableSlots',
    'isFull',
    'runIfLeader',
    '@LeaderGroupElection',
    'Flux',
    'Flow',
    'does not assign unique work',
    'token',
    'TTL',
  ]) {
    assert.match(content, new RegExp(marker));
  }
  assert.doesNotMatch(content, /data-control=["']auto-extend|Math\.random|Date\.now|performance\.now/);
});

test('LeaderGroupElector locale documents expose equivalent structure and behavior', async () => {
  const en = await readFile(
    new URL('docs/superpowers/specs/2026-07-30-leader-group-elector-visual-companion.html', root),
    'utf8',
  );
  const ko = await readFile(
    new URL('docs/superpowers/specs/2026-07-30-leader-group-elector-visual-companion.ko.html', root),
    'utf8',
  );
  const parityPatterns = [
    /<section\b[^>]*id=["']([^"']+)/gi,
    /data-step=["']([^"']+)/gi,
    /data-scenario=["']([^"']+)/gi,
    /data-control=["']([^"']+)/gi,
    /data-state-field=["']([^"']+)/gi,
    /data-event-field=["']([^"']+)/gi,
  ];
  for (const pattern of parityPatterns) {
    assert.deepEqual(values(en, pattern), values(ko, pattern));
  }
  assert.equal((en.match(/<pre\b/g) ?? []).length, (ko.match(/<pre\b/g) ?? []).length);
  for (const source of [
    'LeaderGroupElector.kt',
    'LeaderGroupElectionOptions.kt',
    'LeaderGroupState.kt',
    'LettuceLeaderGroupElector.kt',
    'LettuceSlotTokenGroup.kt',
    'LeaderGroupElection.kt',
    'single-group-strategic.md',
  ]) {
    assert.match(ko, new RegExp(source.replaceAll('.', '\\.')));
  }
  for (const phrase of [
    '1 → N',
    '작업을 고유하게 분배하지',
    '독립적인 slot token',
    '포화',
    '나중 후보',
  ]) {
    assert.match(ko, new RegExp(phrase));
  }
  assert.doesNotMatch(ko, /data-control=["']auto-extend|Math\.random|Date\.now|performance\.now/);
});

function pngHeader(width = 2880, height = 2000) {
  const buffer = Buffer.alloc(24);
  Buffer.from('89504e470d0a1a0a', 'hex').copy(buffer, 0);
  buffer.writeUInt32BE(width, 16);
  buffer.writeUInt32BE(height, 20);
  return buffer;
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

function pngChunk(type, data = Buffer.alloc(0)) {
  const typeBuffer = Buffer.from(type, 'ascii');
  const chunk = Buffer.alloc(12 + data.length);
  chunk.writeUInt32BE(data.length, 0);
  typeBuffer.copy(chunk, 4);
  data.copy(chunk, 8);
  chunk.writeUInt32BE(crc32(Buffer.concat([typeBuffer, data])), 8 + data.length);
  return chunk;
}

function validPng(width = 2880, height = 2000) {
  const signature = Buffer.from('89504e470d0a1a0a', 'hex');
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 1;
  ihdr[9] = 0;
  const scanlineBytes = Math.ceil(width / 8) + 1;
  const imageData = deflateSync(Buffer.alloc(scanlineBytes * height));
  return Buffer.concat([
    signature,
    pngChunk('IHDR', ihdr),
    pngChunk('IDAT', imageData),
    pngChunk('IEND'),
  ]);
}

function fixtureHtml(documentId, locale) {
  const isElector = documentId === 'leader-elector';
  const oppositeSuffix = locale === 'en' ? '.ko.html' : '.html';
  const ownSuffix = locale === 'en' ? '.html' : '.ko.html';
  const sibling = isElector ? 'leader-group-elector' : 'leader-elector';
  const sections = ['model', 'settings', 'direct-api', 'spring', 'recovery', 'sources'];
  const scenarios = isElector ? ['contention', 'expiry', 'extension'] : ['capacity', 'saturation', 'expiry'];
  const controls = isElector
    ? ['action-time', 'lease-time']
    : ['candidate-count', 'max-leaders', 'action-time', 'lease-time'];
  const candidates = isElector ? ['node-a', 'node-b', 'node-c'] : [];
  const stateFields = isElector ? [] : ['activeCount', 'availableSlots', 'isFull'];
  const markers = isElector
    ? [
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
      ]
    : [
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
      ];
  const sourceLinks = isElector
    ? [
        'LeaderElector.kt',
        'LeaderElectionOptions.kt',
        'LeaderRunResult.kt',
        'LettuceLeaderElector.kt',
        'LeaderElection.kt',
        'runtime-model.md',
      ]
    : [
        'LeaderGroupElector.kt',
        'LeaderGroupElectionOptions.kt',
        'LeaderGroupState.kt',
        'LettuceLeaderGroupElector.kt',
        'LettuceSlotTokenGroup.kt',
        'LeaderGroupElection.kt',
        'single-group-strategic.md',
      ];
  return `<!doctype html>
<html lang="${locale}">
<head>
  <meta name="color-scheme" content="light dark">
  <style>
    :root[data-theme="light"] { --surface: white; }
    :root[data-theme="dark"] { --surface: black; }
    @media (prefers-reduced-motion: reduce) { * { animation: none; } }
  </style>
</head>
<body data-design-baseline="${designBaseline}" data-release-ref="0.4.0" data-release-commit="${releaseCommit}">
  <button aria-pressed="true">theme</button>
  ${['model', 'settings', 'direct-api', 'spring', 'recovery'].map((step) => `<button data-step="${step}">${step}</button>`).join('')}
  ${sections.map((section) => `<section id="${section}">${section}</section>`).join('')}
  ${scenarios.map((scenario) => `<button data-scenario="${scenario}">${scenario}</button>`).join('')}
  ${controls.map((control) => `<label data-control="${control}">${control}</label>`).join('')}
  ${candidates.map((candidate) => `<span data-candidate="${candidate}">${candidate}</span>`).join('')}
  ${stateFields.map((field) => `<span data-state-field="${field}">${field}</span>`).join('')}
  <span data-event-field="tick">tick</span>
  <div aria-live="polite">status</div>
  <pre>${markers.join('\n')}</pre>
  ${sourceLinks.map((source) => `<a href="./${source}">${source}</a>`).join('')}
  <a href="./2026-07-30-leader-election-visual-companions-design.md">design</a>
  <a href="./2026-07-30-${documentId}-visual-companion${oppositeSuffix}">locale</a>
  <a href="./2026-07-30-${sibling}-visual-companion${ownSuffix}">sibling</a>
</body>
</html>`;
}

async function writeJson(file, value) {
  await writeFile(file, `${JSON.stringify(value, null, 2)}\n`);
}

async function createFixture(t) {
  const fixtureRoot = await mkdtemp(path.join(tmpdir(), 'leader-visual-'));
  t.after(() => rm(fixtureRoot, { recursive: true, force: true }));
  const design = 'docs/superpowers/specs/2026-07-30-leader-election-visual-companions-design.md';
  const manuals = [
    'docs/manual/en/core/single-group-strategic.md',
    'docs/manual/ko/core/single-group-strategic.md',
    'docs/manual/en/frameworks/spring-boot.md',
    'docs/manual/ko/frameworks/spring-boot.md',
  ];
  const documents = ['leader-elector', 'leader-group-elector'].map((id) => ({
    id,
    source: design,
    status: 'approved',
    public: true,
    presentation: { mode: 'simulation', defaultView: 'simulation', views: ['simulation'] },
    manuals,
    locales: Object.fromEntries(['en', 'ko'].map((locale) => {
      const localeSuffix = locale === 'en' ? '' : '.ko';
      const html = `docs/superpowers/specs/2026-07-30-${id}-visual-companion${localeSuffix}.html`;
      return [locale, {
        title: `${id}-${locale}`,
        html,
        route: `${locale === 'ko' ? '/ko' : ''}/visual-companions/bluetape4k-leader/${id}/`,
        fallback: `docs/manual/assets/visual-companions/${id}.${locale}.png`,
      }];
    })),
  }));
  const manifest = {
    schemaVersion: 1,
    repository: 'bluetape4k/bluetape4k-leader',
    release: { ref: '0.4.0', commit: releaseCommit },
    documents,
  };

  for (const directory of [
    'docs/visual-companions',
    'docs/superpowers/specs',
    'docs/manual/en/core',
    'docs/manual/ko/core',
    'docs/manual/en/frameworks',
    'docs/manual/ko/frameworks',
    'docs/manual/assets/visual-companions',
  ]) {
    await mkdir(path.join(fixtureRoot, directory), { recursive: true });
  }
  await writeFile(path.join(fixtureRoot, design), '# fixture design\n');
  for (const manual of manuals) await writeFile(path.join(fixtureRoot, manual), '# fixture manual\n');
  for (const document of documents) {
    for (const locale of ['en', 'ko']) {
      await writeFile(
        path.join(fixtureRoot, document.locales[locale].html),
        fixtureHtml(document.id, locale),
      );
      await writeFile(
        path.join(fixtureRoot, document.locales[locale].fallback),
        validPng(),
      );
    }
  }
  await writeJson(path.join(fixtureRoot, 'docs/visual-companions/manifest.json'), manifest);
  return { fixtureRoot, manifest };
}

test('validator rejects duplicated document IDs', async (t) => {
  const { fixtureRoot, manifest } = await createFixture(t);
  manifest.documents[1].id = 'leader-elector';
  await writeJson(path.join(fixtureRoot, 'docs/visual-companions/manifest.json'), manifest);
  await assert.rejects(validateRepository(fixtureRoot), /id is duplicated/);
});

test('validator rejects a missing Korean locale file', async (t) => {
  const { fixtureRoot, manifest } = await createFixture(t);
  await unlink(path.join(fixtureRoot, manifest.documents[0].locales.ko.html));
  await assert.rejects(validateRepository(fixtureRoot), /leader-elector\.ko\.html does not exist/);
});

test('validator rejects repository path traversal', async (t) => {
  const { fixtureRoot, manifest } = await createFixture(t);
  manifest.documents[0].source = '../outside.md';
  await writeJson(path.join(fixtureRoot, 'docs/visual-companions/manifest.json'), manifest);
  await assert.rejects(validateRepository(fixtureRoot), /path escapes repository/);
});

test('validator rejects external scripts', async (t) => {
  const { fixtureRoot, manifest } = await createFixture(t);
  const htmlPath = path.join(fixtureRoot, manifest.documents[0].locales.en.html);
  const html = await readFile(htmlPath, 'utf8');
  await writeFile(htmlPath, html.replace('</head>', '<script src="https://example.com/a.js"></script></head>'));
  await assert.rejects(validateRepository(fixtureRoot), /forbidden external surface/);
});

test('validator rejects a missing design baseline', async (t) => {
  const { fixtureRoot, manifest } = await createFixture(t);
  const htmlPath = path.join(fixtureRoot, manifest.documents[0].locales.en.html);
  const html = await readFile(htmlPath, 'utf8');
  await writeFile(htmlPath, html.replace(` data-design-baseline="${designBaseline}"`, ''));
  await assert.rejects(validateRepository(fixtureRoot), /must pin the design baseline/);
});

test('validator rejects a missing scenario marker', async (t) => {
  const { fixtureRoot, manifest } = await createFixture(t);
  const htmlPath = path.join(fixtureRoot, manifest.documents[1].locales.en.html);
  const html = await readFile(htmlPath, 'utf8');
  await writeFile(htmlPath, html.replace(' data-scenario="saturation"', ''));
  await assert.rejects(validateRepository(fixtureRoot), /must include scenario saturation/);
});

test('validator rejects English and Korean structure drift', async (t) => {
  const { fixtureRoot, manifest } = await createFixture(t);
  const htmlPath = path.join(fixtureRoot, manifest.documents[0].locales.ko.html);
  const html = await readFile(htmlPath, 'utf8');
  await writeFile(htmlPath, html.replace('</body>', '<section id="locale-only">drift</section></body>'));
  await assert.rejects(validateRepository(fixtureRoot), /English\/Korean sections drift/);
});

test('validator rejects a missing fallback PNG', async (t) => {
  const { fixtureRoot, manifest } = await createFixture(t);
  await unlink(path.join(fixtureRoot, manifest.documents[1].locales.ko.fallback));
  await assert.rejects(validateRepository(fixtureRoot), /leader-group-elector\.ko\.fallback does not exist/);
});

test('validator rejects a truncated fallback PNG with only a signature and dimensions', async (t) => {
  const { fixtureRoot, manifest } = await createFixture(t);
  const fallback = path.join(fixtureRoot, manifest.documents[0].locales.en.fallback);
  await writeFile(fallback, pngHeader());
  await assert.rejects(validateRepository(fixtureRoot), /must contain a complete PNG structure/);
});

test('Korean LeaderGroupElector models a stale release after lease expiry', async () => {
  const content = await readFile(
    new URL('docs/superpowers/specs/2026-07-30-leader-group-elector-visual-companion.ko.html', root),
    'utf8',
  );
  assert.match(content, /candidate\.actionEndsAt===state\.tick/);
  assert.match(content, /\['running','stale'\]\.includes\(candidate\.status\)/);
  assert.match(content, /거부: 오래된 token/);
  assert.match(content, /attemptAt:preset\.attempts\[index\]\?\?index/);
  assert.match(content, /state\.tick>=candidate\.deadline/);
  assert.match(content, /ttl:ttl\?\?'—'/);
  assert.match(content, /!state\.candidates\.some\(candidate=>\['queued','waiting','running'\]\.includes\(candidate\.status\)\)/);
});
