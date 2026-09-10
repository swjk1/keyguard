import { NextRequest } from 'next/server';
import { z } from 'zod';

import {
  PolicySchema,
  applyScopeNarrowing,
  familyOfChild,
  familyOwnedBy,
  readPolicy,
  touchChild,
  writePolicy,
} from '@/lib/family';
import { authorizeInstall, authorizeParent, isResponse, json } from '@/lib/guard';

/**
 * The policy, read by the child device and written by the parent.
 *
 * Both halves live here because they are the same object seen from two ends, and splitting
 * them invites the two shapes to drift.
 */

const PolicyInput = PolicySchema.omit({ version: true }).refine(
  (policy) => policy.aiVerification !== 'FORCED_ON',
  { message: 'A parent may disable cloud verification but may not consent to typed-text transfer for the child.' },
);

/**
 * A note on an inconsistency this route now contains, left deliberately rather than resolved.
 *
 * The refinement above blocks `aiVerification: FORCED_ON` on the stated principle that a parent
 * may not consent to their child's typed text leaving the device. `reviewScope: FULL_TEXT` is
 * accepted, and does exactly that — more thoroughly, since verification sends only flagged
 * spans while full review sends whole messages.
 *
 * The two rules cannot both be right. They are both here because the second was an explicit
 * product decision and the first was a considered position taken earlier, and quietly dropping
 * the earlier one as a side effect of shipping the later one would be the wrong way to change
 * it. Whichever way it goes, it should go on purpose:
 *
 * - Keep the principle, and `FULL_TEXT` should require something stronger than a policy write —
 *   a child-side acknowledgement, most plausibly.
 * - Drop it, and the refinement above should go, since a parent who may read every message can
 *   hardly be forbidden from consenting to a flagged phrase being checked.
 *
 * Flagged in the README's open questions rather than settled here.
 */

/**
 * The child device asking what it should be enforcing.
 *
 * `supervised: false` is the important reply, not an error case: when a parent removes a
 * child the device has to *learn* that, drop its cached policy, and take down the persistent
 * monitoring notice. A 404 here would be indistinguishable from a bad deploy, and the device
 * would keep telling its user it was being watched.
 */
export async function GET(request: NextRequest) {
  const caller = await authorizeInstall(request);
  if (isResponse(caller)) return caller;

  const familyId = await familyOfChild(caller.installId);
  if (!familyId) return json({ supervised: false });

  await touchChild(familyId, caller.installId);
  return json({ supervised: true, familyId, policy: await readPolicy(familyId) });
}

/** The parent changing what their child's keyboard does. */
export async function PUT(request: NextRequest) {
  const caller = await authorizeParent(request);
  if (isResponse(caller)) return caller;

  const familyId = await familyOwnedBy(caller.account.id);
  if (!familyId) return json({ error: 'no_family' }, { status: 404 });

  let body: z.infer<typeof PolicyInput>;
  try {
    body = PolicyInput.parse(await request.json());
  } catch {
    return json({ error: 'invalid_body' }, { status: 400 });
  }

  const previous = await readPolicy(familyId);

  // The version is the server's to assign; a client-supplied one would let a stale parent
  // screen overwrite a newer change and leave devices polling for a number that never moves.
  const policy = await writePolicy(familyId, body);

  // Narrowing the scope has to reach data already stored, not just data collected from now on.
  // Otherwise "turn full review off" would leave a fortnight of the child's messages sitting on
  // the server, still readable by the parent who just said they no longer wanted them — which
  // is not what anyone means by turning it off. Done after the write so a crash in between
  // leaves the stricter policy in force rather than the looser one.
  await applyScopeNarrowing(familyId, previous.reviewScope, policy.reviewScope);

  return json({ policy });
}
