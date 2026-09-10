import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';

const baseUrl = (process.argv[2] ?? process.env.KEYGUARD_SMOKE_URL ?? '').replace(/\/$/, '');
if (!baseUrl.startsWith('https://') && !baseUrl.startsWith('http://localhost')) {
  throw new Error('Pass an HTTPS deployment URL (or localhost) as the first argument.');
}

async function request(path, init = {}) {
  const response = await fetch(baseUrl + path, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init.headers },
  });
  const text = await response.text();
  const body = text ? JSON.parse(text) : null;
  return { response, body };
}

const health = await request('/api/health');
assert.equal(health.response.status, 200, `health failed: ${JSON.stringify(health.body)}`);
assert.equal(health.body.ok, true);

const rules = await request('/api/rules?since=0');
assert.equal(rules.response.status, 200);
assert.equal(typeof rules.body.version, 'number');

const installId = randomUUID();
const install = await request('/api/register', {
  method: 'POST',
  body: JSON.stringify({ installId }),
});
assert.equal(install.response.status, 200);
assert.match(install.body.token, new RegExp(`^${installId.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\.`));

const email = `smoke-${randomUUID()}@example.invalid`;
const password = `Smoke-${randomUUID()}!`;
let parentToken;
try {
  const registration = await request('/api/auth/register', {
    method: 'POST',
    body: JSON.stringify({ email, password }),
  });
  assert.equal(registration.response.status, 201, JSON.stringify(registration.body));
  assert.equal(typeof registration.body.recoveryCode, 'string');
  parentToken = registration.body.token;

  const auth = { Authorization: `Bearer ${parentToken}` };
  const family = await request('/api/family/create', { method: 'POST', headers: auth });
  assert.equal(family.response.status, 200, JSON.stringify(family.body));
  assert.equal(typeof family.body.familyId, 'string');

  const pairing = await request('/api/family/code', { method: 'POST', headers: auth });
  assert.equal(pairing.response.status, 200, JSON.stringify(pairing.body));
  assert.match(pairing.body.code, /^[0-9A-HJKMNP-TV-Z]{8}$/);
} finally {
  if (parentToken) {
    await request('/api/auth/account', {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${parentToken}` },
    });
  }
}

process.stdout.write(`Smoke test passed for ${baseUrl}\n`);
