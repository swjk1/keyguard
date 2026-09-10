import { NextRequest } from 'next/server';
import { z } from 'zod';

import { familyOwnedBy, removeChild } from '@/lib/family';
import { authorizeParent, isResponse, json } from '@/lib/guard';

/**
 * Unpairing, which only a parent can do.
 *
 * There is deliberately no child-side counterpart. A supervised device that could quietly
 * unpair itself is not supervised, and a monitoring tool that lets the monitored party switch
 * it off without the other party knowing is worse than useless — it reports "all quiet" that
 * means nothing. The child's genuine exit is uninstalling the keyboard, which the parent sees
 * as a device that stopped reporting, and the supervision screen says so in as many words.
 *
 * Removal also deletes the child's event history. A parent who unpairs has ended the
 * arrangement; keeping the record of it afterwards serves nobody.
 */

const Body = z.object({
  installId: z.string().regex(/^[A-Za-z0-9-]{8,64}$/),
});

export async function DELETE(request: NextRequest) {
  const caller = await authorizeParent(request);
  if (isResponse(caller)) return caller;

  const familyId = await familyOwnedBy(caller.account.id);
  if (!familyId) return json({ error: 'no_family' }, { status: 404 });

  let body: z.infer<typeof Body>;
  try {
    body = Body.parse(await request.json());
  } catch {
    return json({ error: 'invalid_body' }, { status: 400 });
  }

  await removeChild(familyId, body.installId);
  return json({ removed: true });
}
