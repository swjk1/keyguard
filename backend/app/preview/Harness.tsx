'use client';

import { useEffect, useState } from 'react';

import { ChatScene } from './screens/ChatScene';
import { ChildApp } from './screens/ChildApp';
import { DocsScene } from './screens/DocsScene';
import { ParentApp } from './screens/ParentApp';
import type { RulePack } from './detect';
import {
  freshWorld,
  issuePairingCode,
  policyOutOfDate,
  redeemCode,
  sync,
  writePolicy,
  type ChildDevice,
  type ParentDevice,
  type World,
} from './world';

/** What the child's phone is showing. The parent's only ever shows the Keyguard app. */
type ChildScene = 'app' | 'chat' | 'docs';

const SCREEN_HEIGHT = 852;

/**
 * The testing environment: two phones and the family between them.
 *
 * One phone was enough to judge a screen and useless for judging a flow. Pairing, policy
 * delivery and event upload are three-party interactions, and every interesting failure lives in
 * the lag between the parts — a rule changed on a child that has not synced, a device unpaired
 * remotely that does not know it, a queue sitting on a phone nobody has opened.
 *
 * So both phones are on screen at once over one shared [World], and the handshake between them
 * is real: the parent mints a code, you read it across and type it into the child, and from then
 * on the two are genuinely connected. Typing a flagged message in the chat or document app
 * queues an event on the child; syncing moves it; the parent's Children tab shows it arrive.
 */
export function Harness({ pack }: { pack: RulePack }) {
  const [world, setWorld] = useState<World>(freshWorld);
  const [childScene, setChildScene] = useState<ChildScene>('app');
  const [keyboardReported, setKeyboardReported] = useState(true);
  const [scale, setScale] = useState(1);

  // Two phones plus the control panel is about 1150px wide and 900 tall. Scale to whichever
  // dimension is tighter, never above 1 — blowing a 393px design up would make it look better
  // than it is, which defeats laying out against a real viewport.
  useEffect(() => {
    const fit = () =>
      setScale(Math.min(1, (window.innerHeight - 150) / 900, (window.innerWidth - 380) / 850));
    fit();
    window.addEventListener('resize', fit);
    return () => window.removeEventListener('resize', fit);
  }, []);

  const patchChild = (next: Partial<ChildDevice>) =>
    setWorld((current) => ({ ...current, child: { ...current.child, ...next } }));
  const patchParent = (next: Partial<ParentDevice>) =>
    setWorld((current) => ({ ...current, parent: { ...current.parent, ...next } }));

  return (
    <div className="kg-harness" data-theme={world.theme}>
      <Controls
        world={world}
        setWorld={setWorld}
        patchChild={patchChild}
        keyboardReported={keyboardReported}
        setKeyboardReported={setKeyboardReported}
        childScene={childScene}
        setChildScene={setChildScene}
      />

      <div className="kg-stage">
        <Device
          name="Child phone"
          scale={scale}
          theme={world.theme}
          status={<ChildStatus world={world} />}
          tabs={
            <div className="kg-device-tabs">
              {(
                [
                  ['app', 'Keyguard'],
                  ['chat', 'Chat app'],
                  ['docs', 'Documents'],
                ] as [ChildScene, string][]
              ).map(([id, label]) => (
                <button
                  key={id}
                  data-on={childScene === id}
                  onClick={() => setChildScene(id)}
                  type="button"
                >
                  {label}
                </button>
              ))}
            </div>
          }
        >
          {childScene === 'app' ? (
            <ChildApp world={world} patchChild={patchChild} dispatch={setWorld} />
          ) : null}
          {childScene === 'chat' ? (
            <ChatScene
              world={world}
              dispatch={setWorld}
              pack={pack}
              keyboardReported={keyboardReported}
              screenHeight={SCREEN_HEIGHT}
            />
          ) : null}
          {childScene === 'docs' ? (
            <DocsScene
              world={world}
              dispatch={setWorld}
              pack={pack}
              keyboardReported={keyboardReported}
              screenHeight={SCREEN_HEIGHT}
            />
          ) : null}
        </Device>

        <Device
          name="Parent phone"
          scale={scale}
          theme={world.theme}
          status={<ParentStatus world={world} />}
        >
          <ParentApp world={world} dispatch={setWorld} patchParent={patchParent} />
        </Device>
      </div>
    </div>
  );
}

