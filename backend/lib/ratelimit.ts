import { Ratelimit } from '@upstash/ratelimit';
import { Redis } from '@upstash/redis';

import { installIdFrom } from './token';

/**
 * Per-install daily caps.
 *
 * This is the mechanism that makes the free tier's economics work. The planning meeting's
 * central worry was per-user inference cost, and the answer is a server-enforced ceiling
 * rather than client-side restraint: the cap can be retuned from configuration once the
 * benchmark produces real numbers, without shipping an app update.
 *
 * Being throttled costs a user refinement, never protection — the device keeps its local
 * verdict either way.
 */

const FREE_DAILY_LIMIT = Number(process.env.KEYGUARD_FREE_DAILY_LIMIT ?? 40);
const BURST_PER_MINUTE = Number(process.env.KEYGUARD_BURST_PER_MINUTE ?? 12);

/**
 * Family routes cost a Redis round trip, not an inference call, so they get their own budget
 * — the daily cap above is sized for model spend and a device polling its policy would eat it
 * by lunchtime.
 */
const FAMILY_PER_MINUTE = Number(process.env.KEYGUARD_FAMILY_PER_MINUTE ?? 30);

/**
 * Pairing is the one family route where the caller is guessing at a secret, so it is limited
 * far harder than the rest. At ten attempts an hour against 32^8 live-for-ten-minutes codes,
 * brute force is not a threat model.
 */
const PAIR_ATTEMPTS_PER_HOUR = Number(process.env.KEYGUARD_PAIR_ATTEMPTS_PER_HOUR ?? 10);
const AUTH_ATTEMPTS_PER_HOUR = Number(process.env.KEYGUARD_AUTH_ATTEMPTS_PER_HOUR ?? 20);

export interface RateLimitResult {
  success: boolean;
  remaining: number;
  reset: number;
  retryAfterSeconds: number;
}

let redis: Redis | null = null;
let dailyLimiter: Ratelimit | null = null;
let burstLimiter: Ratelimit | null = null;
let familyLimiter: Ratelimit | null = null;
let pairLimiter: Ratelimit | null = null;
let authLimiter: Ratelimit | null = null;

function limiters() {
  if (!redis) {
    // Absent Redis, fail *open* rather than closed. A misconfigured limiter must not take
    // safety verification offline; overspend is recoverable, a broken product is worse.
    if (!process.env.UPSTASH_REDIS_REST_URL) return null;
    redis = Redis.fromEnv();
    dailyLimiter = new Ratelimit({
      redis,
      limiter: Ratelimit.fixedWindow(FREE_DAILY_LIMIT, '1 d'),
      prefix: 'kg:day',
    });
    burstLimiter = new Ratelimit({
      redis,
      // Catches a runaway client looping on a request; the daily cap handles cost.
      limiter: Ratelimit.slidingWindow(BURST_PER_MINUTE, '1 m'),
      prefix: 'kg:burst',
    });
    familyLimiter = new Ratelimit({
      redis,
      limiter: Ratelimit.slidingWindow(FAMILY_PER_MINUTE, '1 m'),
      prefix: 'kg:fam',
    });
    pairLimiter = new Ratelimit({
      redis,
      limiter: Ratelimit.slidingWindow(PAIR_ATTEMPTS_PER_HOUR, '1 h'),
      prefix: 'kg:pair',
    });
    authLimiter = new Ratelimit({
      redis,
      limiter: Ratelimit.slidingWindow(AUTH_ATTEMPTS_PER_HOUR, '1 h'),
      prefix: 'kg:auth',
    });
  }
  return {
    dailyLimiter: dailyLimiter!,
    burstLimiter: burstLimiter!,
    familyLimiter: familyLimiter!,
    pairLimiter: pairLimiter!,
    authLimiter: authLimiter!,
  };
}

export async function checkRateLimit(token: string): Promise<RateLimitResult> {
  const active = limiters();
  if (!active) {
    return { success: true, remaining: -1, reset: 0, retryAfterSeconds: 0 };
  }

  const key = installIdFrom(token);
  const burst = await active.burstLimiter.limit(key);
  if (!burst.success) {
    return toResult(false, burst.remaining, burst.reset);
  }

  const daily = await active.dailyLimiter.limit(key);
  return toResult(daily.success, daily.remaining, daily.reset);
}

/**
 * Ordinary family traffic: policy polls, event uploads, a parent refreshing the activity list.
 *
 * Fails open like the rest. Being throttled out of a policy poll costs a device one cycle of
 * staleness; failing closed would make a misconfigured limiter look exactly like a parent
 * having removed the child.
 */
export async function checkFamilyRateLimit(token: string): Promise<RateLimitResult> {
  const active = limiters();
  if (!active) return { success: true, remaining: -1, reset: 0, retryAfterSeconds: 0 };

  const result = await active.familyLimiter.limit(installIdFrom(token));
  return toResult(result.success, result.remaining, result.reset);
}

export async function checkFamilyRateLimitKey(key: string): Promise<RateLimitResult> {
  const active = limiters();
  if (!active) return { success: true, remaining: -1, reset: 0, retryAfterSeconds: 0 };
  const result = await active.familyLimiter.limit(key);
  return toResult(result.success, result.remaining, result.reset);
}

export async function checkAuthRateLimit(key: string): Promise<RateLimitResult> {
  const active = limiters();
  if (!active) return { success: true, remaining: -1, reset: 0, retryAfterSeconds: 0 };
  const result = await active.authLimiter.limit(key);
  return toResult(result.success, result.remaining, result.reset);
}

/** The guessing budget for `/api/family/join`, keyed per install. */
export async function checkPairingRateLimit(token: string): Promise<RateLimitResult> {
  const active = limiters();
  if (!active) return { success: true, remaining: -1, reset: 0, retryAfterSeconds: 0 };

  const result = await active.pairLimiter.limit(installIdFrom(token));
  return toResult(result.success, result.remaining, result.reset);
}

function toResult(success: boolean, remaining: number, reset: number): RateLimitResult {
  return {
    success,
    remaining,
    reset,
    retryAfterSeconds: Math.max(1, Math.ceil((reset - Date.now()) / 1000)),
  };
}
