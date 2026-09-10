/**
 * Time windows and cache buckets for reports.
 *
 * Split out of `report.ts` with **no imports at all**, so it can be unit-tested by the node
 * test runner without dragging in the AI SDK and the Redis client. That is the practical
 * reason; the design reason is that these two functions are the only genuinely tricky
 * arithmetic in the report path, and everything else in that file is either I/O or a prompt.
 */

export type ReportPeriod = 'DAY' | 'WEEK';

export interface ReportWindow {
  from: number;
  to: number;
}

const DAY_MS = 24 * 60 * 60 * 1000;

/**
 * Rolling windows rather than calendar ones.
 *
 * A calendar day would be the obvious choice and it needs a timezone this server does not have:
 * the parent's phone knows one, the child's phone knows a possibly different one, and the store
 * knows neither. Guessing would put "today" an hour out for a family on holiday, and the report
 * would disagree with the activity list sitting above it.
 *
 * A rolling 24 hours is well-defined everywhere, and it is what "today" means to someone opening
 * the app at nine in the evening anyway.
 */
export function windowFor(period: ReportPeriod, now: number): ReportWindow {
  const span = period === 'WEEK' ? 7 * DAY_MS : DAY_MS;
  return { from: now - span, to: now };
}

/**
 * The cache bucket a window falls in.
 *
 * Coarser for the weekly report, because a week's aggregate barely moves in six hours and each
 * regeneration is a model call. Combined with the cache TTL this bounds spend at roughly 24
 * daily plus 4 weekly generations per child per day in the worst case, however often a parent
 * opens the screen.
 *
 * The bucket is part of the cache *key* rather than only a TTL, so a report can never be served
 * for the wrong window — a stale entry simply has a key nothing asks for any more.
 */
export function cacheBucket(period: ReportPeriod, now: number): string {
  const granularity = period === 'WEEK' ? 6 * 60 * 60 * 1000 : 60 * 60 * 1000;
  return String(Math.floor(now / granularity));
}
