import { NextRequest } from 'next/server';
import { z } from 'zod';

import { isPairingCode, readPolicy, redeemPairingCode } from '@/lib/family';
import { authorizeInstall, isResponse, json } from '@/lib/guard';
import { checkPairingRateLimit } from '@/lib/ratelimit';

/**
 * Binds a child's device to a family.
 *
 * This is the only route where the caller is guessing at a secret, so it carries its own much
 * tighter limit on top of the ordinary family budget, and a malformed code is spent from that
 * budget just like a wrong one — otherwise the cheap way to probe would be to send garbage
 * until something sticks.
 *
 * The label is the child's own name for the device, typed on the child's phone. It is the one
 * free-text field in the whole supervision path, which is why it is short, and why the parent
 * UI shows it as a label rather than treating it as identity.
 */

const Body = z.object({
  code: z.string().max(32),
  label: z.string().trim().min(1).max(40).default('Phone'),
});

export async function POST(request: NextRequest) {
  const caller = await authorizeInstall(request);
  if (isResponse(caller)) return caller;

  const attempt = await checkPairingRateLimit(caller.token);
  if (!attempt.success) {
    return json(
      { error: 'too_many_attempts', resetAt: attempt.reset },
      { status: 429, headers: { 'Retry-After': String(attempt.retryAfterSeconds) } },
    );
  }

  let body: z.infer<typeof Body>;
  try {
    body = Body.parse(await request.json());
  } catch {
    return json({ error: 'invalid_body' }, { status: 400 });
  }

  const code = body.code.toUpperCase();
  if (!isPairingCode(code)) {
    // Same shape as a wrong code. A distinct "malformed" reply would tell a prober which
    // half of their guess was wrong.
    return json({ error: 'invalid_code' }, { status: 404 });
  }

  const familyId = await redeemPairingCode(code, caller.installId, body.label);
  if (!familyId) return json({ error: 'invalid_code' }, { status: 404 });

  return json({ familyId, policy: await readPolicy(familyId) });
}
