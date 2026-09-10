import { NextRequest } from 'next/server';

import { authorizeParent, isResponse, json } from '@/lib/guard';

export async function GET(request: NextRequest) {
  const caller = await authorizeParent(request);
  if (isResponse(caller)) return caller;
  const { id, email, createdAt } = caller.account;
  return json({ account: { id, email, createdAt } });
}
