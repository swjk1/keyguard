import { NextRequest } from 'next/server';

import { familyOwnedBy, listChildren, readEvents, readPolicy } from '@/lib/family';
import { authorizeParent, isResponse, json } from '@/lib/guard';

/**
 * Everything the parent screen renders, in one call: the policy and every child's activity.
 *
 * One request rather than a policy call plus a children call plus an events call per child,
 * because the parent side is a phone on mobile data and the payload is small — a hundred
 * events is a few kilobytes when none of them carries any text.
 *
 * The policy is served here rather than from `GET /api/family/policy`, which answers as the
 * *child* and is keyed on the caller's own membership. A parent is not a member of their own
 * family, so that route would tell them they are unsupervised, which is true and useless.
 */

const DEFAULT_LIMIT = 100;
const MAX_LIMIT = 200;

export async function GET(request: NextRequest) {
  const caller = await authorizeParent(request);
  if (isResponse(caller)) return caller;

  const familyId = await familyOwnedBy(caller.account.id);
  if (!familyId) return json({ familyId: null, policy: null, children: [] });

  const requested = Number(request.nextUrl.searchParams.get('limit') ?? DEFAULT_LIMIT);
  const limit = Number.isFinite(requested)
    ? Math.min(Math.max(Math.trunc(requested), 1), MAX_LIMIT)
    : DEFAULT_LIMIT;

  const children = await listChildren(familyId);
  const withEvents = await Promise.all(
    children.map(async (child) => ({
      ...child,
      events: await readEvents(familyId, child.installId, limit),
    })),
  );

  return json({ familyId, policy: await readPolicy(familyId), children: withEvents });
}
