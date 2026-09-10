import { NextRequest } from 'next/server';

import { ensureFamily, readPolicy } from '@/lib/family';
import { authorizeParent, isResponse, json } from '@/lib/guard';
import { storeUnavailable } from '@/lib/store';

/**
 * Turns a parent's install into a family.
 *
 * Idempotent — a parent who reopens the app gets the family they already own rather than a
 * second one, which is what keeps the pairing code they read to their child yesterday
 * pointing at the same place.
 *
 * Family ownership is anchored to the authenticated parent account, so another signed-in
 * device or a recovery-code reset reaches the same family rather than creating an orphan.
 */
export async function POST(request: NextRequest) {
  const caller = await authorizeParent(request);
  if (isResponse(caller)) return caller;

  const familyId = await ensureFamily(caller.account.id);
  if (!familyId) return storeUnavailable();

  return json({ familyId, policy: await readPolicy(familyId) });
}
