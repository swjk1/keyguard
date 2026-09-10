import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// Recomputed rather than imported from `scripts/sync-rule-pack.mjs`: importing an untyped
// .mjs into a typechecked test trips TS7016, and the two paths are one line each.
const here = dirname(fileURLToPath(import.meta.url));
const SOURCE = resolve(here, '..', '..', 'detect', 'src', 'main', 'resources', 'rules', 'pack-v0.json');
const TARGET = resolve(here, '..', 'lib', 'rule-pack.json');

/**
 * The vendored pack must be byte-identical to the one the Android module bundles.
 *
 * `app/api/rules/route.ts` serves this file so a device can pull a corrected rule without an
 * app update, and the whole value of that depends on the served pack being the same pack.
 * Vendoring it into `backend/` was forced by Vercel uploading only this directory; this test
 * is what keeps the copy honest, so a stale copy is a red build rather than a device quietly
 * enforcing last month's severities.
 *
 * Skipped where `detect/` is not present — that is the deployment environment, which has the
 * committed copy and nothing to compare it against.
 */
test('the vendored rule pack matches detect/', { skip: !existsSync(SOURCE) }, () => {
  assert.equal(
    readFileSync(TARGET, 'utf8'),
    readFileSync(SOURCE, 'utf8'),
    'lib/rule-pack.json is stale — run `node scripts/sync-rule-pack.mjs` and commit the result',
  );
});

test('the vendored pack is structurally what the route expects', () => {
  const pack = JSON.parse(readFileSync(TARGET, 'utf8'));
  assert.ok(Number.isInteger(pack.version), 'version must be an integer; /api/rules compares it to ?since=');
  assert.ok(Array.isArray(pack.terms) && pack.terms.length > 0);
  assert.ok(Array.isArray(pack.suppressors));
});
