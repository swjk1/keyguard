import { NextRequest } from 'next/server';

import { deleteParentAccount } from '@/lib/auth';
import { removeParentAccountFamily } from '@/lib/family';
import { authorizeParent, isResponse, json } from '@/lib/guard';

export async function DELETE(request: NextRequest) {
  const caller = await authorizeParent(request);
  if (isResponse(caller)) return caller;
  await removeParentAccountFamily(caller.account.id);
  await deleteParentAccount(caller.account);
  return json({ deleted: true });
}
