import { NextRequest } from 'next/server';

import { logoutParent } from '@/lib/auth';
import { authorizeParent, isResponse, json } from '@/lib/guard';

export async function POST(request: NextRequest) {
  const caller = await authorizeParent(request);
  if (isResponse(caller)) return caller;
  await logoutParent(caller.token);
  return json({ signedOut: true });
}
