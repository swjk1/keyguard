'use client';

import { CATEGORY_LABEL } from './detect';
import { S } from './strings';
import type { TypingSurface } from './useTyping';
import type { ChildDevice } from './world';

/**
 * The floating warning itself, over whatever app is underneath.
 *
 * Shared by the chat and document scenes so the two cannot disagree about it — which matters
 * because the difference between them is exactly the thing being tested: the same warning has to
 * land correctly above a one-line composer and above a full-page editor, and any divergence here
 * would make that comparison meaningless.
 */
export function OverlayWarning({
  surface,
  child,
  bottomOffset,
}: {
  surface: TypingSurface;
  child: ChildDevice;
  /** Distance from the bottom of the screen, or null to pin to the top. */
  bottomOffset: number | null;
}) {
  if (!surface.showWarning) return null;

  const { result, crisis, shaded, dismissible } = surface;
  const tone = crisis ? 'crisis' : result.maxSeverity === 3 ? 'warn-high' : 'warn-medium';

  return (
    <div
      className={`kg-overlay kg-overlay--${tone}`}
      style={{
        ...(bottomOffset === null ? { top: 0 } : { bottom: bottomOffset }),
        opacity: child.overlayOpacity / 100,
      }}
    >
      <p className="kg-overlay-summary">{crisis ? S.crisis_summary : result.top?.message}</p>
      <p className="kg-overlay-detail">
        {crisis
          ? S.crisis_detail.replace('%1$s', 'Samaritans')
          : S.warning_detail.replace('%1$s', CATEGORY_LABEL[result.top?.category ?? ''] ?? '')}
      </p>

      {/* Driven by whether the shade is actually up, never by whether it was wanted. Claiming
          typing is paused while it is not would be the one straightforwardly dishonest message
          in the product. */}
      {shaded ? <p className="kg-overlay-paused">{S.overlay_typing_paused}</p> : null}

      <div className="kg-overlay-actions">
        {crisis ? (
          // No Ignore and no Remove here. One dismisses an offer of help, the other tells
          // someone to delete what they just said.
          <button type="button">{S.overlay_get_help}</button>
        ) : (
          <>
            <button type="button" onClick={surface.removeFlagged}>
              {S.overlay_remove}
            </button>
            {dismissible ? (
              <button type="button" onClick={surface.ignore}>
                {S.overlay_ignore}
              </button>
            ) : null}
          </>
        )}
      </div>
    </div>
  );
}

/** Somebody else's keyboard: Gboard's proportions, not Keyguard's. */
export function SceneKeyboard({ height }: { height: number }) {
  const rows = ['qwertyuiop', 'asdfghjkl', 'zxcvbnm'];
  return (
    <div className="kg-keyboard" style={{ height, background: '#1f2429' }}>
      {rows.map((row, index) => (
        <div className="kg-krow" key={row}>
          {index === 2 ? (
            <button className="kg-key kg-key--mod" type="button">
              ⇧
            </button>
          ) : null}
          {[...row].map((letter) => (
            <button className="kg-key" key={letter} type="button">
              {letter}
            </button>
          ))}
          {index === 2 ? (
            <button className="kg-key kg-key--mod" type="button">
              ⌫
            </button>
          ) : null}
        </div>
      ))}
      <div className="kg-krow">
        <button className="kg-key kg-key--mod" type="button">
          ?123
        </button>
        <button className="kg-key" type="button">
          ,
        </button>
        <button className="kg-key kg-key--space" type="button" />
        <button className="kg-key" type="button">
          .
        </button>
        <button className="kg-key kg-key--mod" type="button">
          ↵
        </button>
      </div>
    </div>
  );
}

export const KEYBOARD_HEIGHT = 232;

/**
 * Where the warning sits, mirroring `OverlayAnchor.placeWarning`.
 *
 * @param fieldTopFromBottom how far the field's own top edge is above the bottom of the screen,
 *   or null when the scene has no single field to clear. The field matters and not just the
 *   keyboard: anchoring to the keyboard's top edge lands the warning exactly on a chat composer,
 *   so the message asking someone to remove the flagged text covers the text.
 */
export function anchorOffset(
  position: ChildDevice['overlayPosition'],
  keyboardReported: boolean,
  fieldTopFromBottom: number | null,
  screenHeight: number,
): number | null {
  if (position === 'SCREEN_TOP') return null;
  // OverlayAnchor.FALLBACK_BOTTOM_MARGIN_DP when the platform will not report the keyboard.
  if (!keyboardReported) return 280;
  if (fieldTopFromBottom === null) return KEYBOARD_HEIGHT;
  // A field taller than half the screen is a full-page editor, not a composer; anchoring above
  // it would push the warning off the top, so the keyboard edge wins.
  if (fieldTopFromBottom - KEYBOARD_HEIGHT > screenHeight / 2) return KEYBOARD_HEIGHT;
  return Math.max(KEYBOARD_HEIGHT, fieldTopFromBottom);
}
