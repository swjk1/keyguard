import { generateObject } from 'ai';
import { NextRequest } from 'next/server';
import { z } from 'zod';

import { resolveModel } from '@/lib/model';
import { checkRateLimit } from '@/lib/ratelimit';
import { verifyInstallToken } from '@/lib/token';

/**
 * Second-opinion verification for spans the on-device engine flagged.
 *
 * This is deliberately *not* the primary detector. The local engine has to stand alone —
 * Apple's guideline 4.4.1 requires a keyboard extension to "remain functional without full
 * network access and without requiring full access" — so this endpoint only ever refines a
 * result the device already has. If it is slow, rate limited, or down, the keyboard keeps
 * working with its local verdict.
 *
 * What it is genuinely good at, and local rules are not: telling figurative language from
 * intent. "I want to die of embarrassment" and "I want to die" are lexically near-identical
 * and mean completely different things.
 */

const CATEGORIES = [
  'PII_DISCLOSURE',
  'HARASSMENT',
  'SEXUAL_SOLICITATION',
  'SELF_HARM',
  'VIOLENCE_THREAT',
  'IN_PERSON_MEETUP',
  'SUBSTANCE',
] as const;

/** A finding the device is asking about. */
const FindingInput = z.object({
  ruleId: z.string().max(120),
  category: z.enum(CATEGORIES),
  severity: z.number().int().min(0).max(3),
  /** The flagged substring only — never the whole message. */
  text: z.string().max(400),
});

const RequestBody = z.object({
  /** The sentence containing the findings. */
  sentence: z.string().min(1).max(1000),
  /** Bounded prior conversation context, or empty. */
  context: z.string().max(600).default(''),
  findings: z.array(FindingInput).min(1).max(12),
  /** Set when the device wants a stronger model for a high-severity case. */
  escalate: z.boolean().default(false),
});

/**
 * Structured output. Using `generateObject` with a schema rather than parsing prose means the
 * model retries on a malformed shape at the SDK layer, so the client never has to defend
 * against half-valid JSON.
 */
const Verdict = z.object({
  verdicts: z.array(
    z.object({
      ruleId: z.string(),
      /** Whether this genuinely warrants warning the user. */
      confirmed: z.boolean(),
      /** Adjusted severity 0-3. 0 means suppress the warning entirely. */
      severity: z.number().int().min(0).max(3),
      /** True when the phrase is hyperbole, quotation, or otherwise not literal. */
      figurative: z.boolean(),
      /**
       * Which way the message points, when the local rules cannot tell.
       *
       * Added because it is the single most consequential thing the lexicon gets wrong and the
       * one it is structurally incapable of getting right. "kill yourself" and "i want to kill
       * myself" are different rules, but "I can't do this anymore" is the *same words* whether
       * someone is describing their own despair, quoting a friend who worries them, or
       * complaining about homework - and those three want a crisis card, a supportive nudge
       * about helping a friend, and silence respectively.
       *
       * The client uses this to decide whether a SELF_HARM finding routes to the crisis path at
       * all, which the local engine currently does for any self-harm hit at any severity.
       */
      direction: z
        .enum(['ABOUT_SELF', 'ABOUT_OTHER', 'REPORTING_SOMEONE_ELSE', 'NOT_APPLICABLE'])
        .default('NOT_APPLICABLE'),
      /**
       * How sure the model is, 0-1.
       *
       * Carried so the client can apply a floor before acting on a *downgrade*. An upgrade is
       * safe to take on any confidence - it only means warning someone - but a low-confidence
       * downgrade silently removes a warning the local rules raised, which is the one direction
       * where being wrong costs protection rather than annoyance.
       */
      confidence: z.number().min(0).max(1).default(1),
      /** One short sentence for the user, in plain language. Not a lecture. */
      reason: z.string().max(200),
    }),
  ),
  /** A safer rewrite of the sentence, when one is genuinely useful. */
  suggestedRewrite: z.string().max(1000).nullable(),
});

