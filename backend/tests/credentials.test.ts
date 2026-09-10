import assert from 'node:assert/strict';
import test from 'node:test';

import {
  ParentCredentialsSchema,
  generateRecoveryCode,
  hashPassword,
  hashRecoveryCode,
  normalizeEmail,
  verifyPassword,
} from '../lib/credentials.ts';

test('email normalization is stable and case insensitive', () => {
  assert.equal(normalizeEmail('  Parent@Example.COM '), 'parent@example.com');
});

test('parent credentials require an email and a 12 character password', () => {
  assert.equal(ParentCredentialsSchema.safeParse({ email: 'parent@example.com', password: 'long-password' }).success, true);
  assert.equal(ParentCredentialsSchema.safeParse({ email: 'not-an-email', password: 'long-password' }).success, false);
  assert.equal(ParentCredentialsSchema.safeParse({ email: 'parent@example.com', password: 'short' }).success, false);
});

test('password hashes are salted and verify without retaining the password', async () => {
  const first = await hashPassword('a genuinely long password');
  const second = await hashPassword('a genuinely long password');
  assert.notEqual(first, second);
  assert.equal(first.includes('a genuinely long password'), false);
  assert.equal(await verifyPassword('a genuinely long password', first), true);
  assert.equal(await verifyPassword('the wrong password', first), false);
  assert.equal(await verifyPassword('anything', 'broken'), false);
});

test('recovery codes are random and normalized before hashing', () => {
  const first = generateRecoveryCode();
  const second = generateRecoveryCode();
  assert.notEqual(first, second);
  assert.match(first, /^[A-Z0-9_-]{20,40}$/);
  assert.equal(hashRecoveryCode(` ${first.toLowerCase()} `), hashRecoveryCode(first));
});
