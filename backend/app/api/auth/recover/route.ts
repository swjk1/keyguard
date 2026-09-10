import { NextRequest } from 'next/server';
import { z } from 'zod';

import { normalizeEmail, recoverParent } from '@/lib/auth';
import { json } from '@/lib/guard';
import { checkAuthRateLimit } from '@/lib/ratelimit';
import { store, storeUnavailable } from '@/lib/store';

const Body = z.object({
  email: z.string().trim().email().max(254),
  recoveryCode: z.string().trim().min(20).max(40),
  newPassword: z.string().min(12).max(128),
});

export async function POST(request: NextRequest) {
  if (!store()) return storeUnavailable();
  let body: z.infer<typeof Body>;
  try {
    body = Body.parse(await request.json());
  } catch {
    return json({ error: 'invalid_recovery' }, { status: 400 });
  }
  const limit = await checkAuthRateLimit(`recover:${normalizeEmail(body.email)}`);
  if (!limit.success) {
    return json({ error: 'rate_limited' }, { status: 429, headers: { 'Retry-After': String(limit.retryAfterSeconds) } });
  }
  const issued = await recoverParent(body.email, body.recoveryCode, body.newPassword);
  if (!issued) return json({ error: 'invalid_recovery' }, { status: 401 });
  return json(issued);
}
