import assert from 'node:assert/strict';
import test from 'node:test';

import { EventSchema, isPairingCode, mintPairingCode } from '../lib/family-contract.ts';

test('pairing codes use eight unambiguous Crockford characters', () => {
  const codes = new Set(Array.from({ length: 100 }, mintPairingCode));
  assert.equal(codes.size, 100);
  for (const code of codes) {
    assert.equal(isPairingCode(code), true);
    assert.match(code, /^[0-9A-HJKMNP-TV-Z]{8}$/);
  }
});

test('event ingestion accepts metadata without message text', () => {
  const parsed = EventSchema.strict().safeParse({
    at: Date.now(),
    category: 'PII_DISCLOSURE',
    severity: 3,
    outcome: 'ABANDONED_DELETED',
    heeded: true,
  });
  assert.equal(parsed.success, true);
});

test('event ingestion rejects message text and host app fields', () => {
  for (const extra of [{ text: 'secret' }, { app: 'chat.example' }, { recipient: 'someone' }]) {
    const parsed = EventSchema.strict().safeParse({
      at: Date.now(),
      category: 'HARASSMENT',
      severity: 2,
      outcome: 'SENT',
      heeded: false,
      ...extra,
    });
    assert.equal(parsed.success, false);
  }
});
