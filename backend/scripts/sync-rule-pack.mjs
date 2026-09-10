/**
 * Copies the detection rule pack into the backend so it can be bundled.
 *
 * `app/api/rules/route.ts` used to import it straight from
 * `../../../../detect/src/main/resources/rules/pack-v0.json`. That works locally and fails on
 * Vercel, which uploads only this directory — the build died with "module not found" on a path
 * that reaches outside the project root.
 *
 * The fix keeps the original intent (one pack, served and bundled from the same bytes) but
 * makes the dependency explicit: the pack is copied in at build time and `tests/rule-pack.test.ts`
 * fails if the committed copy ever differs from the source. Drift becomes a red test rather
 * than something nobody notices.
 *
 * Missing source is not an error. On Vercel the `detect/` module is not uploaded, so this is a
 * no-op there and the committed copy is what ships — which is the whole point.
 */
import { copyFileSync, existsSync, mkdirSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
export const SOURCE = resolve(here, '..', '..', 'detect', 'src', 'main', 'resources', 'rules', 'pack-v0.json');
export const TARGET = resolve(here, '..', 'lib', 'rule-pack.json');

if (!existsSync(SOURCE)) {
  console.log(`[rule-pack] source not present (${SOURCE}) — keeping the committed copy.`);
  process.exit(0);
}

mkdirSync(dirname(TARGET), { recursive: true });
const before = existsSync(TARGET) ? readFileSync(TARGET, 'utf8') : null;
copyFileSync(SOURCE, TARGET);
const after = readFileSync(TARGET, 'utf8');

const pack = JSON.parse(after);
console.log(
  before === after
    ? `[rule-pack] up to date (v${pack.version} ${pack.revision}, ${pack.terms.length} terms)`
    : `[rule-pack] UPDATED from detect/ (v${pack.version} ${pack.revision}, ${pack.terms.length} terms) — commit lib/rule-pack.json`,
);
