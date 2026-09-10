'use client';

import { KEYBOARD_HEIGHT, OverlayWarning, SceneKeyboard, anchorOffset } from '../OverlayWarning';
import { MOCK_THREAD } from '../mock';
import type { RulePack } from '../detect';
import { useTyping } from '../useTyping';
import type { World } from '../world';

/**
 * A chat app that is not ours, with Keyguard floating over it.
 *
 * The short-field case: a one-line composer sitting directly on top of the keyboard, which is
 * the shape most messaging apps have and the one that produced the anchor bug — a warning
 * placed at the keyboard's top edge lands exactly on the field.
 *
 * Sending is wired to the real reporting path. A flagged message that is sent produces an event
 * with `SENT_INFERRED`; the same message with *Remove it* pressed first produces
 * `ABANDONED_DELETED` and counts as heeded. Both land in the child's queue and only reach the
 * parent on a sync, which is the whole point of having two phones on screen.
 */
export function ChatScene({
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
  const surface = useTyping(
    world,
    dispatch,
    pack,
    keyboardReported,
    'my address is 123 Main Street',
  );

  const COMPOSER_HEIGHT = 58;

  return (
    <div className="kg-scene">
      <div className="kg-scene-bar">
        <span className="kg-scene-avatar" />
        <span>Jess</span>
      </div>

      <div className="kg-scene-thread">
        {MOCK_THREAD.map((message, index) => (
          <div key={index} className={`kg-bubble kg-bubble--${message.from}`}>
            {message.text}
          </div>
        ))}
      </div>

      <div className="kg-composer">
        <input
          value={surface.draft}
          onChange={(event) => surface.type(event.target.value)}
          placeholder="Message"
        />
        <button className="kg-send" type="button" onClick={surface.send}>
          ➤
        </button>
      </div>

      <SceneKeyboard height={KEYBOARD_HEIGHT} />

      {surface.shaded ? <div className="kg-shade" style={{ height: KEYBOARD_HEIGHT }} /> : null}

      <OverlayWarning
        surface={surface}
        child={world.child}
        bottomOffset={anchorOffset(
          world.child.overlayPosition,
          keyboardReported,
          KEYBOARD_HEIGHT + COMPOSER_HEIGHT,
          screenHeight,
        )}
      />
    </div>
  );
}
