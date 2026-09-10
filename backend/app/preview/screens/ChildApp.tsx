'use client';

import { useState } from 'react';

import { Icon, type IconName } from '../Icons';
import { Body, Btn, Card, Field, Hint, Radio, SwitchRow, Title } from '../ui';
import { S, format } from '../strings';
import {
  aiLocked,
  blocksAtHigh,
  effectiveIntensity,
  effectiveOverride,
  effectiveScope,
  policyOutOfDate,
  protectionStatus,
  redeemCode,
  sync,
  type ChildDevice,
  type World,
} from '../world';

type Tab = 'protection' | 'appearance' | 'family' | 'privacy';

/**
 * The message the sample text produces.
 *
 * Lives in the rule pack rather than `strings.xml` — warning copy is data, so retuning it ships
 * over the air without an app release — so it is quoted here rather than read from `S`.
 */
const PREVIEW_MESSAGE = "You're about to share your home address.";

/**
 * The child app: four tabs over one shell, mirroring `SetupActivity` and `ui/child/`.
 *
 * Reads `world.child.cachedPolicy` throughout, never `world.family.policy`. That distinction is
 * the reason the harness has two phones: the child enforces what it last synced, so a rule the
 * parent changed a minute ago genuinely has not arrived yet, and every screen here should look
 * the way it would on a phone in that state.
 */
export function ChildApp({
  world,
  patchChild,
  dispatch,
}: {
  world: World;
  patchChild: (next: Partial<ChildDevice>) => void;
  dispatch: (next: World) => void;
}) {
  const [tab, setTab] = useState<Tab>('protection');
  const child = world.child;

  const tabs: { id: Tab; label: string; icon: IconName }[] = [
    { id: 'protection', label: S.nav_protection, icon: 'protection' },
    { id: 'appearance', label: S.nav_appearance, icon: 'appearance' },
    { id: 'family', label: S.nav_family, icon: 'family' },
    { id: 'privacy', label: S.nav_privacy, icon: 'privacy' },
  ];

  return (
    <>
      <div className="kg-toolbar">{tabs.find((t) => t.id === tab)?.label}</div>
      <div className="kg-body">
        {tab === 'protection' ? (
          <Protection world={world} patchChild={patchChild} goTo={setTab} />
        ) : null}
        {tab === 'appearance' ? <Appearance world={world} patchChild={patchChild} /> : null}
        {tab === 'family' ? <Family world={world} dispatch={dispatch} /> : null}
        {tab === 'privacy' ? <Privacy world={world} patchChild={patchChild} /> : null}
      </div>
      <nav className="kg-bottomnav">
        {tabs.map((item) => (
          <button
            key={item.id}
            data-on={tab === item.id}
            onClick={() => setTab(item.id)}
            type="button"
          >
            <Icon name={item.icon} />
            {item.label}
          </button>
        ))}
      </nav>
    </>
  );
}

// ------------------------------------------------------------------ Protection

