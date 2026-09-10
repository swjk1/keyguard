import { generateObject } from 'ai';
import { z } from 'zod';

import {
  EMOTION_NAMES,
  THEME_NAMES,
  type ActivitySample,
  type ReportPeriod,
  type ReviewScope,
} from './family-contract';
import { readEvents, readSamples, type StoredEvent, type StoredSample } from './family';
import { isModelConfigured, resolveModel } from './model';
import { cacheBucket, windowFor, type ReportWindow } from './report-window';
import { REPORT_CACHE_TTL_SECONDS, SAMPLE_HISTORY_LIMIT, keys, store } from './store';

/**
 * Daily and weekly summaries.
 *
 * The feature exists because the activity list does not answer the question parents actually
 * ask. A list of warnings tells them what went wrong, and the overwhelmingly common case is
 * that nothing did — so the screen says "no warnings" week after week, which is either
 * reassuring or useless depending on whether you believe it. What a parent wants to know is
 * closer to "is my kid alright", and that is a question about ordinary conversation, not about
 * incidents.
 *
 * ### The honesty constraint
 *
 * A summary is generated text about a real child, read by someone who will act on it. Two
 * failure modes matter far more than a dull report:
 *
 * 1. **Inventing a concern.** A parent told their child seems withdrawn will go and ask them
 *    about it. If that came from four messages and a lexical mood guess, the model has caused a
 *    conversation on no evidence. Everything below — the volume floors, the prompt's rules, the
 *    explicit sample counts handed to the model — exists to make this harder.
 * 2. **Laundering weak evidence into confident prose.** A theme tag is a word list match. A
 *    fluent paragraph built on tags reads far more authoritative than the tags deserve, which
 *    is why the report carries its counts alongside the narrative and states its own basis.
 *
 * The prompt is therefore written to permit "nothing much happened" as a *good* answer, and the
 * fallback path below produces exactly that without a model at all.
 */

export { cacheBucket, windowFor } from './report-window';
export type { ReportWindow } from './report-window';

/**
 * The report as it is stored and served.
 *
 * The counts sit alongside the narrative rather than being replaced by it, and `basis` is
 * carried rather than inferred. Both are honesty measures: a parent should be able to see what
 * a sentence was built from, and should be able to tell a summary written from tags apart from
 * one written from their child's actual messages. Mirrored by `FamilyReport` on the device.
 */
export interface GeneratedReport {
  period: ReportPeriod;
  from: number;
  to: number;
  childInstallId: string;
  childLabel: string;
  messageCount: number;
  flaggedCount: number;
  themes: { theme: string; count: number }[];
  emotions: { emotion: string; count: number }[];
  concerns: string[];
  interests: string[];
  summary: string;
  generatedAt: number;
  basis: ReviewScope;
  /** Which model wrote the narrative, or null when it was composed arithmetically. */
  model: string | null;
}

/** What the model is asked to produce. Structured, so a malformed shape retries at the SDK. */
const Narrative = z.object({
  /**
   * One short paragraph. The only part most parents will read, so it carries the whole answer
   * rather than introducing the sections below it.
   */
  summary: z.string().max(700),
  /**
   * Things worth a look. Explicitly allowed to be empty, and most weeks should be.
   */
  concerns: z.array(z.string().max(160)).max(4),
  /** What they seem to be enjoying. The half of the report that is not about risk. */
  interests: z.array(z.string().max(80)).max(6),
});

function tally<T extends string>(values: T[]): { value: T; count: number }[] {
  const counts = new Map<T, number>();
  for (const value of values) counts.set(value, (counts.get(value) ?? 0) + 1);
  return [...counts.entries()]
    .map(([value, count]) => ({ value, count }))
    .sort((a, b) => b.count - a.count || a.value.localeCompare(b.value));
}

/**
 * Builds a report, from cache when one is current.
 *
 * @param force skips the cache. Used by nothing on the parent screen deliberately — a refresh
 *   button that regenerated would let a parent spend the family's model budget by tapping.
 */
