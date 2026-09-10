import { NextRequest } from 'next/server';

import { ensureFamily, issuePairingCode } from '@/lib/family';
import { authorizeParent, isResponse, json } from '@/lib/guard';
import { storeUnavailable } from '@/lib/store';

/**
 * Mints a short-lived pairing code for the calling parent's family.
 *
 * Codes are minted here and never on a device: a client that could invent its own code could
 * name the family it joins. Each one is single-use and expires in ten minutes, so a code left
 * on screen or overheard is worth nothing shortly after it is used or read.
 */
export async function POST(request: NextRequest) {
  const caller = await authorizeParent(request);
  if (isResponse(caller)) return caller;

  // Creates on demand, so a parent who taps straight to "add a child" never sees an error
  // about a family they were never told they had to make.
  const familyId = await ensureFamily(caller.account.id);
  if (!familyId) return storeUnavailable();

  return json(await issuePairingCode(familyId));
}