function Protection({
  world,
  patchChild,
  goTo,
}: {
  world: World;
  patchChild: (next: Partial<ChildDevice>) => void;
  goTo: (tab: Tab) => void;
}) {
  const child = world.child;
  const status = protectionStatus(child);
  const ok = status === 'on';

  const [title, body] = {
    on: [S.protection_on_title, S.protection_on_body],
    'partial-missing-draw': [S.protection_partial_title, S.overlay_status_missing_draw],
    'partial-missing-a11y': [S.protection_partial_title, S.overlay_status_missing_accessibility],
    'partial-keyboard': [S.protection_partial_title, S.protection_partial_body],
    off: [S.protection_off_title, S.protection_off_body],
  }[status];

  const intensity = effectiveIntensity(child);
  const locked = child.supervised && child.cachedPolicy.lockSettings;
  const raised = child.supervised && intensity !== child.intensity;

  return (
    <>
      <Card
        style={{
          background: ok ? 'var(--status-ok-surface)' : 'var(--status-pending-surface)',
          borderColor: 'transparent',
        }}
      >
        <p
          className="kg-hero-title"
          style={{ color: ok ? 'var(--status-ok)' : 'var(--status-pending)' }}
        >
          {title}
        </p>
        <Body>{body}</Body>
      </Card>

      <Card>
        <Title>{S.protection_steps_title}</Title>
        <Body>{S.overlay_setup_hint}</Body>
        <div style={{ marginTop: 8 }}>
          <Step
            done={child.disclosureAccepted}
            title={S.protection_step_disclosure}
            hint={S.protection_step_disclosure_hint}
            action={S.protection_step_disclosure_action}
            onAction={() => goTo('privacy')}
          />
          <Step
            done={child.accessibilityGranted}
            title={S.overlay_grant_accessibility}
            hint={S.overlay_grant_accessibility_hint}
            action={S.protection_step_open_settings}
            onAction={() => patchChild({ accessibilityGranted: true })}
          />
          <Step
            done={child.overlayGranted}
            title={S.overlay_grant_draw}
            hint={S.overlay_grant_draw_hint}
            action={S.protection_step_open_settings}
            onAction={() => patchChild({ overlayGranted: true })}
          />
        </div>
      </Card>

      <Card>
        <Title>{S.setup_intensity_heading}</Title>
        <Body>{S.protection_intensity_hint}</Body>
        <div style={{ marginTop: 8 }}>
          {(['SUBTLE', 'STANDARD', 'INSISTENT'] as const).map((value) => (
            <Radio
              key={value}
              label={
                {
                  SUBTLE: S.setup_intensity_subtle,
                  STANDARD: S.setup_intensity_standard,
                  INSISTENT: S.setup_intensity_insistent,
                }[value]
              }
              checked={intensity === value}
              disabled={locked}
              onChange={() => patchChild({ intensity: value })}
            />
          ))}
        </div>
        {locked || raised ? (
          <p className="kg-hint" style={{ color: 'var(--accent)' }}>
            {S.supervision_locked_by_parent}
          </p>
        ) : null}
      </Card>

      <Card>
        <Title>{S.protection_keyboard_title}</Title>
        <Body>{S.protection_keyboard_hint}</Body>
        <p className="kg-label" style={{ marginTop: 12 }}>
          {child.keyboardEnabled ? S.setup_status_enabled : S.setup_status_disabled}
        </p>
        <Btn
          variant="tonal"
          disabled={!child.disclosureAccepted}
          onClick={() => patchChild({ keyboardEnabled: true })}
        >
          {S.setup_enable_button}
        </Btn>
        <Btn
          variant="text"
          disabled={!child.keyboardEnabled}
          onClick={() => patchChild({ keyboardSelected: true })}
        >
          {S.setup_switch_button}
        </Btn>
      </Card>
    </>
  );
}

function Step({
  done,
  title,
  hint,
  action,
  onAction,
}: {
  done: boolean;
  title: string;
  hint: string;
  action: string;
  onAction: () => void;
}) {
  return (
    <div className={`kg-step${done ? ' kg-step--done' : ''}`}>
      <span
        className="kg-step-icon"
        style={{ color: done ? 'var(--status-ok)' : 'var(--on-surface-muted)' }}
      >
        <Icon name={done ? 'check' : 'pending'} />
      </span>
      <div style={{ flex: 1 }}>
        <p className="kg-step-title">{title}</p>
        {done ? (
          <p className="kg-hint">{S.protection_step_done}</p>
        ) : (
          <>
            <p className="kg-hint">{hint}</p>
            <button
              className="kg-btn kg-btn--outline"
              style={{ marginTop: 6 }}
              onClick={onAction}
              type="button"
            >
              {action}
            </button>
          </>
        )}
      </div>
    </div>
  );
}

// ------------------------------------------------------------------ Look

