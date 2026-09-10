import { NextRequest } from 'next/server';

import { ParentCredentialsSchema, loginParent, normalizeEmail } from '@/lib/auth';
import { json } from '@/lib/guard';
import { checkAuthRateLimit } from '@/lib/ratelimit';
import { store, storeUnavailable } from '@/lib/store';

export async function POST(request: NextRequest) {
  if (!store()) return storeUnavailable();
  let body;
  try {
    body = ParentCredentialsSchema.parse(await request.json());
  } catch {
    return json({ error: 'invalid_credentials' }, { status: 400 });
  }
  const forwarded = request.headers.get('x-forwarded-for')?.split(',')[0]?.trim() ?? 'unknown';
  const limit = await checkAuthRateLimit(`login:${forwarded}:${normalizeEmail(body.email)}`);
  if (!limit.success) {
    return json({ error: 'rate_limited' }, { status: 429, headers: { 'Retry-After': String(limit.retryAfterSeconds) } });
  }
  const issued = await loginParent(body.email, body.password);
  if (!issued) return json({ error: 'invalid_credentials' }, { status: 401 });
  return json(issued);
}
