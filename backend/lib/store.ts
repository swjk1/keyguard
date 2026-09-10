import { Redis } from '@upstash/redis';

/**
 * Durable storage for family state.
 *
 * **This fails closed, and that is the opposite of `lib/ratelimit.ts` on purpose.** The
 * limiter fails open because a missing Redis must not take safety verification offline —
 * overspend is recoverable. Family state has no such fallback: with no store there is no
 * policy to serve and nowhere to put an event, and inventing a permissive answer would tell a
 * child's device it is unsupervised when a parent believes otherwise. A route that cannot
 * reach the store says so and the device keeps its cached policy.
 */

let client: Redis | null = null;

/**
 * The REST credentials, under whichever names the platform happened to use.
 *
 * Vercel's Upstash Marketplace integration provisions `KV_REST_API_URL` / `KV_REST_API_TOKEN`;
 * a hand-configured Upstash project uses `UPSTASH_REDIS_REST_URL` / `UPSTASH_REDIS_REST_TOKEN`,
 * which is also what `Redis.fromEnv()` looks for. Both are the same REST endpoint.
 *
 * Reading either is deliberate, rather than aliasing one to the other in the environment.
 * An alias duplicates a live credential across two variables and goes stale the moment the
 * integration rotates the original — which fails as a 401 at request time, in the one
 * subsystem that fails closed. Accepting both names has no such failure mode.
 *
 * Note this must NOT fall back to `REDIS_URL`: the integration sets that too, but it is the
 * wire-protocol URL and this client speaks REST.
 */
function credentials(): { url: string; token: string } | null {
  const url = process.env.UPSTASH_REDIS_REST_URL ?? process.env.KV_REST_API_URL;
  const token = process.env.UPSTASH_REDIS_REST_TOKEN ?? process.env.KV_REST_API_TOKEN;
  return url && token ? { url, token } : null;
}

export function storeConfigured(): boolean {
  return credentials() !== null;
}

export function store(): Redis | null {
  const config = credentials();
  if (!config) return null;
  if (!client) client = new Redis(config);
  return client;
}

/** The 503 every family route returns when the store is unreachable. */
export function storeUnavailable(): Response {
  return Response.json({ error: 'family_unavailable' }, { status: 503 });
}

export const keys = {
  accountByEmail: (email: string) => `kg:account:email:${email}`,
  account: (accountId: string) => `kg:account:${accountId}`,
  session: (sessionHash: string) => `kg:session:${sessionHash}`,
  accountSessions: (accountId: string) => `kg:account:${accountId}:sessions`,
  /** familyId available to a parent account. One active family per account. */
  ownerFamily: (accountId: string) => `kg:fam:owner:${accountId}`,
  family: (familyId: string) => `kg:fam:${familyId}`,
  /** Hash of accountId -> OWNER | CAREGIVER. */
  parents: (familyId: string) => `kg:fam:${familyId}:parents`,
  policy: (familyId: string) => `kg:fam:${familyId}:policy`,
  /** Hash of childInstallId -> membership record. */
  children: (familyId: string) => `kg:fam:${familyId}:children`,
  /** Reverse lookup so a child's token alone identifies its family. */
  childFamily: (childInstallId: string) => `kg:child:${childInstallId}`,
  pairingCode: (code: string) => `kg:pair:${code}`,
  parentInvite: (code: string) => `kg:parent-pair:${code}`,
  events: (familyId: string, childInstallId: string) =>
    `kg:events:${familyId}:${childInstallId}`,
  /** Per-message activity samples. Separate key from events - see SampleQueue on the device. */
  samples: (familyId: string, childInstallId: string) =>
    `kg:samples:${familyId}:${childInstallId}`,
  /** A generated report, cached so opening the screen twice does not pay for two model calls. */
  report: (familyId: string, childInstallId: string, period: string, bucket: string) =>
    `kg:report:${familyId}:${childInstallId}:${period}:${bucket}`,
};

/** Events older than this stop being anyone's business. */
export const EVENT_TTL_SECONDS = 60 * 60 * 24 * 30;

/** Newest-first cap per child. A parent reads the recent end; the tail is not evidence. */
export const EVENT_HISTORY_LIMIT = 500;

/** Long enough to read a code aloud and type it, short enough that a stolen one is stale. */
export const PAIRING_CODE_TTL_SECONDS = 60 * 10;

/**
 * Samples expire faster than events, and deliberately so.
 *
 * An event is a warning - the thing a parent may need to look back on weeks later, and the
 * record the product was designed around. A sample is ordinary conversation, and under
 * FULL_TEXT it is a child's messages. Keeping those for the same thirty days would mean the
 * most sensitive data in the system had the longest life, which is backwards. Fourteen days
 * covers every window a report can ask for with a week to spare.
 */
export const SAMPLE_TTL_SECONDS = 60 * 60 * 24 * 14;

/** Newest-first cap per child. Sized for a chatty fortnight, not for an archive. */
export const SAMPLE_HISTORY_LIMIT = 2000;

/**
 * How long a generated report stays cached.
 *
 * The cache key already contains the period's time bucket, so a stale report cannot be served
 * for the wrong day; this TTL only decides how often a *current* window is recomposed. An hour
 * means a parent refreshing repeatedly through an evening pays for one call, while a report
 * opened tomorrow morning still reflects last night.
 */
export const REPORT_CACHE_TTL_SECONDS = 60 * 60;

/** Parent sessions are renewable by signing in again and revocable per device. */
export const PARENT_SESSION_TTL_SECONDS = 60 * 60 * 24 * 30;
