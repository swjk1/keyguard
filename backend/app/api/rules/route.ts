import { NextRequest } from 'next/server';
import pack from '../../../../detect/src/main/resources/rules/pack-v0.json';

/**
 * Serves the detection rule pack.
 *
 * Severity tuning is the highest-churn part of this product, and shipping an app update for
 * every adjustment would make the false-positive rate impossible to iterate on. The pack is
 * data, so it can be corrected the same day a bad rule is spotted.
 *
 * Single source of truth: this reads the same file the Android module bundles, so the served
 * pack and the built-in fallback cannot drift.
 */

export async function GET(request: NextRequest) {
  const since = Number(request.nextUrl.searchParams.get('since') ?? '0');

  // Nothing newer than what the client already has.
  if (Number.isFinite(since) && since >= pack.version) {
    return new Response(null, { status: 304 });
  }

  return Response.json(pack, {
    headers: {
      // Short cache: a bad rule needs to be correctable quickly, and the payload is small.
      'Cache-Control': 'public, max-age=300, stale-while-revalidate=3600',
    },
  });
}
