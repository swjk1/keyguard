import { NextRequest } from 'next/server';

import { ParentCredentialsSchema, normalizeEmail, registerParent } from '@/lib/auth';
import { checkAuthRateLimit } from '@/lib/ratelimit';
import { storeUnavailable, store } from '@/lib/store';
import { json } from '@/lib/guard';

export async function POST(request: NextRequest) {
  if (!store()) return storeUnavailable();
  let body;
  try {
    body = ParentCredentialsSchema.parse(await request.json());
  } catch {
    return json({ error: 'invalid_credentials', passwordMinimum: 12 }, { status: 400 });
  }
  const limit = await checkAuthRateLimit(`register:${normalizeEmail(body.email)}`);
  if (!limit.success) {
    return json({ error: 'rate_limited' }, { status: 429, headers: { 'Retry-After': String(limit.retryAfterSeconds) } });
  }
  const issued = await registerParent(body.email, body.password);
  if (!issued) return json({ error: 'email_in_use' }, { status: 409 });
  return json(issued, { status: 201 });
}