function Device({
  name,
  tabs,
  status,
  scale,
  theme,
  children,
}: {
  name: string;
  tabs?: React.ReactNode;
  status: React.ReactNode;
  scale: number;
  theme: string;
  children: React.ReactNode;
}) {
  return (
    <div className="kg-phone-wrap" style={{ ['--phone-scale' as string]: scale }}>
      <p className="kg-device-name">{name}</p>
      {/* A placeholder keeps the two frames aligned when only one device has scene tabs.
          Without it the parent phone floats a row higher than the child and the pair reads as
          a rendering accident rather than a comparison. */}
      {tabs ?? <div className="kg-device-tabs kg-device-tabs--spacer" />}
      <div className="kg-phone" data-theme={theme}>
        <div className="kg-statusbar">
          <span>9:41</span>
          <span>▮▮▮ ▯</span>
        </div>
        {children}
      </div>
      <div className="kg-device-state">{status}</div>
    </div>
  );
}

/**
 * What the child device is enforcing, and what it is holding.
 *
 * Under the phone rather than inside it, because none of this is UI the product has — it is
 * state a developer needs and a user must never be shown.
 */
function ChildStatus({ world }: { world: World }) {
  const child = world.child;
  if (!child.supervised) return <span>Not paired · enforcing its own settings</span>;
  return (
    <span>
      Paired · enforcing <b>{child.cachedPolicy.reviewScope}</b> /{' '}
      <b>{child.cachedPolicy.overrideLevel}</b> · {child.queuedEvents.length} event
      {child.queuedEvents.length === 1 ? '' : 's'} queued
      {policyOutOfDate(world) ? (
        <>
          {' · '}
          <span className="kg-stale">policy out of date</span>
        </>
      ) : null}
    </span>
  );
}

function ParentStatus({ world }: { world: World }) {
  if (!world.parent.signedIn) return <span>Signed out</span>;
  const received = world.family.children.reduce((sum, child) => sum + child.events.length, 0);
  const count = world.family.children.length;
  return (
    <span>
      Policy v{world.family.policy.version} · {count} child{count === 1 ? '' : 'ren'} ·{' '}
      {received} event{received === 1 ? '' : 's'} received
    </span>
  );
}

// ---------------------------------------------------------------- controls

