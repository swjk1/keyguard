import { randomUUID } from 'node:crypto';
import { z } from 'zod';

import {
  EVENT_HISTORY_LIMIT,
  EVENT_TTL_SECONDS,
  PAIRING_CODE_TTL_SECONDS,
  SAMPLE_HISTORY_LIMIT,
  SAMPLE_TTL_SECONDS,
  keys,
  store,
} from './store';
import {
  EventSchema,
  PolicySchema,
  SampleSchema,
  isPairingCode,
  mintPairingCode,
  scopePermitsText,
  type ActivitySample,
  type Policy,
  type ReviewScope,
  type SupervisionEvent,
} from './family-contract';

export {
  EventSchema,
  PolicySchema,
  REPORT_PERIODS,
  SampleSchema,
  isPairingCode,
  mintPairingCode,
  scopePermitsSamples,
  scopePermitsText,
} from './family-contract';
export type {
  ActivitySample,
  Policy,
  ReportPeriod,
  ReviewScope,
  SupervisionEvent,
} from './family-contract';

/**
 * Families, pairing, policy, and the activity feed.
 *
 * The whole shape of this file follows from one decision made on the device side: a
 * supervision event carries a category, a severity and an outcome, and **no text**. There is
 * therefore nothing here that redacts, truncates or hashes a message, because no message ever
 * arrives. If that changes, this file is where the change stops being cheap.
 */

/**
 * Matches `FamilyPolicy.DEFAULT` on the device, including the deliberately absent floor:
 * pairing on its own must not change how anyone's keyboard behaves.
 */
export const DEFAULT_POLICY: Policy = {
  version: 0,
  minIntensity: 'SUBTLE',
  blockAtHigh: true,
  aiVerification: 'CHILD_CHOICE',
  lockSettings: false,
  // Both match the device's own defaults, and both are the pre-supervision behaviour: FULL is
  // what the Ignore button has always done, and CONCERNING_ONLY is the no-text promise the
  // product shipped with. Pairing changes neither.
  overrideLevel: 'FULL',
  reviewScope: 'CONCERNING_ONLY',
  reportsEnabled: true,
};

/** The device's half of the wire format. `receivedAt` is added here. */
const StoredEventSchema = EventSchema.extend({ receivedAt: z.number().int() });
export type StoredEvent = z.infer<typeof StoredEventSchema>;

const StoredSampleSchema = SampleSchema.extend({ receivedAt: z.number().int() });
export type StoredSample = z.infer<typeof StoredSampleSchema>;

const MembershipSchema = z.object({
  label: z.string(),
  pairedAt: z.number().int(),
  lastSeen: z.number().int(),
});

export type Membership = z.infer<typeof MembershipSchema>;

export interface Child extends Membership {
  installId: string;
}

/**
 * A pairing code, from a CSPRNG rather than `Math.random`.
 *
 * Rejection sampling on the byte rather than a modulo, so every character is equally likely.
 * 32^8 is about 1.1e12 codes alive for ten minutes each, which is what makes guessing one a
 * non-event even before the per-install attempt limit.
 */
export type ParentRole = 'OWNER' | 'CAREGIVER';

/** The family this parent account belongs to, creating one as owner on first call. */
export async function ensureFamily(parentAccountId: string): Promise<string | null> {
  const redis = store();
  if (!redis) return null;

  const existing = await redis.get<string>(keys.ownerFamily(parentAccountId));
  if (existing) return existing;

  const familyId = randomUUID();
  // NX so two parallel calls from the same install cannot produce two families and orphan
  // whichever one the child then joins.
  const claimed = await redis.set(keys.ownerFamily(parentAccountId), familyId, { nx: true });
  if (!claimed) return redis.get<string>(keys.ownerFamily(parentAccountId));

  await redis.hset(keys.family(familyId), {
    ownerAccountId: parentAccountId,
    createdAt: Date.now(),
  });
  await redis.hset(keys.parents(familyId), { [parentAccountId]: 'OWNER' });
  await redis.set(keys.policy(familyId), DEFAULT_POLICY);
  return familyId;
}

/** The family a parent account may administer, without creating one. */
export async function familyOwnedBy(parentAccountId: string): Promise<string | null> {
  const redis = store();
  if (!redis) return null;
  return redis.get<string>(keys.ownerFamily(parentAccountId));
}

/** The family a child install belongs to, or null once a parent has removed it. */
export async function familyOfChild(childInstallId: string): Promise<string | null> {
  const redis = store();
  if (!redis) return null;
  return redis.get<string>(keys.childFamily(childInstallId));
}

export async function issuePairingCode(familyId: string): Promise<{ code: string; expiresAt: number }> {
  const redis = store()!;
  const code = mintPairingCode();
  await redis.set(keys.pairingCode(code), familyId, { ex: PAIRING_CODE_TTL_SECONDS });
  return { code, expiresAt: Date.now() + PAIRING_CODE_TTL_SECONDS * 1000 };
}

/**
 * Spends a pairing code and binds the child.
 *
 * `getdel` makes the code single-use atomically: two devices racing on the same code cannot
 * both join, and a code read over someone's shoulder is worthless the moment it is used.
 */
