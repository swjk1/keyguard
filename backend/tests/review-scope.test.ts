import assert from 'node:assert/strict';
import test from 'node:test';

import {
  EMOTION_NAMES,
  PolicySchema,
  SampleSchema,
  THEME_NAMES,
  scopePermitsSamples,
  scopePermitsText,
} from '../lib/family-contract.ts';
import { cacheBucket, windowFor } from '../lib/report-window.ts';

/**
 * The review scope is the setting that decides whether a child's messages leave their phone, so
 * the assertions here are about what each rung *permits*, not about whether the code runs.
 *
 * Note what these deliberately do not test: `appendSamples` stripping text, and the samples
 * route refusing a withdrawn scope. Both need a live Redis, and this suite runs without one -
 * the same reason the existing family tests cover the contract rather than the store. The
 * stripping is a one-line expression in `appendSamples` sitting directly under a comment saying
 * so, which is the best that can be done here; a store-backed integration test is the honest
 * follow-up and is named as one in the README.
 */

test('policy defaults preserve the pre-supervision behaviour', () => {
  // Pairing on its own must change nothing about how a keyboard behaves. If either of these
  // defaults moves, a family that pairs and touches nothing gets a device that acts differently
  // from the one they had yesterday.
  const parsed = PolicySchema.parse({
    version: 0,
    minIntensity: 'SUBTLE',
    blockAtHigh: true,
    aiVerification: 'CHILD_CHOICE',
    lockSettings: false,
  });
  assert.equal(parsed.overrideLevel, 'FULL');
  assert.equal(parsed.reviewScope, 'CONCERNING_ONLY');
  assert.equal(parsed.reportsEnabled, true);
});

test('a policy written before these fields existed still parses', () => {
  // Stored policies predate the new fields. Failing to parse would fall back to DEFAULT_POLICY
  // and silently reset a family's intensity floor as a side effect of an unrelated addition.
  const parsed = PolicySchema.safeParse({
    version: 7,
    minIntensity: 'INSISTENT',
    blockAtHigh: false,
    aiVerification: 'FORCED_OFF',
    lockSettings: true,
  });
  assert.equal(parsed.success, true);
  assert.equal(parsed.data?.minIntensity, 'INSISTENT');
  assert.equal(parsed.data?.version, 7);
});

test('only FULL_TEXT permits message text, and only CONCERNING_ONLY blocks samples', () => {
  assert.equal(scopePermitsText('CONCERNING_ONLY'), false);
  assert.equal(scopePermitsText('THEMES'), false);
  assert.equal(scopePermitsText('FULL_TEXT'), true);

  assert.equal(scopePermitsSamples('CONCERNING_ONLY'), false);
  assert.equal(scopePermitsSamples('THEMES'), true);
  assert.equal(scopePermitsSamples('FULL_TEXT'), true);
});

test('a sample without text is valid, which is what THEMES depends on', () => {
  const parsed = SampleSchema.safeParse({
    at: Date.now(),
    themes: ['SCHOOL', 'FRIENDSHIP'],
    emotions: ['ANXIOUS'],
    chars: 42,
    flagged: false,
    outcome: 'SENT',
  });
  assert.equal(parsed.success, true);
  assert.equal(parsed.data?.text, undefined);
});

test('sample text is capped so one message cannot become the largest object in the store', () => {
  const parsed = SampleSchema.safeParse({
    at: Date.now(),
    themes: [],
    emotions: [],
    chars: 5000,
    flagged: false,
    outcome: 'SENT',
    text: 'x'.repeat(501),
  });
  assert.equal(parsed.success, false);
});

test('unknown theme and emotion names are rejected at ingest', () => {
  // This is what keeps the duplicated vocabulary honest. A Kotlin enum value missing from the
  // TypeScript list fails here immediately, rather than being stored as an unknown string and
  // quietly breaking an aggregate months later.
  const parsed = SampleSchema.safeParse({
    at: Date.now(),
    themes: ['HOMEWORK_STRESS'],
    emotions: [],
    chars: 10,
    flagged: false,
    outcome: 'SENT',
  });
  assert.equal(parsed.success, false);
});

test('the reporting vocabularies match the device enums', () => {
  // Hand-maintained mirrors of Theme and Emotion in :detect. Asserted by count and by a
  // spot-check of the boundaries, so a one-sided addition is caught by the failing count rather
  // than discovered when a report silently drops a tag.
  assert.equal(THEME_NAMES.length, 17);
  assert.equal(EMOTION_NAMES.length, 9);
  assert.ok(THEME_NAMES.includes('CONFLICT'));
  assert.ok(THEME_NAMES.includes('SCHOOL'));
  assert.ok(EMOTION_NAMES.includes('LONELY'));
  assert.ok(EMOTION_NAMES.includes('HAPPY'));
});

test('report windows are rolling, so no timezone is required to define them', () => {
  const now = 1_700_000_000_000;
  const day = windowFor('DAY', now);
  const week = windowFor('WEEK', now);

  assert.equal(day.to, now);
  assert.equal(day.to - day.from, 24 * 60 * 60 * 1000);
  assert.equal(week.to - week.from, 7 * 24 * 60 * 60 * 1000);
  // The daily window is contained in the weekly one, which is what lets a parent read both
  // without the numbers appearing to contradict each other.
  assert.ok(week.from < day.from);
});

test('cache buckets bound how often a report can be regenerated', () => {
  const hour = 60 * 60 * 1000;
  // Aligned to a bucket boundary. An arbitrary timestamp sits mid-bucket, and then "an hour
  // later" straddles two buckets - which is true of real clocks too, and is why the assertion
  // is about the boundary rather than about any particular hour being one call.
  const now = Math.floor(1_700_000_000_000 / (6 * hour)) * (6 * hour);

  // Within the hour, a daily report reuses the same bucket - so a parent refreshing repeatedly
  // through an evening pays for one model call rather than one per tap.
  assert.equal(cacheBucket('DAY', now), cacheBucket('DAY', now + hour - 1));
  assert.notEqual(cacheBucket('DAY', now), cacheBucket('DAY', now + hour));

  // The weekly report is coarser still: a week's aggregate barely moves in six hours.
  assert.equal(cacheBucket('WEEK', now), cacheBucket('WEEK', now + 5 * hour));
  assert.notEqual(cacheBucket('WEEK', now), cacheBucket('WEEK', now + 6 * hour));
});