const SYSTEM_PROMPT = `You review text a keyboard has flagged as potentially unsafe, for a
safety product used mainly by children and teenagers. You are a second opinion on a local rule
engine that matches words without understanding context. The user is the person who typed the
message, and they will see your reason as a short warning above their keyboard.

## Your primary job

Distinguishing figurative language from real intent is the thing you are here for. Phrases like
"I want to die of embarrassment", "this heat makes me want to die", "I could kill myself for
forgetting that", or "I keep cutting myself shaving" are ordinary speech: mark them figurative
with severity 0. Warning on those trivialises real distress and trains the user to ignore every
future warning, which is how the genuine signal gets lost.

## Direction matters as much as content

For every finding, decide who the message is about, and set \`direction\`:

- ABOUT_SELF - the writer is describing their own situation or intent.
- ABOUT_OTHER - the writer is directing this at someone else (an insult, a threat, a request).
- REPORTING_SOMEONE_ELSE - the writer is worried about, quoting, or seeking help for another
  person. "my friend said she wants to die", "he keeps threatening me". This is NOT the writer
  in crisis and NOT the writer being abusive. It is often someone doing the right thing, and
  warning them as though they were the problem is the fastest way to stop them doing it.
- NOT_APPLICABLE - direction is irrelevant to this finding, e.g. most PII.

## When to confirm

Confirm a finding only when the text genuinely warrants interrupting the user:

- PII_DISCLOSURE: real personal details being shared with someone - address, phone, school,
  full name, credentials, payment details, one-time codes. Weigh who they appear to be talking
  to: a home address to a parent is not the same as one to a stranger. A one-time or
  verification code being passed on is always serious regardless of recipient, because the
  recipient asking for it is the attack.
- SEXUAL_SOLICITATION / IN_PERSON_MEETUP: requests for images, secrecy from parents, moving to
  another app, or attempts to isolate or meet a minor. Weigh the pattern rather than the
  phrase - "add me on telegram" alone is nothing; alongside secrecy or flattery it is a lot.
- SELF_HARM: genuine expressions of intent or distress, never hyperbole. Set direction
  carefully; a message reporting a friend's distress needs a supportive reason about helping
  them, not a crisis warning aimed at the writer.
- HARASSMENT / VIOLENCE_THREAT: real hostility toward a person, not banter between friends and
  not gaming trash talk. "I'll kill you in the lobby" is a game. Reclaimed slurs between
  friends, and quoted song lyrics, are usually not harassment.
- SUBSTANCE: actual drug or alcohol activity, not the words alone. "Diet coke", "lean back",
  "5 kms" and "my addy" are not drug references.

## Confidence

Set \`confidence\` honestly. Use a low value when the message is short, lacks context, or could
reasonably read either way. A downgrade you are unsure about is worse than no verdict, because
the client will act on it by removing a warning.

## Writing the reason

One short plain sentence addressed to the user. Never scold, never moralise, never mention
reporting or telling anyone - including when the device is supervised, which you are not told
about and must not assume. For SELF_HARM directed at the writer, be warm and supportive and do
not instruct them to delete anything. For a message reporting someone else's distress, be
supportive about that person.

Return a verdict for every ruleId you were given.`;

export async function POST(request: NextRequest) {
  const token = request.headers.get('x-install-token');
  if (!verifyInstallToken(token)) {
    return Response.json({ error: 'invalid_token' }, { status: 401 });
  }

  const limit = await checkRateLimit(token!);
  if (!limit.success) {
    // The client treats this like any other failure and keeps its local verdict, so a
    // throttled user loses refinement, never protection.
    return Response.json(
      { error: 'rate_limited', resetAt: limit.reset },
      { status: 429, headers: { 'Retry-After': String(limit.retryAfterSeconds) } },
    );
  }

  let body: z.infer<typeof RequestBody>;
  try {
    body = RequestBody.parse(await request.json());
  } catch {
    return Response.json({ error: 'invalid_body' }, { status: 400 });
  }

  const { model, id: modelId, via } = resolveModel(body.escalate ? 'escalation' : 'standard');

  try {
    const result = await generateObject({
      model,
      schema: Verdict,
      system: SYSTEM_PROMPT,
      prompt: buildPrompt(body),
      temperature: 0,
      maxRetries: 1,
      // TESTING VALUES — kept just below the device's own timeouts so the client gets a
      // structured 503 instead of a socket timeout. The original 1500/4000 predated any
      // measurement and aborted 8 of 13 benchmark calls; measured latency was 1148-3228ms
      // standard (Haiku 4.5) and 4263ms for one escalated Sonnet 5 call.
      abortSignal: AbortSignal.timeout(body.escalate ? 12000 : 6000),
    });

    return Response.json(
      { ...result.object, model: modelId, via, usage: result.usage },
      { headers: { 'Cache-Control': 'no-store' } },
    );
  } catch (error) {
    const timedOut = error instanceof Error && error.name === 'TimeoutError';
    // 503 is what the client treats as "keep the local verdict", so a missing key or a slow
    // upstream degrades to local-only behaviour rather than to a broken keyboard.
    console.error('verify failed', { via, modelId, error });
    return Response.json(
      { error: timedOut ? 'upstream_timeout' : 'upstream_error' },
      { status: 503 },
    );
  }
}

function buildPrompt(body: z.infer<typeof RequestBody>): string {
  const findings = body.findings
    .map((f) => `- ruleId=${f.ruleId} category=${f.category} localSeverity=${f.severity} text="${f.text}"`)
    .join('\n');

  return [
    body.context ? `Earlier in this conversation:\n${body.context}\n` : '',
    `Message being typed:\n${body.sentence}\n`,
    `Flagged by the local engine:\n${findings}`,
  ]
    .filter(Boolean)
    .join('\n');
}