function Controls({
  world,
  setWorld,
  patchChild,
  keyboardReported,
  setKeyboardReported,
  childScene,
  setChildScene,
}: {
  world: World;
  setWorld: (next: World) => void;
  patchChild: (next: Partial<ChildDevice>) => void;
  keyboardReported: boolean;
  setKeyboardReported: (next: boolean) => void;
  childScene: ChildScene;
  setChildScene: (next: ChildScene) => void;
}) {
  return (
    <aside className="kg-controls">
      <h1>Keyguard preview</h1>
      <p className="kg-sub">
        Two phones and the family between them. Pair them for real: get a code on the parent, read
        it across, type it into the child. Changes here are the design source — carry them back to{' '}
        <code>res/values</code> and <code>ui/</code> when Android catches up.
      </p>

      <Group label="Child phone shows">
        <Seg
          options={[
            ['app', 'Keyguard'],
            ['chat', 'Chat'],
            ['docs', 'Docs'],
          ]}
          value={childScene}
          onChange={(next) => setChildScene(next as ChildScene)}
        />
      </Group>

      <Group label="Shortcuts">
        <Seg
          options={[
            ['reset', 'Reset both'],
            ['ready', 'Child ready'],
            ['paired', 'Pair now'],
          ]}
          value=""
          onChange={(action) => {
            if (action === 'reset') {
              setWorld(freshWorld());
              // Scroll position lives in the DOM, not in the world, so a reset that only
              // replaced the state left both phones part-way down whichever screen was open.
              document.querySelectorAll('.kg-body').forEach((el) => {
                el.scrollTop = 0;
              });
              return;
            }

            const ready: World = {
              ...world,
              child: {
                ...world.child,
                disclosureAccepted: true,
                accessibilityGranted: true,
                overlayGranted: true,
              },
            };
            if (action === 'ready') return setWorld(ready);

            // Skips reading the code across, for when the handshake is not what is being tested.
            const withCode = issuePairingCode({
              ...ready,
              parent: { ...ready.parent, signedIn: true },
            });
            setWorld(redeemCode(withCode, withCode.family.pairingCode!, world.child.label)!);
          }}
        />
        <Seg
          options={[
            ['sync', 'Sync child'],
            ['strict', 'Strict rules'],
            ['full', 'Full review'],
          ]}
          value=""
          onChange={(action) => {
            if (action === 'sync') return setWorld(sync(world));
            if (action === 'strict') {
              return setWorld(
                writePolicy(world, {
                  ...world.family.policy,
                  overrideLevel: 'NONE',
                  minIntensity: 'INSISTENT',
                  lockSettings: true,
                }),
              );
            }
            return setWorld(writePolicy(world, { ...world.family.policy, reviewScope: 'FULL_TEXT' }));
          }}
        />
        <p className="kg-note" style={{ borderTop: 0, marginTop: 4, paddingTop: 0 }}>
          Rule changes land on the server. The child keeps enforcing its cached copy until you
          sync — the real behaviour, and why it is a separate button.
        </p>
      </Group>

      <Group label="Theme">
        <Seg
          options={[
            ['light', 'Light'],
            ['dark', 'Dark'],
          ]}
          value={world.theme}
          onChange={(next) => setWorld({ ...world, theme: next as 'light' | 'dark' })}
        />
      </Group>

      <Group label="Child permissions">
        <Check
          label="Disclosure accepted"
          checked={world.child.disclosureAccepted}
          onChange={(v) => patchChild({ disclosureAccepted: v })}
        />
        <Check
          label="Accessibility granted"
          checked={world.child.accessibilityGranted}
          onChange={(v) => patchChild({ accessibilityGranted: v })}
        />
        <Check
          label="Draw-over granted"
          checked={world.child.overlayGranted}
          onChange={(v) => patchChild({ overlayGranted: v })}
        />
        <Check
          label="Keyguard keyboard in use"
          checked={world.child.keyboardEnabled && world.child.keyboardSelected}
          onChange={(v) => patchChild({ keyboardEnabled: v, keyboardSelected: v })}
        />
      </Group>

      <Group label="Overlay">
        <Seg
          options={[
            ['ABOVE_KEYBOARD', 'Above keys'],
            ['SCREEN_TOP', 'Screen top'],
          ]}
          value={world.child.overlayPosition}
          onChange={(next) =>
            patchChild({ overlayPosition: next as ChildDevice['overlayPosition'] })
          }
        />
        {/* The fallback nobody can trigger deliberately on a device, and which decides whether
            the block is enforceable at all. */}
        <Check
          label="Keyboard reports its window"
          checked={keyboardReported}
          onChange={setKeyboardReported}
        />
        <Check
          label="Warnings enabled"
          checked={world.child.overlayEnabled}
          onChange={(v) => patchChild({ overlayEnabled: v })}
        />
      </Group>

      <p className="kg-note">
        The chat and document apps run the real rule pack — 210 terms with the suppressors and
        co-occurrence rules — so typing produces genuine warnings. Sending a flagged message
        queues a real event on the child; sync it to watch it arrive on the parent.
      </p>
    </aside>
  );
}

function Group({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="kg-control-group">
      <label className="kg-group-label">{label}</label>
      {children}
    </div>
  );
}

function Seg({
  options,
  value,
  onChange,
}: {
  options: [string, string][];
  value: string;
  onChange: (next: string) => void;
}) {
  return (
    <div className="kg-seg" style={{ marginBottom: 6 }}>
      {options.map(([id, label]) => (
        <button key={id} data-on={value === id} onClick={() => onChange(id)} type="button">
          {label}
        </button>
      ))}
    </div>
  );
}

function Check({
  label,
  checked,
  onChange,
}: {
  label: string;
  checked: boolean;
  onChange: (next: boolean) => void;
}) {
  return (
    <label className="kg-toggle">
      <input
        type="checkbox"
        checked={checked}
        onChange={(event) => onChange(event.target.checked)}
        style={{ accentColor: 'var(--accent)' }}
      />
      {label}
    </label>
  );
}
