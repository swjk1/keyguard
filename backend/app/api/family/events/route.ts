import { NextRequest } from 'next/server';
import { z } from 'zod';

import { EventSchema, appendEvents, familyOfChild, touchChild } from '@/lib/family';
import { authorizeInstall, isResponse, json } from '@/lib/guard';

/**
 * Where a supervised device delivers its queue.
 *
 * The schema is the enforcement point for the promise the product makes: category, severity,
 * outcome and a timestamp, and zod is `strict()` so a build that started attaching a snippet
 * would be rejected here rather than quietly stored. That is the check worth having, because
 * the client is the easy thing to change and the server is the thing that would have to keep
 * the text.
 *
 * `supervised: false` in the reply is how a device that was removed while offline finds out,
 * and its cue to drop the queue rather than retry a batch nobody will ever read.
 */

const Body = z.object({
  events: z.array(EventSchema.strict()).min(1).max(100),
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

  const accepted = await appendEvents(familyId, caller.installId, body.events);
  await touchChild(familyId, caller.installId);

  // `received` is what the device confirms against, not `accepted`: a duplicate the server
  // deliberately dropped is still delivered, and leaving it queued would resend it forever.
  return json({ supervised: true, accepted, received: body.events.length });
}