export async function redeemPairingCode(
  code: string,
  childInstallId: string,
  label: string,
): Promise<string | null> {
  const redis = store()!;
  const familyId = await redis.getdel<string>(keys.pairingCode(code));
  if (!familyId || !(await redis.exists(keys.family(familyId)))) return null;

  const now = Date.now();
  const membership: Membership = { label, pairedAt: now, lastSeen: now };
  await redis.hset(keys.children(familyId), { [childInstallId]: membership });
  await redis.set(keys.childFamily(childInstallId), familyId);
  return familyId;
}

/** A parent-to-parent invite uses the same short lifetime but a separate keyspace. */
export async function issueParentInvite(familyId: string): Promise<{ code: string; expiresAt: number }> {
  const redis = store()!;
  const code = mintPairingCode();
  await redis.set(keys.parentInvite(code), familyId, { ex: PAIRING_CODE_TTL_SECONDS });
  return { code, expiresAt: Date.now() + PAIRING_CODE_TTL_SECONDS * 1000 };
}

export async function redeemParentInvite(code: string, accountId: string): Promise<string | null> {
  const redis = store()!;
  const familyId = await redis.getdel<string>(keys.parentInvite(code));
  if (!familyId || !(await redis.exists(keys.family(familyId)))) return null;
  const existing = await redis.get<string>(keys.ownerFamily(accountId));
  if (existing && existing !== familyId) return null;
  await redis.set(keys.ownerFamily(accountId), familyId);
  await redis.hset(keys.parents(familyId), { [accountId]: 'CAREGIVER' });
  return familyId;
}

export async function readPolicy(familyId: string): Promise<Policy> {
  const redis = store()!;
  const raw = await redis.get(keys.policy(familyId));
  const parsed = PolicySchema.safeParse(raw);
  // A policy that will not parse falls back to the permissive default rather than the
  // strictest reading, for the same reason the device does: a restriction nobody chose is
  // discovered by a child locked out of their own keyboard.
  return parsed.success ? parsed.data : DEFAULT_POLICY;
}

/** Writes a policy under a freshly incremented version, so devices can poll on the number. */
export async function writePolicy(
  familyId: string,
  policy: Omit<Policy, 'version'>,
): Promise<Policy> {
  const redis = store()!;
  const current = await readPolicy(familyId);
  const next: Policy = { ...policy, version: current.version + 1 };
  await redis.set(keys.policy(familyId), next);
  return next;
}

export async function touchChild(familyId: string, childInstallId: string): Promise<void> {
  const redis = store()!;
  const raw = await redis.hget(keys.children(familyId), childInstallId);
  const parsed = MembershipSchema.safeParse(raw);
  if (!parsed.success) return;
  await redis.hset(keys.children(familyId), {
    [childInstallId]: { ...parsed.data, lastSeen: Date.now() },
  });
}

export async function listChildren(familyId: string): Promise<Child[]> {
  const redis = store()!;
  const raw = await redis.hgetall<Record<string, unknown>>(keys.children(familyId));
  if (!raw) return [];

  return Object.entries(raw).flatMap(([installId, value]) => {
    const parsed = MembershipSchema.safeParse(value);
    return parsed.success ? [{ installId, ...parsed.data }] : [];
  });
}

/**
 * Appends events, skipping ones already stored.
 *
 * The device confirms a batch only after the server accepts it, so a crash in that window
 * re-sends. Dedup compares against the newest slice rather than keeping a separate seen-set:
 * a duplicate can only ever be a recent one, and a stray key per event is not worth it.
 */
export async function appendEvents(
  familyId: string,
  childInstallId: string,
  events: SupervisionEvent[],
): Promise<number> {
  const redis = store()!;
  const key = keys.events(familyId, childInstallId);
  const receivedAt = Date.now();

  const recent = await readEvents(familyId, childInstallId, events.length * 2);
  const seen = new Set(recent.map(fingerprint));
  const fresh = events.filter((event) => !seen.has(fingerprint(event)));
  if (fresh.length === 0) return 0;

  const stored: StoredEvent[] = fresh.map((event) => ({ ...event, receivedAt }));
  // Newest first, so the parent view's slice is the front of the list.
  await redis.lpush(key, ...stored.reverse());
  await redis.ltrim(key, 0, EVENT_HISTORY_LIMIT - 1);
  await redis.expire(key, EVENT_TTL_SECONDS);
  return fresh.length;
}

export async function readEvents(
  familyId: string,
  childInstallId: string,
  limit: number,
): Promise<StoredEvent[]> {
  const redis = store()!;
  if (limit <= 0) return [];
  const raw = await redis.lrange(keys.events(familyId, childInstallId), 0, limit - 1);
  return raw.flatMap((value) => {
    const parsed = StoredEventSchema.safeParse(value);
    return parsed.success ? [parsed.data] : [];
  });
}

/**
 * Appends activity samples.
 *
 * Deliberately does **not** dedupe the way `appendEvents` does. Two identical events a second
 * apart are almost certainly one warning delivered twice; two identical samples are two
 * messages, and a child who sends "ok" twice has sent it twice. Dropping the second would
 * quietly understate exactly the volume signal a report is counting.
 *
 * `stripText` is applied here rather than trusted from the caller, so the family's stored scope
 * is what decides whether a message body is written - not the shape of what arrived.
 */
