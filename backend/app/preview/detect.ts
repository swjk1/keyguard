/**
 * A cut-down port of the detection engine, for the harness only.
 *
 * **This is not the engine and must never be mistaken for it.** The real one is `:detect`, in
 * Kotlin, with 61 tests and a golden corpus. This exists so that typing into the fake chat in
 * the browser produces a real warning with real copy, which is what makes the overlay design
 * possible to judge at all — a mocked warning that always says the same thing tells you nothing
 * about how the layout behaves with a long message or a low-severity nudge.
 *
 * ### What it keeps
 *
 * - The real rule pack, read from `detect/src/main/resources/rules/pack-v0.json` at request
 *   time. Same terms, same severities, same messages.
 * - Word-boundary validation, so "class" does not trip a term like "ass".
 * - Suppressors, because without them the demo fires a crisis card at "cutting myself shaving"
 *   and would give a completely misleading impression of how often the thing interrupts.
 * - Co-occurrence escalation, since it is what produces most high-severity cases.
 *
 * ### What it drops, and why that is acceptable here
 *
 * - Normalization beyond lowercasing: no leetspeak folding, no run collapsing, no zero-width
 *   stripping, no separator-stripped evasion pass. Those matter for catching deliberate evasion
 *   and not at all for judging whether a warning is legible.
 * - The rolling conversation context, which spans messages.
 *
 * The consequence is that this under-detects relative to the real engine. That is the right
 * direction for a design harness: anything it flags, the real engine also flags.
 */

export interface Finding {
  start: number;
  end: number;
  category: string;
  severity: number;
  ruleId: string;
  message: string;
}

export interface ScanResult {
  findings: Finding[];
  maxSeverity: number;
  requiresCrisisResponse: boolean;
  top: Finding | null;
}

export interface TermRule {
  id: string;
  term: string;
  category: string;
  severity: number;
  message: string;
  allow_substring?: boolean;
}

export interface SuppressorRule {
  id: string;
  category: string;
  phrase: string;
  within_chars: number;
  only_rule_id?: string;
}

export interface CoOccurrenceRule {
  id: string;
  when_category: string;
  with_category: string;
  within_chars: number;
  result_severity: number;
  message: string;
}

export interface RulePack {
  terms: TermRule[];
  suppressors: SuppressorRule[];
  co_occurrence: CoOccurrenceRule[];
}

const WORD = /[a-z0-9_]/;

function isWordBounded(text: string, start: number, end: number): boolean {
  const before = start === 0 || !WORD.test(text[start - 1]);
  const after = end >= text.length || !WORD.test(text[end]);
  return before && after;
}

export function scan(pack: RulePack, buffer: string): ScanResult {
  if (!buffer.trim()) {
    return { findings: [], maxSeverity: 0, requiresCrisisResponse: false, top: null };
  }
  const haystack = buffer.toLowerCase();
  let findings: Finding[] = [];

  for (const rule of pack.terms) {
    const needle = rule.term.toLowerCase();
    let from = 0;
    for (;;) {
      const at = haystack.indexOf(needle, from);
      if (at < 0) break;
      const end = at + needle.length;
      if (rule.allow_substring || isWordBounded(haystack, at, end)) {
        findings.push({
          start: at,
          end,
          category: rule.category,
          severity: rule.severity,
          ruleId: rule.id,
          message: rule.message,
        });
      }
      from = at + 1;
    }
  }

  // Suppressors, before anything else looks at the findings. A figurative hit is not a finding
  // that gets downgraded; it never existed.
  findings = findings.filter((finding) => {
    for (const suppressor of pack.suppressors) {
      if (suppressor.category !== finding.category) continue;
      if (suppressor.only_rule_id && suppressor.only_rule_id !== finding.ruleId) continue;
      const from = Math.max(0, finding.start - suppressor.within_chars);
      const to = Math.min(haystack.length, finding.end + suppressor.within_chars);
      if (haystack.slice(from, to).includes(suppressor.phrase.toLowerCase())) return false;
    }
    return true;
  });

  // Co-occurrence escalation. Two categories near each other mean more than either alone.
  for (const rule of pack.co_occurrence) {
    for (const subject of findings) {
      if (subject.category !== rule.when_category) continue;
      const partner = findings.find(
        (other) =>
          other.category === rule.with_category &&
          Math.abs(other.start - subject.start) <= rule.within_chars,
      );
      if (!partner) continue;
      if (rule.result_severity > subject.severity) {
        subject.severity = rule.result_severity;
        subject.message = rule.message;
      }
    }
  }

  // Overlapping hits collapse to the most severe, so "my address is" inside a longer match does
  // not stack two warnings about the same words.
  findings.sort((a, b) => a.start - b.start || b.severity - a.severity);
  const merged: Finding[] = [];
  for (const finding of findings) {
    const previous = merged[merged.length - 1];
    if (previous && finding.start < previous.end) {
      if (finding.severity > previous.severity) merged[merged.length - 1] = finding;
      continue;
    }
    merged.push(finding);
  }

  const maxSeverity = merged.reduce((max, f) => Math.max(max, f.severity), 0);
  // Any self-harm finding at any severity, exactly as ContextScorer does it.
  const requiresCrisisResponse = merged.some((f) => f.category === 'SELF_HARM');
  const top =
    merged.reduce<Finding | null>(
      (best, f) => (best === null || f.severity > best.severity ? f : best),
      null,
    ) ?? null;

  return { findings: merged, maxSeverity, requiresCrisisResponse, top };
}

/** The category label a warning shows, mirroring the `category_*` strings. */
export const CATEGORY_LABEL: Record<string, string> = {
  PII_DISCLOSURE: 'Personal information',
  HARASSMENT: 'Hurtful language',
  SEXUAL_SOLICITATION: 'Unsafe request',
  SELF_HARM: 'Support',
  VIOLENCE_THREAT: 'Threat',
  IN_PERSON_MEETUP: 'Meeting in person',
  SUBSTANCE: 'Drugs or alcohol',
};
