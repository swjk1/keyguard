import { NextRequest } from 'next/server';
import { z } from 'zod';

import { isPairingCode, readPolicy, redeemParentInvite } from '@/lib/family';
import { authorizeParent, isResponse, json } from '@/lib/guard';

const Body = z.object({ code: z.string().trim().max(32) });

export async function POST(request: NextRequest) {
  const caller = await authorizeParent(request);
  if (isResponse(caller)) return caller;
  let body: z.infer<typeof Body>;
  try {
    body = Body.parse(await request.json());
  } catch {
    return json({ error: 'invalid_code' }, { status: 400 });
  }
  const code = body.code.toUpperCase();
  if (!isPairingCode(code)) return json({ error: 'invalid_code' }, { status: 404 });
  const familyId = await redeemParentInvite(code, caller.account.id);
  if (!familyId) return json({ error: 'invalid_code' }, { status: 404 });
  return json({ familyId, policy: await readPolicy(familyId) });
}