function Appearance({
  world,
  patchChild,
}: {
  world: World;
  patchChild: (next: Partial<ChildDevice>) => void;
}) {
  const child = world.child;
  const keyboardPath = child.keyboardEnabled && child.keyboardSelected;

  return (
    <>
      <Card>
        <Title>{S.overlay_position_heading}</Title>
        <Body>{S.appearance_position_hint}</Body>
        <div style={{ marginTop: 8 }}>
          <Radio
            label={S.overlay_position_above}
            checked={child.overlayPosition === 'ABOVE_KEYBOARD'}
            onChange={() => patchChild({ overlayPosition: 'ABOVE_KEYBOARD' })}
          />
          <Radio
            label={S.overlay_position_top}
            checked={child.overlayPosition === 'SCREEN_TOP'}
            onChange={() => patchChild({ overlayPosition: 'SCREEN_TOP' })}
          />
        </div>
        <p className="kg-label" style={{ marginTop: 12 }}>
          {format(S.appearance_opacity_value, S.overlay_opacity, child.overlayOpacity)}
        </p>
        <input
          className="kg-slider"
          type="range"
          min={40}
          max={100}
          value={child.overlayOpacity}
          onChange={(event) => patchChild({ overlayOpacity: Number(event.target.value) })}
        />
        <SwitchRow
          label={S.overlay_enabled_toggle}
          checked={child.overlayEnabled}
          onChange={(next) => patchChild({ overlayEnabled: next })}
        />
      </Card>

      <Card>
        <Title>{S.appearance_preview_heading}</Title>
        <Body>{S.appearance_preview_hint}</Body>
        <PreviewSurface world={world} keyboardPath={keyboardPath} />
      </Card>

      {keyboardPath ? (
        <Card>
          <Title>{S.appearance_keyboard_heading}</Title>
          <Body>{S.appearance_keyboard_hint}</Body>
          <SwitchRow
            label={S.appearance_autocorrect}
            checked={child.autocorrect}
            onChange={(next) => patchChild({ autocorrect: next })}
          />
          <SwitchRow
            label={S.appearance_haptics}
            checked={child.haptics}
            onChange={(next) => patchChild({ haptics: next })}
          />
        </Card>
      ) : null}

      <Btn variant="text">{S.appearance_reset}</Btn>
    </>
  );
}

/**
 * The live preview, showing whichever warning surface this device will actually use.
 *
 * The original principle was that the preview is assembled from the real components so it cannot
 * drift from what gets drawn. Showing the keyboard's strip to an overlay user would quietly
 * break exactly that, so the surface follows the path.
 */
function PreviewSurface({ world, keyboardPath }: { world: World; keyboardPath: boolean }) {
  const child = world.child;
  const blocking = blocksAtHigh(child);
  const dismissible = effectiveOverride(child) !== 'NONE';

  if (!keyboardPath) {
    return (
      <div style={{ marginTop: 12, borderRadius: 8, overflow: 'hidden' }}>
        <div
          className="kg-overlay kg-overlay--warn-high"
          style={{ position: 'static', opacity: child.overlayOpacity / 100 }}
        >
          <p className="kg-overlay-summary">{PREVIEW_MESSAGE}</p>
          <p className="kg-overlay-detail">{S.appearance_preview_detail}</p>
          <div className="kg-overlay-actions">
            <button type="button">{S.overlay_remove}</button>
            {dismissible ? <button type="button">{S.overlay_ignore}</button> : null}
          </div>
        </div>
      </div>
    );
  }

  return (
    <div style={{ marginTop: 12, borderRadius: 8, overflow: 'hidden' }}>
      <div className="kg-strip">
        <p style={{ color: 'var(--warn-high)', fontWeight: 700, fontSize: 14, margin: 0 }}>
          {PREVIEW_MESSAGE}
        </p>
        <p style={{ fontSize: 14, margin: '5px 0 0' }}>
          my address is{' '}
          <span style={{ background: 'var(--warn-high)', color: '#fff', fontWeight: 700 }}>
            123 Main Street
          </span>
        </p>
        {blocking ? (
          <p style={{ color: 'var(--warn-high)', fontWeight: 700, fontSize: 13, margin: '5px 0 0' }}>
            {dismissible ? S.warning_blocked : S.warning_blocked_locked}
          </p>
        ) : null}
      </div>
      <MiniKeyboard />
    </div>
  );
}

function MiniKeyboard() {
  const rows = ['qwertyuiop', 'asdfghjkl', 'zxcvbnm'];
  return (
    <div className="kg-keyboard">
      {rows.map((row) => (
        <div className="kg-krow" key={row}>
          {[...row].map((letter) => (
            <button className="kg-key" key={letter} type="button">
              {letter}
            </button>
          ))}
        </div>
      ))}
      <div className="kg-krow">
        <button className="kg-key kg-key--mod" type="button">
          ?123
        </button>
        <button className="kg-key kg-key--space" type="button" />
        <button className="kg-key kg-key--mod" type="button">
          ↵
        </button>
      </div>
    </div>
  );
}

// ------------------------------------------------------------------ Family

