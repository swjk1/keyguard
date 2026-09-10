import { isModelConfigured } from '@/lib/model';
import { storeConfigured } from '@/lib/store';

/**
 * Reports what is and is not configured.
 *
 * Exists because every failure in the verify path deliberately degrades to "keep the local
 * verdict", which is right for the user but means a missing key looks identical to a healthy
 * server that found nothing wrong. This makes the difference visible.
 */
export async function GET() {
  const checks = {
    tokenSecret: Boolean(process.env.KEYGUARD_TOKEN_SECRET),
    model: isModelConfigured(),
    // Via the store itself, so health cannot report ready while `store()` returns null —
    // two copies of "is Redis configured" is exactly how a health check starts lying.
    redis: storeConfigured(),
  };

  const ok = checks.tokenSecret && checks.redis;

  return Response.json(
    {
      ok,
      checks,
      notes: {
        tokenSecret: checks.tokenSecret ? null : 'KEYGUARD_TOKEN_SECRET missing: /api/register will fail',
        model: checks.model ? null : 'AI verification is unavailable; local detection still works',
        redis: checks.redis ? null : 'Redis URL/token missing: accounts and supervision are unavailable',
      },
    },
    { status: ok ? 200 : 503, headers: { 'Cache-Control': 'no-store' } },
  );
}
