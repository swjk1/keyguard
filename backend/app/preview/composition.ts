import type { Finding, ScanResult } from './detect';
import type { ActivitySample, Outcome, SupervisionEvent } from './world';

/**
 * What one message-in-progress has been, so far.
 *
 * Mirrors the state `KeyguardAccessibilityService` keeps across a composition. The reason it is
 * kept at all: a message that was flagged and then edited down to something clean still produced
 * a warning worth reporting, and the *peak* is what gets reported rather than the final scan. A
 * child who types an address, sees the warning, and deletes it has produced exactly the outcome
 * the product wants — and reporting the clean final text would record nothing at all.
 */
export interface Composition {
  everFlagged: boolean;
  peak: Finding | null;
  /** The only deletion the app can be certain of: the one it performed itself. */
  removedByUser: boolean;
}

export const EMPTY_COMPOSITION: Composition = {
  everFlagged: false,
  peak: null,
  removedByUser: false,
};

/** Folds a fresh scan into the running composition. */
export function observe(composition: Composition, result: ScanResult): Composition {
  if (!result.top) return composition;
  const peakSeverity = composition.peak?.severity ?? -1;
  return {
    ...composition,
    everFlagged: true,
    peak: result.top.severity > peakSeverity ? result.top : composition.peak,
  };
}

/**
 * What to report when the message ends.
 *
 * The outcome guess goes the conservative way, exactly as the service does it. An overlay cannot
 * tell a send from a deletion — it sees a field with text and then a field without — so an
 * emptied field is `SENT_INFERRED` unless *Remove it* was pressed, which is the one deletion the
 * app performed itself and can be sure of.
 *
 * That under-reports `heeded`, which is the safe direction: a parent told "the warning worked"
 * when the message was actually sent is the failure that matters.
 */
export function finish(
  composition: Composition,
  switched: boolean,
): SupervisionEvent | null {
  if (!composition.everFlagged || !composition.peak) return null;

  const outcome: Outcome = switched
    ? 'ABANDONED_SWITCHED'
    : composition.removedByUser
      ? 'ABANDONED_DELETED'
      : 'SENT_INFERRED';

  return {
    at: Date.now(),
    category: composition.peak.category,
    severity: composition.peak.severity,
    outcome,
    heeded: outcome === 'ABANDONED_DELETED' && composition.peak.severity >= 2,
  };
}

/**
 * The derived tags a report is built from.
 *
 * A crude stand-in for `ThemeScanner`, which matches a 341-term vocabulary the browser does not
 * load. Enough to show a report with something in it; not enough to judge the tagging itself,
 * which is a job for the golden corpus rather than a design harness.
 */
const THEME_HINTS: [RegExp, string][] = [
  [/\b(homework|exam|school|revision|teacher|class|lesson|essay|coursework)\b/i, 'School'],
  [/\b(friend|mate|bestie|group chat|fell out|falling out)\b/i, 'Friends'],
  [/\b(mum|mom|dad|parents|brother|sister|family)\b/i, 'Family'],
  [/\b(fortnite|minecraft|valorant|roblox|xbox|playstation|game|lobby)\b/i, 'Gaming'],
  [/\b(football|training|match|practice|team|gym)\b/i, 'Sport'],
  [/\b(instagram|tiktok|snap|discord|post|story)\b/i, 'Social media'],
];

const EMOTION_HINTS: [RegExp, string][] = [
  [/\b(stressed|overwhelmed|too much|can'?t cope)\b/i, 'Stressed'],
  [/\b(sad|upset|crying|gutted|down)\b/i, 'Sad'],
  [/\b(nervous|anxious|worried|scared|panicking)\b/i, 'Anxious'],
  [/\b(angry|annoyed|furious|mad|fed up)\b/i, 'Angry'],
  [/\b(happy|great|excited|can'?t wait|buzzing)\b/i, 'Happy'],
  [/\b(tired|exhausted|knackered|didn'?t sleep)\b/i, 'Tired'],
];

export function summarize(text: string, flagged: boolean): ActivitySample {
  const match = (hints: [RegExp, string][], cap: number) =>
    hints
      .filter(([pattern]) => pattern.test(text))
      .map(([, label]) => label)
      .slice(0, cap);

  return {
    at: Date.now(),
    // Capped the way MessageSummary caps: "school, friends and football" is a report, six
    // themes is a tag cloud.
    themes: match(THEME_HINTS, 3),
    emotions: match(EMOTION_HINTS, 2),
    chars: text.length,
    flagged,
    text: text.slice(0, 500),
  };
}
