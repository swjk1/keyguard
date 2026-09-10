import { NextRequest } from 'next/server';

import { REPORT_PERIODS, familyOwnedBy, listChildren, readPolicy } from '@/lib/family';
import { buildReport } from '@/lib/report';
import { authorizeParent, isResponse, json } from '@/lib/guard';

/**
 * The parent's daily or weekly summary for one child.
 *
 * Parent-authenticated and keyed on the family the *account* owns, so a caller cannot ask for a
 * report about an install id that is not theirs — the child is looked up in their own family's
 * membership list rather than trusted from the query string. That check is the one that matters
 * here: a report is the most sensitive object this API serves, and under `FULL_TEXT` it is
 * composed from a child's messages.
 *
 * Generation is cached in `buildReport`, and there is deliberately no `force` parameter exposed.
 * A refresh button that regenerated on demand would let a parent spend the family's model
 * budget by tapping, and would produce a visibly different summary each time from the same
 * data — which is the fastest way to teach someone the report is not to be trusted.
 */
export async function GET(request: NextRequest) {
  const caller = await authorizeParent(request);
  if (isResponse(caller)) return caller;

  const familyId = await familyOwnedBy(caller.account.id);
  if (!familyId) return json({ error: 'no_family' }, { status: 404 });

  const installId = request.nextUrl.searchParams.get('installId')?.trim();
  if (!installId) return json({ error: 'invalid_body' }, { status: 400 });

  const requested = request.nextUrl.searchParams.get('period')?.toUpperCase();
  const period = (REPORT_PERIODS as readonly string[]).includes(requested ?? '')
    ? (requested as (typeof REPORT_PERIODS)[number])
    : 'DAY';

  // Membership is the authorization check, not the install id in the URL.
  const child = (await listChildren(familyId)).find((c) => c.installId === installId);
  if (!child) return json({ error: 'no_child' }, { status: 404 });

  const policy = await readPolicy(familyId);
  if (!policy.reportsEnabled) {
    return json({ error: 'reports_disabled' }, { status: 409 });
  }

  const report = await buildReport({
    familyId,
    childInstallId: child.installId,
    childLabel: child.label,
    period,
    scope: policy.reviewScope,
  });

  return json({ report });
}