export async function appendSamples(
  familyId: string,
  childInstallId: string,
  samples: ActivitySample[],
  scope: ReviewScope,
): Promise<number> {
  const redis = store()!;
  const key = keys.samples(familyId, childInstallId);
  const receivedAt = Date.now();
  const keepText = scopePermitsText(scope);

  const stored: StoredSample[] = samples.map((sample) => ({
    ...sample,
    // The one line that decides whether a child's words are written to disk. A sample arriving
    // with text under a scope that does not permit it is stored without the text rather than
    // rejected: the derived half is still legitimate, and refusing the batch would make a
    // client bug look like a network failure and retry forever.
    text: keepText ? sample.text : undefined,
    receivedAt,
  }));

  if (stored.length === 0) return 0;
  await redis.lpush(key, ...stored.slice().reverse());
  await redis.ltrim(key, 0, SAMPLE_HISTORY_LIMIT - 1);
  await redis.expire(key, SAMPLE_TTL_SECONDS);
  return stored.length;
}

export async function readSamples(
  familyId: string,
  childInstallId: string,
  limit: number,
): Promise<StoredSample[]> {
  const redis = store()!;
  if (limit <= 0) return [];
  const raw = await redis.lrange(keys.samples(familyId, childInstallId), 0, limit - 1);
  return raw.flatMap((value) => {
    const parsed = StoredSampleSchema.safeParse(value);
    return parsed.success ? [parsed.data] : [];
  });
}

/**
 * Deletes everything collected under a scope a family has moved away from.
 *
 * Called when a parent narrows the review scope, and it is the half of that change that
 * actually matters. Storing a narrower scope going forward while keeping a fortnight of a
 * child's messages already on the server would mean "turn it off" did not turn it off - the
 * parent would still be able to read every message sent before they changed their mind.
 *
 * Narrowing to CONCERNING_ONLY drops the samples entirely. Narrowing from FULL_TEXT to THEMES
 * keeps the derived half and strips the text, since the tags were always within what THEMES
 * permits and discarding them would throw away a report's history for no privacy gain.
 */
export async function applyScopeNarrowing(
  familyId: string,
  previous: ReviewScope,
  next: ReviewScope,
): Promise<void> {
  const redis = store()!;
  const depth = (scope: ReviewScope) =>
    scope === 'CONCERNING_ONLY' ? 0 : scope === 'THEMES' ? 1 : 2;
  if (depth(next) >= depth(previous)) return;

  const children = await listChildren(familyId);
  for (const child of children) {
    const key = keys.samples(familyId, child.installId);
    if (next === 'CONCERNING_ONLY') {
      await redis.del(key);
      continue;
    }
    // FULL_TEXT -> THEMES. Rewrite in place with the bodies removed.
    const existing = await readSamples(familyId, child.installId, SAMPLE_HISTORY_LIMIT);
    await redis.del(key);
    const scrubbed = existing.map(({ text, ...rest }) => rest);
    if (scrubbed.length === 0) continue;
    await redis.lpush(key, ...scrubbed.slice().reverse());
    await redis.expire(key, SAMPLE_TTL_SECONDS);
  }
}

/** Removes a child from a family, and with it the reason its device stays supervised. */
export async function removeChild(familyId: string, childInstallId: string): Promise<void> {
  const redis = store()!;
  await redis.hdel(keys.children(familyId), childInstallId);
  await redis.del(keys.childFamily(childInstallId));
  await redis.del(keys.events(familyId, childInstallId));
  // Samples go with the child, like the event history does. A parent who unpaired has ended
  // the arrangement; keeping a fortnight of their messages afterwards serves nobody.
  await redis.del(keys.samples(familyId, childInstallId));
}

/**
 * Removes a parent account from family state. A caregiver leaves; deleting the owner removes
 * the family, child memberships, event history, policy, and every caregiver mapping.
 */
export async function removeParentAccountFamily(accountId: string): Promise<void> {
  const redis = store()!;
  const familyId = await familyOwnedBy(accountId);
  if (!familyId) return;
  const role = await redis.hget<ParentRole>(keys.parents(familyId), accountId);
  if (role !== 'OWNER') {
    await redis.hdel(keys.parents(familyId), accountId);
    await redis.del(keys.ownerFamily(accountId));
    return;
  }

  const children = await listChildren(familyId);
  for (const child of children) await removeChild(familyId, child.installId);

  const parents = await redis.hgetall<Record<string, ParentRole>>(keys.parents(familyId));
  for (const parentId of Object.keys(parents ?? {})) {
    await redis.del(keys.ownerFamily(parentId));
  }
  await redis.del(keys.policy(familyId));
  await redis.del(keys.children(familyId));
  await redis.del(keys.parents(familyId));
  await redis.del(keys.family(familyId));
}

function fingerprint(event: SupervisionEvent): string {
  return `${event.at}|${event.category}|${event.severity}|${event.outcome}|${event.heeded}`;
}
