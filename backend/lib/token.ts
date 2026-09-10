import { createHmac, timingSafeEqual } from 'node:crypto';

/**
 * Anonymous per-install tokens.
 *
 * `/api/verify` spends money on every call, so leaving it open would be a standing invitation
 * to drain the inference budget. This is *not* user authentication: the token identifies an
 * installation so it can be rate limited, and carries no personal data. There is no account,
 * no email, and nothing that could tie a token back to a person.
 *
 * Format: `<installId>.<hmac>` where installId is a client-generated UUID.
 */

const SEPARATOR = '.';

function secret(): string {
  const value = process.env.KEYGUARD_TOKEN_SECRET;
  if (!value) throw new Error('KEYGUARD_TOKEN_SECRET is not set');
  return value;
}

function sign(installId: string): string {
  return createHmac('sha256', secret()).update(installId).digest('base64url');
}

/** Issues a token for a fresh install. Called by `/api/register`. */
export function issueInstallToken(installId: string): string {
  return `${installId}${SEPARATOR}${sign(installId)}`;
}

export function verifyInstallToken(token: string | null): boolean {
  if (!token) return false;

  const separatorIndex = token.lastIndexOf(SEPARATOR);
  if (separatorIndex <= 0) return false;

  const installId = token.slice(0, separatorIndex);
  const provided = token.slice(separatorIndex + 1);
  if (!/^[A-Za-z0-9-]{8,64}$/.test(installId)) return false;

  const expected = sign(installId);
  const a = Buffer.from(provided);
  const b = Buffer.from(expected);
  // Length check first: timingSafeEqual throws on a mismatch rather than returning false.
  if (a.length !== b.length) return false;
  return timingSafeEqual(a, b);
}

/** The install id inside a token, for rate-limit keying. */
export function installIdFrom(token: string): string {
  return token.slice(0, token.lastIndexOf(SEPARATOR));
}
