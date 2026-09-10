'use client';

import { useState } from 'react';

import { EMPTY_COMPOSITION, finish, observe, summarize, type Composition } from './composition';
import { scan, type RulePack, type ScanResult } from './detect';
import {
  blocksAtHigh,
  effectiveOverride,
  mayDismiss,
  queueEvent,
  queueSample,
  type World,
} from './world';

export interface TypingSurface {
  draft: string;
  /** Replaces the draft and folds the resulting scan into the running composition. */
  type: (next: string) => void;
  /** Finishes the message: queues the event and the sample, then clears the field. */
  send: () => void;
  /** *Remove it*: deletes the flagged span only, never the whole field. */
  removeFlagged: () => void;
  /** *Ignore*, if the policy permits it. */
  ignore: () => void;

  result: ScanResult;
  /** Whether a warning should be on screen at all. */
  showWarning: boolean;
  /** Whether the keyboard is covered, which is a stronger claim than "wanted to be". */
  shaded: boolean;
  dismissible: boolean;
  crisis: boolean;
}

/**
 * Everything that happens between a keystroke and a warning, shared by every typing scene.
 *
 * Both the chat app and the document editor need identical behaviour here — scan, decide, track
 * the composition, report on send — and they differ only in the chrome around the field. Writing
 * it twice would guarantee the two drift, and the whole value of having a second surface is that
 * it exercises the *same* logic against a different shape of field.
 */
export function useTyping(
  world: World,
  dispatch: (next: World) => void,
  pack: RulePack,
  keyboardReported: boolean,
  initial = '',
): TypingSurface {
  const [draft, setDraft] = useState(initial);
  const [composition, setComposition] = useState<Composition>(EMPTY_COMPOSITION);
  const [acknowledged, setAcknowledged] = useState(false);

  const result = scan(pack, draft);
  const severity = result.maxSeverity;
  const level = effectiveOverride(world.child);
  const dismissible = mayDismiss(level, severity);

  // An acknowledgement the policy never permitted counts for nothing, anywhere. Re-checking
  // rather than trusting the flag is what makes a tightening sync take effect immediately
  // instead of the child riding out the message on a withdrawn permission.
  const waived = acknowledged && dismissible;

  const crisis = result.requiresCrisisResponse;
  const showWarning =
    world.child.overlayEnabled && result.top !== null && severity > 0 && !waived;
  const shaded =
    showWarning && !crisis && severity === 3 && blocksAtHigh(world.child) && keyboardReported;

  const type = (next: string) => {
    setDraft(next);
    // Typing re-arms the warning, exactly as the keyboard's `dismissed` flag is re-armed by the
    // next keystroke.
    setAcknowledged(false);
    setComposition((current) => observe(current, scan(pack, next)));
  };

  const send = () => {
    if (!draft.trim()) return;
    const settled = observe(composition, result);
    const event = finish(settled, false);

    let next = world;
    if (event) next = queueEvent(next, event);
    next = queueSample(next, summarize(draft, settled.everFlagged));
    dispatch(next);

    setDraft('');
    setComposition(EMPTY_COMPOSITION);
    setAcknowledged(false);
  };

  const removeFlagged = () => {
    const top = result.top;
    if (!top) return;
    // Only the flagged span. Clearing the whole field to remove an address would eat the
    // ninety per cent of the message that was fine, and teach people to compose elsewhere.
    const cleaned = draft.slice(0, top.start) + draft.slice(top.end);
    setDraft(cleaned);
    setComposition((current) => ({ ...observe(current, result), removedByUser: true }));
  };

  const ignore = () => {
    if (!dismissible) return;
    setAcknowledged(true);
  };

  return {
    draft,
    type,
    send,
    removeFlagged,
    ignore,
    result,
    showWarning,
    shaded,
    dismissible,
    crisis,
  };
}
