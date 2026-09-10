import type { ReviewScope } from './world';

/**
 * The two things the harness still stands in for.
 *
 * Children, events and samples are real now — they come from the world, produced by actually
 * typing on the child phone. What remains here is the chat thread the overlay sits on, and the
 * report *narratives*, which in the real product are written server-side by a model from the
 * same data. Reproducing that in a browser would be inventing prose and presenting it as the
 * product's output, so the report screen labels it as a stand-in instead.
 *
 * Written to be *ordinary*, not dramatic. Mock data for a safety product tends to get filled
 * with worst cases, and a screen designed against that ends up optimised for a week that almost
 * never happens.
 */

export interface MockReport {
  summary: string;
  concerns: string[];
  interests: string[];
  themes: { label: string; count: number }[];
  emotions: { label: string; count: number }[];
  messageCount: number;
  flaggedCount: number;
}

/**
 * Reports differ by scope, and showing that is most of why this data exists.
 *
 * The `CONCERNING_ONLY` report is deliberately thin and slightly unsatisfying. That is the
 * honest shape of a summary built from warnings alone, and a parent looking at the three side by
 * side is exactly the comparison that should inform whether the middle rung is worth having.
 */
export const MOCK_REPORTS: Record<ReviewScope, MockReport> = {
  CONCERNING_ONLY: {
    summary:
      'Two warnings today, both about personal information, and both times the text was removed before sending. Nothing else was collected, so there is nothing more to say about the rest of the day.',
    concerns: [],
    interests: [],
    themes: [],
    emotions: [],
    messageCount: 2,
    flaggedCount: 2,
  },
  THEMES: {
    summary:
      'A fairly normal day. Most of the talk was about school and friends, with a run of messages in the evening that read as stressed — around the same time as mentions of an exam. One warning about sharing personal information, which was removed rather than sent.',
    concerns: ['Several stressed messages in the evening, alongside talk of an exam'],
    interests: ['Football', 'A game called Valorant', 'Music'],
    themes: [
      { label: 'School', count: 14 },
      { label: 'Friends', count: 9 },
      { label: 'Gaming', count: 6 },
      { label: 'Sport', count: 4 },
    ],
    emotions: [
      { label: 'Stressed', count: 7 },
      { label: 'Happy', count: 5 },
      { label: 'Tired', count: 3 },
    ],
    messageCount: 63,
    flaggedCount: 1,
  },
  FULL_TEXT: {
    summary:
      'Busy day, mostly about a group project that is not going well — a few messages suggest they feel they are doing more than their share. Lighter in the evening, plans to watch something with a friend at the weekend. One message shared a home address, which was removed after the warning.',
    concerns: [
      'Frustration about a group project, mentioned across several conversations',
      'One message shared a home address before being removed',
    ],
    interests: ['Football', 'A game called Valorant', 'Music', 'Photography'],
    themes: [
      { label: 'School', count: 21 },
      { label: 'Friends', count: 11 },
      { label: 'Gaming', count: 6 },
    ],
    emotions: [
      { label: 'Stressed', count: 9 },
      { label: 'Happy', count: 6 },
      { label: 'Angry', count: 2 },
    ],
    messageCount: 87,
    flaggedCount: 1,
  },
};

/** The fake conversation the overlay scene sits on top of. */
export const MOCK_THREAD = [
  { from: 'them' as const, text: 'hey! are you coming saturday?' },
  { from: 'me' as const, text: 'yeah think so, need to ask my mum' },
  { from: 'them' as const, text: 'cool — where do you live again? my dad can pick you up' },
];
