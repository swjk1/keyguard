import { NextRequest } from 'next/server';
import { z } from 'zod';

import { issueInstallToken } from '@/lib/token';

/**
 * Exchanges a client-generated install id for a signed token.
 *
 * Anonymous by construction: the client invents a random UUID and we sign it. No account, no
 * email, no device identifier, nothing that ties the token to a person. The token exists only
 * so `/api/verify` can be rate limited per installation.
 */

const Body = z.object({
  installId: z.string().regex(/^[A-Za-z0-9-]{8,64}$/),
});

export async function POST(request: NextRequest) {
  let body: z.infer<typeof Body>;
  try {
    body = Body.parse(await request.json());
  } catch {
    return Response.json({ error: 'invalid_body' }, { status: 400 });
  }

  return Response.json(
    { token: issueInstallToken(body.installId) },
    { headers: { 'Cache-Control': 'no-store' } },
  );
}
