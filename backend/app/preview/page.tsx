import fs from 'node:fs';
import path from 'node:path';

import { Harness } from './Harness';
import type { RulePack, TermRule, SuppressorRule, CoOccurrenceRule } from './detect';

/**
 * The design harness.
 *
 * A server component purely so it can read the real rule pack off disk. Reading it rather than
 * importing it keeps the harness honest in the way that matters: there is one pack, the Kotlin
 * engine and the browser read the same file, and retuning a severity shows up here on the next
 * reload without anything being copied.
 *
 * `force-dynamic` because the pack is the thing being iterated on — a cached render would show
 * yesterday's severities and quietly waste an afternoon.
 */
export const dynamic = 'force-dynamic';

interface RawPack {
  terms: TermRule[];
  suppressors: SuppressorRule[];
  co_occurrence: CoOccurrenceRule[];
}

function loadPack(): RulePack {
  // The Next root is `backend/`, and the pack lives in the sibling `detect` module.
  const packPath = path.resolve(
    process.cwd(),
    '..',
    'detect',
    'src',
    'main',
    'resources',
    'rules',
    'pack-v0.json',
  );
  const raw = JSON.parse(fs.readFileSync(packPath, 'utf8')) as RawPack;

  // Only the harm rules cross to the client. The theme and emotion vocabularies are another 449
  // entries that nothing in the browser reads, and shipping them would double the payload of
  // every reload for no benefit.
  return {
    terms: raw.terms,
    suppressors: raw.suppressors,
    co_occurrence: raw.co_occurrence,
  };
}

export default function PreviewPage() {
  return <Harness pack={loadPack()} />;
}
