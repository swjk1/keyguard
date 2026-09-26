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

  // --- The supervision loop, end to end. ---
  //
  // Everything above this line was reachable by a parent alone, and it is what the smoke test
  // used to cover. The half that actually carries the product - a child joining, learning its
  // policy, reporting a warning, and that warning arriving on the parent's screen - had never
  // run against a live server at all, which meant the first time it ran was going to be on a
  // tester's phone with nobody watching the logs. Redis-backed behaviour cannot be unit
  // tested, so this is the only place it gets exercised before a real family does it.

  const childId = randomUUID();
  const childInstall = await request('/api/register', {
    method: 'POST',
    body: JSON.stringify({ installId: childId }),
  });
  assert.equal(childInstall.response.status, 200);
  // Install tokens ride their own header, not `Authorization` - that one carries the parent
  // session, and the two authorize completely different callers on the same routes.
  const childAuth = { 'x-install-token': childInstall.body.token };

  // A device that has not joined must be told it is unsupervised rather than refused. The
  // client keys its whole "am I still in a family" logic off this reply, and a 404 here would
  // be indistinguishable from a bad deploy.
  const before = await request('/api/family/policy', { headers: childAuth });
  assert.equal(before.response.status, 200);
  assert.equal(before.body.supervised, false);

  const join = await request('/api/family/join', {
    method: 'POST',
    headers: childAuth,
    body: JSON.stringify({ code: pairing.body.code, label: 'Smoke phone' }),
  });
  assert.equal(join.response.status, 200, JSON.stringify(join.body));
  assert.equal(join.body.familyId, family.body.familyId);
  assert.ok(join.body.policy, 'join must return the policy the device is to enforce');

  // Single use. The second redemption of a spent code must fail, or a code read aloud once is
  // a standing invitation into the family.
  const replay = await request('/api/family/join', {
    method: 'POST',
    headers: childAuth,
    body: JSON.stringify({ code: pairing.body.code, label: 'Impostor' }),
  });
  assert.equal(replay.response.status, 404, 'a spent pairing code was accepted twice');

  const policy = await request('/api/family/policy', { headers: childAuth });
  assert.equal(policy.response.status, 200);
  assert.equal(policy.body.supervised, true);
  assert.equal(typeof policy.body.policy.version, 'number');

  const eventAt = Date.now();
  const events = await request('/api/family/events', {
    method: 'POST',
    headers: childAuth,
    body: JSON.stringify({
      events: [
        {
          at: eventAt,
          category: 'IN_PERSON_MEETUP',
          severity: 3,
          outcome: 'ABANDONED_DELETED',
          heeded: true,
        },
      ],
    }),
  });
  assert.equal(events.response.status, 200, JSON.stringify(events.body));
  assert.equal(events.body.supervised, true);
  assert.equal(events.body.received, 1);

  // The schema promise, checked against the deployed build rather than the source. If a
  // snippet field ever becomes storable, the data-safety declaration and the child-facing
  // disclosure both become false, and this is the assertion that says so first.
  const withText = await request('/api/family/events', {
    method: 'POST',
    headers: childAuth,
    body: JSON.stringify({
      events: [
        { at: eventAt, category: 'HARASSMENT', severity: 2, outcome: 'SENT', heeded: false, text: 'should be refused' },
      ],
    }),
  });
  assert.equal(withText.response.status, 400, 'the events route accepted message text');

  // Samples are refused outright at the default scope. A device that queued them under a
  // wider scope has to be told to drop the queue rather than retry it forever.
  const samples = await request('/api/family/samples', {
    method: 'POST',
    headers: childAuth,
    body: JSON.stringify({
      samples: [{ at: eventAt, themes: [], emotions: [], chars: 12, flagged: false, outcome: 'SENT' }],
    }),
  });
  assert.equal(samples.response.status, 403, JSON.stringify(samples.body));
  assert.equal(samples.body.error, 'scope_withdrawn');

  // The parent's end of the loop, which is what the dashboard and the alert poll both read.
  const overview = await request('/api/family/activity', { headers: auth });
  assert.equal(overview.response.status, 200);
  const seen = overview.body.children.find((c) => c.installId === childId);
  assert.ok(seen, 'the paired child did not appear in the parent overview');
  assert.equal(seen.label, 'Smoke phone');
  // One, not two: the event carrying text was refused above, so it must not have been
  // partially stored. This is the assertion that proves the 400 was a real rejection rather
  // than a late validation error after a write.
  assert.equal(seen.events.length, 1);
  assert.equal(seen.events[0].category, 'IN_PERSON_MEETUP');
  // What ParentAlerts keys its watermark off. A missing or zero receipt stamp would make every
  // poll either re-alert or never alert.
  assert.ok(seen.events.every((e) => typeof e.receivedAt === 'number' && e.receivedAt > 0));
  assert.ok(seen.events.every((e) => !('text' in e)), 'a stored event carried text');

  // Unpair, and check both ends learn it: the child is told it is unsupervised, and the
  // parent's overview no longer lists it.
  const removed = await request('/api/family/child', {
    method: 'DELETE',
    headers: auth,
    body: JSON.stringify({ installId: childId }),
  });
  assert.equal(removed.response.status, 200, JSON.stringify(removed.body));

  const after = await request('/api/family/policy', { headers: childAuth });
  assert.equal(after.response.status, 200);
  assert.equal(after.body.supervised, false, 'an unpaired device was still told it is supervised');

  const emptied = await request('/api/family/activity', { headers: auth });
  assert.equal(emptied.body.children.find((c) => c.installId === childId), undefined);
} finally {
  if (parentToken) {
    await request('/api/auth/account', {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${parentToken}` },
    });
  }
}

process.stdout.write(`Smoke test passed for ${baseUrl}\n`);