function Family({ world, dispatch }: { world: World; dispatch: (next: World) => void }) {
  const child = world.child;
  const [code, setCode] = useState('');
  const [label, setLabel] = useState(child.label);
  const [error, setError] = useState<string | null>(null);

  if (!child.supervised) {
    return (
      <Card>
        <Title>{S.supervision_heading}</Title>
        <Body>{S.supervision_unpaired_hint}</Body>
        <Field placeholder={S.supervision_label_hint} value={label} onChange={setLabel} />
        <Field placeholder={S.supervision_code_hint} value={code} onChange={setCode} />
        <Btn
          onClick={() => {
            const next = redeemCode(world, code, label.trim() || 'Phone');
            if (!next) {
              // A wrong code and an expired one are answered identically by the server, and a
              // dead network is indistinguishable from either at this layer. One message that
              // names both plausible causes beats three that guess.
              setError(S.supervision_pair_failed);
              return;
            }
            setError(null);
            setCode('');
            dispatch(next);
          }}
        >
          {S.supervision_pair_button}
        </Btn>
        {error ? <Body>{error}</Body> : null}
      </Card>
    );
  }

  const scope = effectiveScope(child);
  const scopeLine = {
    CONCERNING_ONLY: S.supervision_scope_concerning,
    THEMES: S.supervision_scope_themes,
    FULL_TEXT: S.supervision_scope_full,
  }[scope];

  const queued = child.queuedEvents.length;

  return (
    <Card>
      <p className="kg-title" style={{ color: 'var(--accent)' }}>
        {S.supervision_active_title}
      </p>
      {/* First and bold: it is the fact that changes, and the one a child most needs. */}
      <p className="kg-scope-line">{scopeLine}</p>
      <p className="kg-body-text" style={{ whiteSpace: 'pre-line' }}>
        {S.supervision_active_body}
      </p>
      <Hint>
        {child.lastSyncAt
          ? format(S.supervision_last_sync, relative(child.lastSyncAt))
          : S.supervision_never_synced}
        {queued > 0 ? ` · ${format(S.supervision_queued, queued)}` : ''}
      </Hint>
      {/*
        Not a control the real app has — a device syncs on a timer and on launch. It is here
        because the lag between a parent changing a rule and a child enforcing it is invisible
        on hardware and is exactly what two phones on one screen exist to show.
      */}
      <Btn variant="tonal" onClick={() => dispatch(sync(world))}>
        Sync now (harness only)
      </Btn>
      {policyOutOfDate(world) ? (
        <Hint>Your parent has changed something this phone has not picked up yet.</Hint>
      ) : null}
    </Card>
  );
}

function relative(at: number): string {
  const minutes = Math.max(0, Math.round((Date.now() - at) / 60000));
  if (minutes < 1) return 'just now';
  if (minutes === 1) return '1 minute ago';
  return `${minutes} minutes ago`;
}

// ------------------------------------------------------------------ Privacy

function Privacy({
  world,
  patchChild,
}: {
  world: World;
  patchChild: (next: Partial<ChildDevice>) => void;
}) {
  const child = world.child;
  const locked = aiLocked(child);

  return (
    <>
      <Card>
        <Title>{S.setup_disclosure_heading}</Title>
        <p className="kg-body-text" style={{ whiteSpace: 'pre-line' }}>
          {S.setup_disclosure_body}
        </p>
        <p className="kg-body-text" style={{ whiteSpace: 'pre-line' }}>
          {S.setup_disclosure_network}
        </p>
        <label className="kg-radio" style={{ marginTop: 8 }}>
          <input
            type="checkbox"
            checked={child.disclosureAccepted}
            onChange={(event) => patchChild({ disclosureAccepted: event.target.checked })}
          />
          <span className="kg-label">{S.setup_disclosure_accept}</span>
        </label>
      </Card>

      <Card>
        <Title>{S.ai_heading}</Title>
        <Body>{S.ai_hint}</Body>
        <SwitchRow
          label={S.ai_toggle}
          checked={locked ? false : child.aiEnabled}
          disabled={!child.disclosureAccepted || locked}
          onChange={(next) => patchChild({ aiEnabled: next })}
        />
        {locked ? <Hint>{S.supervision_locked_by_parent}</Hint> : null}
      </Card>

      <Card>
        <Title>{S.stats_heading}</Title>
        <Body>{S.stats_hint}</Body>
        <p className="kg-label" style={{ marginTop: 10 }}>
          {S.stats_none}
        </p>
        <Hint>{format(S.stats_engine, 2, 'v1-2026-09-06', 210)}</Hint>
        <Btn variant="text">{S.stats_reset}</Btn>
      </Card>
    </>
  );
}
