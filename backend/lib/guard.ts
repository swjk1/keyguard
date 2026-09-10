import { NextRequest } from 'next/server';

import { ParentAccount, parentFromSession } from './auth';
import { checkFamilyRateLimit, checkFamilyRateLimitKey } from './ratelimit';
import { store, storeUnavailable } from './store';
import { installIdFrom, verifyInstallToken } from './token';

/**
 * The three checks every family route repeats: a valid install token, a budget, and a store.
 *
 * Factored out because they have to happen in this order. Rate limiting an unauthenticated
 * caller keys the limiter on an attacker-chosen string, and touching the store before either
 * check spends a round trip on a request that was never going to be served.
 */

export interface InstallCaller {
  installId: string;
  token: string;
}

export async function authorizeInstall(request: NextRequest): Promise<InstallCaller | Response> {
  const token = request.headers.get('x-install-token');
  if (!verifyInstallToken(token)) {
    return Response.json({ error: 'invalid_token' }, { status: 401 });
  }

  const limit = await checkFamilyRateLimit(token!);
  if (!limit.success) {
    return Response.json(
      { error: 'rate_limited', resetAt: limit.reset },
      { status: 429, headers: { 'Retry-After': String(limit.retryAfterSeconds) } },
    );
  }

  if (!store()) return storeUnavailable();

  return { installId: installIdFrom(token!), token: token! };
}

export interface ParentCaller {
  account: ParentAccount;
  token: string;
}

export async function authorizeParent(request: NextRequest): Promise<ParentCaller | Response> {
  const header = request.headers.get('authorization');
  const token = header?.startsWith('Bearer ') ? header.slice(7).trim() : '';
  if (!token || !store()) return token ? storeUnavailable() : json({ error: 'invalid_session' }, { status: 401 });
  const account = await parentFromSession(token);
  if (!account) return json({ error: 'invalid_session' }, { status: 401 });
  const limit = await checkFamilyRateLimitKey(account.id);
  if (!limit.success) {
    return json(
      { error: 'rate_limited', resetAt: limit.reset },
      { status: 429, headers: { 'Retry-After': String(limit.retryAfterSeconds) } },
    );
  }
  return { account, token };
}

export function isResponse(value: unknown): value is Response {
  return value instanceof Response;
}

/** Family payloads are per-device state; nothing here may sit in a shared cache. */
export function json(body: unknown, init?: ResponseInit): Response {
  return Response.json(body, {
    ...init,
    headers: { ...init?.headers, 'Cache-Control': 'no-store' },
  });
}
