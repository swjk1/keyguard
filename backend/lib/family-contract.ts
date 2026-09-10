import { randomBytes } from 'node:crypto';
import { z } from 'zod';

const CODE_ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
const CODE_LENGTH = 8;

const CATEGORIES = [
  'PII_DISCLOSURE',
  'HARASSMENT',
  'SEXUAL_SOLICITATION',
  'SELF_HARM',
  'VIOLENCE_THREAT',
  'IN_PERSON_MEETUP',
  'SUBSTANCE',
] as const;

const OUTCOMES = [
  'SENT',
  'SENT_INFERRED',
  'ABANDONED_DELETED',
  'ABANDONED_SWITCHED',
] as const;

/**
 * The reporting vocabulary, mirroring `Theme` and `Emotion` in the `:detect` module.
 *
 * Duplicated here rather than shared, because there is no build step joining a Kotlin module to
 * a Next.js app and inventing one for two string lists would cost more than it saves. What
 * keeps them honest is that the device sends names and the server validates them: a Kotlin enum
 * value missing from these lists is rejected at ingest and shows up immediately, rather than
 * being stored as an unknown string and quietly breaking an aggregate months later.
 */
const THEMES = [
  'SCHOOL',
  'FRIENDSHIP',
  'FAMILY',
  'ROMANCE',
  'GAMING',
  'SPORT',
  'MUSIC_AND_SHOWS',
  'ONLINE_LIFE',
  'MONEY',
  'FOOD',
  'HEALTH',
  'APPEARANCE',
  'FUTURE_PLANS',
  'TRAVEL',
  'PETS',
  'CREATIVE',
  'CONFLICT',
] as const;

const EMOTIONS = [
  'HAPPY',
  'EXCITED',
  'AFFECTIONATE',
  'SAD',
  'ANXIOUS',
  'ANGRY',
  'LONELY',
  'STRESSED',
  'TIRED',
] as const;

export const THEME_NAMES = THEMES;
export const EMOTION_NAMES = EMOTIONS;

/**
 * How much authority the child has over a warning. Mirrors `OverrideLevel` on the device.
 *
 * The server does not enforce this — it cannot, since the decision happens on a phone that may
 * be offline — it only stores and serves it. That is worth being explicit about: a modified
 * client could ignore the level entirely, and this field is a parental control, not a security
 * boundary. The same has always been true of `minIntensity` and `blockAtHigh`.
 */
const OVERRIDE_LEVELS = ['FULL', 'LIMITED', 'NONE'] as const;

/**
 * How much of what the child types the parent can see. Mirrors `ReviewScope` on the device.
 *
 * Unlike the levels above, this one the server *does* enforce, and it is the only policy field
 * that is enforced here. `POST /api/family/samples` rejects text that the family's stored scope
 * does not permit, so a client that started attaching message bodies under CONCERNING_ONLY
 * would be refused rather than quietly stored — the same discipline `EventSchema.strict()`
 * already applies to warnings, for the same reason: the client is the easy thing to change and
 * the server is the thing that would have to keep the text.
 */
const REVIEW_SCOPES = ['CONCERNING_ONLY', 'THEMES', 'FULL_TEXT'] as const;

export const PolicySchema = z.object({
  version: z.number().int().nonnegative(),
  minIntensity: z.enum(['SUBTLE', 'STANDARD', 'INSISTENT']),
  blockAtHigh: z.boolean(),
  aiVerification: z.enum(['CHILD_CHOICE', 'FORCED_ON', 'FORCED_OFF']),
  lockSettings: z.boolean(),
  // Defaulted rather than required, so a policy written before these existed still parses and
  // resolves to the pre-supervision behaviour. A stored policy that failed to parse would fall
  // back to DEFAULT_POLICY anyway, but silently rewriting a family's intensity floor because
  // an unrelated field was added is not a failure mode worth having.
  overrideLevel: z.enum(OVERRIDE_LEVELS).default('FULL'),
  reviewScope: z.enum(REVIEW_SCOPES).default('CONCERNING_ONLY'),
  reportsEnabled: z.boolean().default(true),
});

export type Policy = z.infer<typeof PolicySchema>;
export type ReviewScope = (typeof REVIEW_SCOPES)[number];

export const EventSchema = z.object({
  at: z.number().int().nonnegative(),
  category: z.enum(CATEGORIES),
  severity: z.number().int().min(0).max(3),
  outcome: z.enum(OUTCOMES),
  heeded: z.boolean(),
});

export type SupervisionEvent = z.infer<typeof EventSchema>;

/**
 * One composed message, as the device reports it.
 *
 * `text` is optional and its presence is the whole security-relevant property of this schema.
 * Note it is *not* `.strict()` in the way `EventSchema` is used at ingest — it cannot be, since
 * the optional field is the point — so the enforcement moved rather than disappeared: the
 * samples route checks the family's stored scope against whether `text` is present, which is a
 * stronger check than a shape assertion because it is tied to what the family actually agreed
 * to rather than to what the schema happens to allow.
 */
export const SampleSchema = z.object({
  at: z.number().int().nonnegative(),
  themes: z.array(z.enum(THEMES)).max(5).default([]),
  emotions: z.array(z.enum(EMOTIONS)).max(5).default([]),
  chars: z.number().int().nonnegative().max(100_000),
  flagged: z.boolean(),
  outcome: z.enum(OUTCOMES),
  text: z.string().max(500).optional(),
});

export type ActivitySample = z.infer<typeof SampleSchema>;

export const REPORT_PERIODS = ['DAY', 'WEEK'] as const;
export type ReportPeriod = (typeof REPORT_PERIODS)[number];

export function mintPairingCode(): string {
  let code = '';
  while (code.length < CODE_LENGTH) {
    for (const byte of randomBytes(CODE_LENGTH)) {
      if (byte >= 256 - (256 % CODE_ALPHABET.length)) continue;
      code += CODE_ALPHABET[byte % CODE_ALPHABET.length];
      if (code.length === CODE_LENGTH) break;
    }
  }
  return code;
}

export function isPairingCode(value: string): boolean {
  return value.length === CODE_LENGTH && [...value].every((c) => CODE_ALPHABET.includes(c));
}

/** Whether a scope permits message text to be stored at all. */
export function scopePermitsText(scope: ReviewScope): boolean {
  return scope === 'FULL_TEXT';
}

/** Whether a scope permits unflagged messages to be recorded at all. */
export function scopePermitsSamples(scope: ReviewScope): boolean {
  return scope !== 'CONCERNING_ONLY';
}
