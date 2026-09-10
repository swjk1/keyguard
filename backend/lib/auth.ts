import {
  createHash,
  randomBytes,
  randomUUID,
  timingSafeEqual,
} from 'node:crypto';
import { z } from 'zod';

import {
  generateRecoveryCode,
  hashPassword,
  hashRecoveryCode,
  normalizeEmail,
  verifyPassword,
} from './credentials';
import { keys, PARENT_SESSION_TTL_SECONDS, store } from './store';

export { ParentCredentialsSchema, normalizeEmail } from './credentials';

const AccountSchema = z.object({
  id: z.string().uuid(),
  email: z.string().email(),
  passwordHash: z.string(),
  recoveryHash: z.string(),
  createdAt: z.number().int().nonnegative(),
});

export type ParentAccount = z.infer<typeof AccountSchema>;

const SessionSchema = z.object({
  accountId: z.string().uuid(),
  createdAt: z.number().int().nonnegative(),
});

export interface IssuedParentSession {
  token: string;
  account: Pick<ParentAccount, 'id' | 'email' | 'createdAt'>;
}

function hashSessionToken(token: string): string {
  return createHash('sha256').update(token).digest('base64url');
}

function publicAccount(account: ParentAccount): IssuedParentSession['account'] {
  return { id: account.id, email: account.email, createdAt: account.createdAt };
}

async function issueSession(account: ParentAccount): Promise<IssuedParentSession> {
  const redis = store()!;
  const token = randomBytes(32).toString('base64url');
  const tokenHash = hashSessionToken(token);
  await redis.set(
    keys.session(tokenHash),
    { accountId: account.id, createdAt: Date.now() },
    { ex: PARENT_SESSION_TTL_SECONDS },
  );
  await redis.sadd(keys.accountSessions(account.id), tokenHash);
  await redis.expire(keys.accountSessions(account.id), PARENT_SESSION_TTL_SECONDS);
  return { token, account: publicAccount(account) };
}

export async function registerParent(
  emailInput: string,
  password: string,
): Promise<(IssuedParentSession & { recoveryCode: string }) | null> {
  const redis = store()!;
  const email = normalizeEmail(emailInput);
  const accountId = randomUUID();
  const recoveryCode = generateRecoveryCode();
  const account: ParentAccount = {
    id: accountId,
    email,
    passwordHash: await hashPassword(password),
    recoveryHash: hashRecoveryCode(recoveryCode),
    createdAt: Date.now(),
  };

  const claimed = await redis.set(keys.accountByEmail(email), accountId, { nx: true });
  if (!claimed) return null;
  try {
    await redis.set(keys.account(accountId), account);
    return { ...(await issueSession(account)), recoveryCode };
  } catch (error) {
    await redis.del(keys.accountByEmail(email));
    throw error;
  }
}

async function accountForEmail(emailInput: string): Promise<ParentAccount | null> {
  const redis = store()!;
  const accountId = await redis.get<string>(keys.accountByEmail(normalizeEmail(emailInput)));
  if (!accountId) return null;
  const parsed = AccountSchema.safeParse(await redis.get(keys.account(accountId)));
  return parsed.success ? parsed.data : null;
}

export async function loginParent(email: string, password: string): Promise<IssuedParentSession | null> {
  const account = await accountForEmail(email);
  if (!account || !(await verifyPassword(password, account.passwordHash))) return null;
  return issueSession(account);
}

export async function parentFromSession(token: string): Promise<ParentAccount | null> {
  const redis = store()!;
  if (!/^[A-Za-z0-9_-]{40,80}$/.test(token)) return null;
  const session = SessionSchema.safeParse(await redis.get(keys.session(hashSessionToken(token))));
  if (!session.success) return null;
  const account = AccountSchema.safeParse(await redis.get(keys.account(session.data.accountId)));
  return account.success ? account.data : null;
}

export async function logoutParent(token: string): Promise<void> {
  const redis = store()!;
  const tokenHash = hashSessionToken(token);
  const session = SessionSchema.safeParse(await redis.get(keys.session(tokenHash)));
  await redis.del(keys.session(tokenHash));
  if (session.success) await redis.srem(keys.accountSessions(session.data.accountId), tokenHash);
}

export async function recoverParent(
  email: string,
  recoveryCode: string,
  newPassword: string,
): Promise<(IssuedParentSession & { recoveryCode: string }) | null> {
  const redis = store()!;
  const account = await accountForEmail(email);
  if (!account) return null;
  const provided = Buffer.from(hashRecoveryCode(recoveryCode));
  const expected = Buffer.from(account.recoveryHash);
  if (provided.length !== expected.length || !timingSafeEqual(provided, expected)) return null;

  await revokeAllSessions(account.id);
  const nextRecoveryCode = generateRecoveryCode();
  const updated: ParentAccount = {
    ...account,
    passwordHash: await hashPassword(newPassword),
    recoveryHash: hashRecoveryCode(nextRecoveryCode),
  };
  await redis.set(keys.account(account.id), updated);
  return { ...(await issueSession(updated)), recoveryCode: nextRecoveryCode };
}

export async function revokeAllSessions(accountId: string): Promise<void> {
  const redis = store()!;
  const hashes = await redis.smembers<string[]>(keys.accountSessions(accountId));
  if (hashes.length > 0) await redis.del(...hashes.map(keys.session));
  await redis.del(keys.accountSessions(accountId));
}

export async function deleteParentAccount(account: ParentAccount): Promise<void> {
  const redis = store()!;
  await revokeAllSessions(account.id);
  await redis.del(keys.accountByEmail(account.email));
  await redis.del(keys.account(account.id));
  await redis.del(keys.ownerFamily(account.id));
}
