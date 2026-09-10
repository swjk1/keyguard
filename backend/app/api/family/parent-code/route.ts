import { NextRequest } from 'next/server';

import { ensureFamily, issueParentInvite } from '@/lib/family';
import { authorizeParent, isResponse, json } from '@/lib/guard';
import { storeUnavailable } from '@/lib/store';

export async function POST(request: NextRequest) {
  const caller = await authorizeParent(request);
  if (isResponse(caller)) return caller;
  const familyId = await ensureFamily(caller.account.id);
  if (!familyId) return storeUnavailable();
  return json(await issueParentInvite(familyId));
}
