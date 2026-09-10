import { NextRequest } from 'next/server';
import { z } from 'zod';

import {
  SampleSchema,
  appendSamples,
  familyOfChild,
  readPolicy,
  scopePermitsSamples,
  scopePermitsText,
  touchChild,
} from '@/lib/family';
import { authorizeInstall, isResponse, json } from '@/lib/guard';

/**
 * Where a supervised device delivers ordinary messages, at whatever depth its family permits.
 *
 * Separate from `/api/family/events` on purpose. Events are warnings and exist at every review
 * scope; these are unflagged conversation and exist only above `CONCERNING_ONLY`. Sharing a
 * route would mean one request that could be partly accepted and partly refused, which is not a
 * thing either side could act on sensibly.
 *
 * ### The scope check is the point of this file
 *
 * `/api/family/events` enforces its promise with `EventSchema.strict()`: there is no text field,
 * so a client that started attaching one is rejected by the shape. That trick is unavailable
 * here, because an optional text field is exactly what the feature is. So the enforcement moves
 * from the schema to the *family's stored policy*, which is a stronger check rather than a
 * weaker one — it is tied to what this particular family agreed to rather than to what the wire
 * format happens to allow.
 *
 * Two refusals, and they are deliberately different:
 *
 * - **Samples at all under `CONCERNING_ONLY`** is a 403 with `scope_withdrawn`. The device is
 *   told to drop its queue, because a parent narrowed the scope while it was offline and those
 *   records have no reader who is entitled to them.
 * - **Text under a scope that permits samples but not text** is *not* an error. The derived
 *   half is still legitimate, so the batch is accepted and `appendSamples` strips the bodies.
 *   Rejecting would make a stale client look like a network failure and retry the same payload
 *   forever, which is a worse outcome than storing the half that was allowed.
 */

const Body = z.object({
  samples: z.array(SampleSchema).min(1).max(100),
});

export async function POST(request: NextRequest) {
  const caller = await authorizeInstall(request);
  if (isResponse(caller)) return caller;

  const familyId = await familyOfChild(caller.installId);
  if (!familyId) return json({ supervised: false, accepted: 0 });

  let body: z.infer<typeof Body>;
  try {
    body = Body.parse(await request.json());
  } catch {
    return json({ error: 'invalid_body' }, { status: 400 });
  }

  const policy = await readPolicy(familyId);

  if (!policy.reportsEnabled || !scopePermitsSamples(policy.reviewScope)) {
    return json(
      { error: 'scope_withdrawn', scope: policy.reviewScope },
      { status: 403 },
    );
  }

  const accepted = await appendSamples(
    familyId,
    caller.installId,
    body.samples,
    policy.reviewScope,
  );
  await touchChild(familyId, caller.installId);

  return json({
    supervised: true,
    accepted,
    // Confirmed against what was *received*, matching the events route: a sample whose text was
    // stripped is still delivered, and leaving it queued would resend it forever.
    received: body.samples.length,
    // Echoed so a device can notice a narrowing without waiting for its next policy poll.
    scope: policy.reviewScope,
    textStored: scopePermitsText(policy.reviewScope),
  });
}