export async function buildReport(options: {
  familyId: string;
  childInstallId: string;
  childLabel: string;
  period: ReportPeriod;
  scope: ReviewScope;
  now?: number;
  force?: boolean;
}): Promise<GeneratedReport> {
  const { familyId, childInstallId, childLabel, period, scope } = options;
  const now = options.now ?? Date.now();
  const redis = store()!;
  const cacheKey = keys.report(familyId, childInstallId, period, cacheBucket(period, now));

  if (!options.force) {
    const cached = await redis.get<GeneratedReport>(cacheKey);
    // A cached report from a *different* scope is discarded rather than served. A parent who
    // just narrowed the scope must not be handed a summary written from data they have since
    // given up the right to see.
    if (cached && cached.basis === scope) return cached;
  }

  const window = windowFor(period, now);

  // Read generously and filter by time here rather than asking the store for a range: the
  // lists are newest-first and capped, so this is one round trip either way, and doing the
  // windowing in one place keeps events and samples on identical rules.
  const events = (await readEvents(familyId, childInstallId, 500)).filter(
    (event) => event.receivedAt >= window.from && event.receivedAt < window.to,
  );
  const samples = (await readSamples(familyId, childInstallId, SAMPLE_HISTORY_LIMIT)).filter(
    (sample) => sample.receivedAt >= window.from && sample.receivedAt < window.to,
  );

  const themes = tally(samples.flatMap((s) => s.themes)).map(({ value, count }) => ({
    theme: value,
    count,
  }));
  const emotions = tally(samples.flatMap((s) => s.emotions)).map(({ value, count }) => ({
    emotion: value,
    count,
  }));

  const base = {
    period,
    from: window.from,
    to: window.to,
    childInstallId,
    childLabel,
    // Under CONCERNING_ONLY there are no samples at all, so the message count is the number of
    // flagged messages rather than zero - reporting "0 messages, 3 flagged" would read as a bug.
    messageCount: samples.length > 0 ? samples.length : events.length,
    flaggedCount: events.length,
    themes,
    emotions,
    generatedAt: now,
    basis: scope,
  };

  const narrative = await composeNarrative({ scope, period, events, samples, themes, emotions });

  const report: GeneratedReport = { ...base, ...narrative };
  await redis.set(cacheKey, report, { ex: REPORT_CACHE_TTL_SECONDS });
  return report;
}

/**
 * Writes the prose, or composes it arithmetically when no model is available.
 *
 * The fallback is not a degraded mode to be embarrassed about — for a quiet week it produces a
 * *better* report than a model would, because "nothing notable happened" is the honest answer
 * and a model asked to write three sentences about it will find something to say.
 */
async function composeNarrative(input: {
  scope: ReviewScope;
  period: ReportPeriod;
  events: StoredEvent[];
  samples: StoredSample[];
  themes: { theme: string; count: number }[];
  emotions: { emotion: string; count: number }[];
}): Promise<{ summary: string; concerns: string[]; interests: string[]; model: string | null }> {
  const { scope, period, events, samples, themes, emotions } = input;

  // Below this there is nothing a summary could honestly be built from, and the volume floor
  // is the single most effective guard against invented concerns.
  const MIN_SAMPLES = 5;
  const thin = samples.length < MIN_SAMPLES && events.length === 0;
  if (thin || !isModelConfigured()) {
    return { ...fallbackNarrative(input), model: null };
  }

  const { model, id } = resolveModel('summary');

  try {
    const result = await generateObject({
      model,
      schema: Narrative,
      system: SYSTEM_PROMPT,
      prompt: buildPrompt({ scope, period, events, samples, themes, emotions }),
      temperature: 0.2,
      maxRetries: 1,
      // Far longer than the verify path's budget, and it can be: nobody is waiting on a
      // keystroke. A parent opening the reports tab will wait a few seconds for a paragraph.
      abortSignal: AbortSignal.timeout(30_000),
    });
    return { ...result.object, model: id };
  } catch (error) {
    // Same failure posture as `/api/verify`: degrade, never fail. A parent who cannot reach the
    // model still gets the counts and an arithmetic summary rather than an error screen.
    console.error('report generation failed', { period, scope, error });
    return { ...fallbackNarrative(input), model: null };
  }
}

const SYSTEM_PROMPT = `You write short, plain summaries for a parent about their child's
messaging activity, for a family safety product. A parent reads this and may act on it, so
being wrong has a cost: a parent told their child seems withdrawn will go and ask them about it.

Rules, in order of importance:

1. NEVER invent a concern. If the data does not support one, return an empty concerns list and
   say the period looked ordinary. "Nothing much stood out this week" is a good answer and the
   correct one most of the time. You are not being judged on finding something.
2. Match your confidence to the evidence you were given. You will be told how many messages the
   period covers. A handful of messages supports almost no conclusion; say so rather than
   hedging your way into an implication.
3. Where the data is derived tags rather than message text, you are reading a keyword-based
   guess at topic and mood, not the messages. Tags are noisy at the individual level and only
   mean something in aggregate. Never describe a single tagged message as if you read it.
4. Write about patterns, not incidents. One tagged "sad" message is noise. Fifteen across a
   week against a quiet baseline is worth a sentence.
5. Never quote the child's messages back, even when you were given them. A parent who wants to
   read the messages can; your job is the summary. Quoting also turns the report into a
   surveillance artefact the child would find far more invasive than the summary itself.
6. Be warm and concrete, never clinical and never alarmed. Write like a person who knows the
   family, not like a risk assessment.

Interests are what the child seems to enjoy - name them plainly ("football", "a game called
Valorant", "drawing"). Concerns are things worth a parent's attention, which is a much lower
bar than danger: "three anxious evenings in a row before what looked like a test" is a good
concern. Both lists may be empty.

The summary is one short paragraph, at most four sentences.`;

function periodLabel(period: ReportPeriod): string {
  return period === 'WEEK' ? 'the last 7 days' : 'the last 24 hours';
}

