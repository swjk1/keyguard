'use client';

import { useState } from 'react';

import { KEYBOARD_HEIGHT, OverlayWarning, SceneKeyboard, anchorOffset } from '../OverlayWarning';
import type { RulePack } from '../detect';
import { useTyping } from '../useTyping';
import type { World } from '../world';

/**
 * A document editor, with Keyguard floating over it.
 *
 * The second surface exists because a chat composer is not the only shape a text field comes in,
 * and the differences are exactly the ones most likely to break an overlay:
 *
 * - **The field is most of the screen**, so anchoring above it would push the warning off the
 *   top. `OverlayAnchor` falls back to the keyboard edge for precisely this case, and this scene
 *   is how that branch gets looked at rather than merely unit-tested.
 * - **The text is long and multi-line**, so a flagged span can be anywhere in it — including
 *   scrolled out of view, which is a genuine problem the chat case hides. A warning saying
 *   "remove the highlighted text" is unhelpful when the text is three paragraphs up.
 * - **Nothing is ever "sent".** A document is saved, closed, or just left. The composition ends
 *   without a send button, which is the case the service handles as
 *   `ABANDONED_SWITCHED` — and the one where an overlay genuinely cannot tell what happened.
 *
 * Typing here also exercises the review scope: under `THEMES` and `FULL_TEXT` a saved document
 * produces a sample, and under `FULL_TEXT` the text of it reaches the parent.
 */
export function DocsScene({
  world,
  dispatch,
  pack,
  keyboardReported,
  screenHeight,
}: {
  world: World;
  dispatch: (next: World) => void;
  pack: RulePack;
  keyboardReported: boolean;
  screenHeight: number;
}) {
  const [title, setTitle] = useState('History essay');
  const surface = useTyping(
    world,
    dispatch,
    pack,
    keyboardReported,
    'Notes for Friday.\n\nMeeting Sam after school to finish the project. ' +
      'His address is 14 Elm Row if anyone needs it.',
  );

  // The editor body runs from under the toolbar to the top of the keyboard, so its top edge is
  // far above the keyboard — the full-page case the anchor has to fall back on.
  const DOC_BODY_TOP_FROM_BOTTOM = screenHeight - 150;

  return (
    <div className="kg-scene kg-scene--docs">
      <div className="kg-docs-bar">
        <span className="kg-docs-back">←</span>
        <input
          className="kg-docs-title"
          value={title}
          onChange={(event) => setTitle(event.target.value)}
        />
        {/* Saving is how a document ends. There is no send, so the composition finishes the
            same way leaving the app would. */}
        <button className="kg-docs-save" type="button" onClick={surface.send}>
          Save
        </button>
      </div>

      <div className="kg-docs-tools">
        {['B', 'I', 'U', 'H1', '•', '1.'].map((tool) => (
          <span className="kg-docs-tool" key={tool}>
            {tool}
          </span>
        ))}
      </div>

      <textarea
        className="kg-docs-body"
        value={surface.draft}
        onChange={(event) => surface.type(event.target.value)}
        placeholder="Start writing…"
        spellCheck={false}
      />

      <SceneKeyboard height={KEYBOARD_HEIGHT} />

      {surface.shaded ? <div className="kg-shade" style={{ height: KEYBOARD_HEIGHT }} /> : null}

      <OverlayWarning
        surface={surface}
        child={world.child}
        bottomOffset={anchorOffset(
          world.child.overlayPosition,
          keyboardReported,
          DOC_BODY_TOP_FROM_BOTTOM,
          screenHeight,
        )}
      />
    </div>
  );
}