function buildPrompt(input: {
  scope: ReviewScope;
  period: ReportPeriod;
  events: StoredEvent[];
  samples: StoredSample[];
  themes: { theme: string; count: number }[];
  emotions: { emotion: string; count: number }[];
}): string {
  const { scope, period, events, samples, themes, emotions } = input;
  const lines: string[] = [];

  lines.push(`Period: ${periodLabel(period)}.`);
  lines.push(`Messages covered: ${samples.length}. Messages that raised a warning: ${events.length}.`);

  // Naming the basis to the model, not only to the parent. It changes what the model is
  // entitled to claim, and stating it in the prompt is more reliable than hoping the shape of
  // the data implies it.
  if (scope === 'CONCERNING_ONLY') {
    lines.push(
      'Data available: WARNINGS ONLY. No ordinary messages were collected, so you know nothing about what they talked about or how they seemed. Do not speculate about mood or topics.',
    );
  } else if (scope === 'THEMES') {
    lines.push(
      'Data available: DERIVED TAGS ONLY. You have topic and mood tags produced by a keyword matcher. You have NOT read any messages.',
    );
  } else {
    lines.push(
      'Data available: MESSAGE TEXT. A sample of what the child actually wrote is included below.',
    );
  }

  if (events.length > 0) {
    const byCategory = tally(events.map((e) => e.category));
    lines.push(
      `\nWarnings by kind:\n${byCategory
        .map(({ value, count }) => `- ${value}: ${count}`)
        .join('\n')}`,
    );
    const heeded = events.filter((e) => e.heeded).length;
    lines.push(`Of those, ${heeded} ended with the flagged text being removed rather than sent.`);
  }

  if (themes.length > 0) {
    lines.push(
      `\nTopic tags:\n${themes.map((t) => `- ${t.theme}: ${t.count}`).join('\n')}`,
    );
  }
  if (emotions.length > 0) {
    lines.push(
      `\nMood tags:\n${emotions.map((e) => `- ${e.emotion}: ${e.count}`).join('\n')}`,
    );
  }

  if (scope === 'FULL_TEXT') {
    // Bounded hard. This is the one place a child's actual words leave the store, and an
    // unbounded prompt would send a fortnight of them on every regeneration.
    const withText = samples.filter((s) => s.text).slice(0, 40);
    let budget = 4000;
    const excerpts: string[] = [];
    for (const sample of withText) {
      const text = sample.text!;
      if (text.length > budget) break;
      budget -= text.length;
      excerpts.push(`- ${text}`);
    }
    if (excerpts.length > 0) {
      lines.push(`\nA sample of ${excerpts.length} messages:\n${excerpts.join('\n')}`);
    }
  }

  return lines.join('\n');
}

/**
 * The arithmetic summary. No model, no invention, nothing that is not directly countable.
 */
function fallbackNarrative(input: {
  scope: ReviewScope;
  period: ReportPeriod;
  events: StoredEvent[];
  samples: StoredSample[];
  themes: { theme: string; count: number }[];
  emotions: { emotion: string; count: number }[];
}): { summary: string; concerns: string[]; interests: string[] } {
  const { period, events, samples, themes, emotions } = input;
  const label = periodLabel(period);

  if (samples.length === 0 && events.length === 0) {
    return {
      summary: `No activity recorded in ${label}.`,
      concerns: [],
      interests: [],
    };
  }

  const parts: string[] = [];
  parts.push(
    `${samples.length || events.length} message${samples.length === 1 ? '' : 's'} in ${label}.`,
  );

  if (events.length === 0) {
    parts.push('Nothing was flagged.');
  } else {
    const heeded = events.filter((e) => e.heeded).length;
    parts.push(
      `${events.length} raised a warning, and ${heeded} of those ended with the text being removed.`,
    );
  }

  const top = themes.slice(0, 3).map((t) => humanTheme(t.theme));
  if (top.length > 0) parts.push(`Most common topics: ${top.join(', ')}.`);

  return {
    summary: parts.join(' '),
    // Deliberately empty. A concern is a judgement, and this path exists precisely because
    // there is nothing available to make one with.
    concerns: [],
    interests: themes.slice(0, 4).map((t) => humanTheme(t.theme)),
  };
}

const THEME_LABELS: Record<string, string> = {
  SCHOOL: 'school',
  FRIENDSHIP: 'friends',
  FAMILY: 'family',
  ROMANCE: 'relationships',
  GAMING: 'gaming',
  SPORT: 'sport',
  MUSIC_AND_SHOWS: 'music and shows',
  ONLINE_LIFE: 'social media',
  MONEY: 'money',
  FOOD: 'food',
  HEALTH: 'health',
  APPEARANCE: 'appearance',
  FUTURE_PLANS: 'plans for the future',
  TRAVEL: 'travel',
  PETS: 'pets',
  CREATIVE: 'making things',
  CONFLICT: 'a falling-out',
};

function humanTheme(theme: string): string {
  return THEME_LABELS[theme] ?? theme.toLowerCase().replace(/_/g, ' ');
}

/** Exposed for the contract test, which checks the vocabularies have not drifted. */
export const REPORT_VOCABULARY = { THEME_NAMES, EMOTION_NAMES, THEME_LABELS };
