'use client';

import { useState } from 'react';

import { Icon, type IconName } from '../Icons';
import { Body, Btn, Card, Chip, Field, Hint, Radio, SwitchRow, Title } from '../ui';
import { S, format } from '../strings';
import { MOCK_REPORTS } from '../mock';
import {
  issuePairingCode,
  mintCode,
  removeChild,
  writePolicy,
  type OverrideLevel,
  type PairedChild,
  type ParentDevice,
  type Policy,
  type ReviewScope,
  type World,
} from '../world';

type Tab = 'children' | 'reports' | 'rules' | 'account';

/**
 * The parent app, reading and writing the shared family.
 *
 * Everything here acts on `world.family` — the server — and nothing reaches into the child
 * device directly. That is the same boundary the real system has, and keeping it means the
 * harness reproduces the interesting lag rather than papering over it: saving a rule here
 * changes what the *server* holds, and the child keeps enforcing its cached copy until it syncs.
 */
export function ParentApp({
  world,
  dispatch,
  patchParent,
}: {
  world: World;
  dispatch: (next: World) => void;
  patchParent: (next: Partial<ParentDevice>) => void;
}) {
  const [tab, setTab] = useState<Tab>('children');

  if (!world.parent.signedIn) {
    return (
      <>
        <div className="kg-toolbar">{S.parent_title}</div>
        <ParentAuth
          onSignIn={(withRecovery) =>
            patchParent({ signedIn: true, recoveryCode: withRecovery ? mintCode() : null })
          }
        />
      </>
    );
  }

  const tabs: { id: Tab; label: string; icon: IconName }[] = [
    { id: 'children', label: S.nav_children, icon: 'family' },
    { id: 'reports', label: S.nav_reports, icon: 'reports' },
    { id: 'rules', label: S.nav_rules, icon: 'rules' },
    { id: 'account', label: S.nav_account, icon: 'account' },
  ];

  return (
    <>
      <div className="kg-toolbar">{tabs.find((t) => t.id === tab)?.label}</div>
      <div className="kg-body">
        {tab === 'children' ? <Children world={world} dispatch={dispatch} /> : null}
        {tab === 'reports' ? <Reports world={world} /> : null}
        {tab === 'rules' ? <Rules world={world} dispatch={dispatch} /> : null}
        {tab === 'account' ? <Account world={world} patchParent={patchParent} /> : null}
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

function ParentAuth({ onSignIn }: { onSignIn: (withRecovery: boolean) => void }) {
  const [showRecovery, setShowRecovery] = useState(false);
  return (
    <div className="kg-body" style={{ paddingTop: 24 }}>
      <h1 style={{ fontSize: 24, fontWeight: 700, margin: 0, color: 'var(--on-surface)' }}>
        {S.parent_sign_in_heading}
      </h1>
      <Body>{S.parent_sign_in_hint}</Body>
      <div style={{ marginTop: 16 }}>
        <Field placeholder={S.parent_email_hint} />
        <Field placeholder={S.parent_password_hint} />
      </div>
      <Btn onClick={() => onSignIn(false)}>{S.parent_login}</Btn>
      {/* Registering is the only path that produces a recovery code, so it is the only one
          that hands one to the Account tab. */}
      <Btn variant="tonal" onClick={() => onSignIn(true)}>
        {S.parent_register}
      </Btn>
      <Btn variant="text" onClick={() => setShowRecovery(!showRecovery)}>
        {S.parent_recover}
      </Btn>
      {showRecovery ? (
        <>
          <Field placeholder={S.parent_recovery_hint} />
          <Btn variant="tonal" onClick={() => onSignIn(false)}>
            {S.parent_recover_submit}
          </Btn>
        </>
      ) : null}
    </div>
  );
}

// ------------------------------------------------------------------ Children

function Children({ world, dispatch }: { world: World; dispatch: (next: World) => void }) {
  const children = world.family.children;

  return (
    <>
      <Card>
        <Title>{S.parent_add_child}</Title>
        <Body>{S.parent_code_instructions}</Body>
        {world.family.pairingCode ? (
          <>
            {/* Formatted in two groups, as PairingCode.format does, because this is read aloud
                across a room. */}
            <p className="kg-code">
              {world.family.pairingCode.slice(0, 4)} {world.family.pairingCode.slice(4)}
            </p>
          </>
        ) : null}
        <Btn onClick={() => dispatch(issuePairingCode(world))}>{S.parent_add_child_button}</Btn>
      </Card>

      <div className="kg-row-between">
        <p className="kg-title">{S.parent_children_heading}</p>
      </div>

      {children.length === 0 ? (
        <p className="kg-empty">{S.parent_children_empty}</p>
      ) : (
        children.map((child) => (
          <ChildCard
            key={child.installId}
            child={child}
            onRemove={() => dispatch(removeChild(world, child.installId))}
          />
        ))
      )}
    </>
  );
}

function ChildCard({ child, onRemove }: { child: PairedChild; onRemove: () => void }) {
  return (
    <Card>
      <Title>{child.label}</Title>
      <Hint>
        {child.lastSeen
          ? format(S.parent_child_last_seen, relative(child.lastSeen))
          : S.parent_child_never_seen}
      </Hint>
      <div style={{ marginTop: 10 }}>
        {child.events.length === 0 ? (
          <p className="kg-event">{S.parent_child_quiet}</p>
        ) : (
          <>
            <p className="kg-label" style={{ marginBottom: 4 }}>
              {S.parent_child_events_heading}
            </p>
            {child.events.slice(0, 12).map((event, index) => (
              <p
                key={index}
                className={`kg-event${event.severity === 3 ? ' kg-event--high' : ''}`}
              >
                {format(
                  S.parent_event_line,
                  relative(event.at),
                  CATEGORY[event.category] ?? event.category,
                  event.heeded
                    ? S.parent_outcome_heeded
                    : event.outcome.startsWith('SENT')
                      ? S.parent_outcome_sent
                      : S.parent_outcome_abandoned,
                )}
              </p>
            ))}
          </>
        )}
      </div>
      <button
        className="kg-btn kg-btn--outline"
        style={{ marginTop: 10, color: 'var(--warn-medium)' }}
        type="button"
        onClick={onRemove}
      >
        {S.parent_remove_child}
      </button>
    </Card>
  );
}

const CATEGORY: Record<string, string> = {
  PII_DISCLOSURE: S.category_pii,
  HARASSMENT: S.category_harassment,
  SEXUAL_SOLICITATION: S.category_solicitation,
  SELF_HARM: S.category_self_harm,
  VIOLENCE_THREAT: S.category_violence,
  IN_PERSON_MEETUP: S.category_meetup,
  SUBSTANCE: S.category_substance,
};

function relative(at: number): string {
  const minutes = Math.max(0, Math.round((Date.now() - at) / 60000));
  if (minutes < 1) return S.time_just_now;
  if (minutes < 60) return format(S.time_minutes, minutes);
  return format(S.time_hours, Math.round(minutes / 60));
}

// ------------------------------------------------------------------ Reports

function Reports({ world }: { world: World }) {
  const [period, setPeriod] = useState<'day' | 'week'>('day');
  const [selected, setSelected] = useState(0);

  const children = world.family.children;
  if (children.length === 0) return <p className="kg-empty">{S.parent_report_no_children}</p>;
  if (!world.family.policy.reportsEnabled) {
    return <p className="kg-empty">{S.parent_report_empty}</p>;
  }

  const child = children[Math.min(selected, children.length - 1)];
  const scope = world.family.policy.reviewScope;
  const basis = {
    CONCERNING_ONLY: S.parent_report_basis_concerning,
    THEMES: S.parent_report_basis_themes,
    FULL_TEXT: S.parent_report_basis_full,
  }[scope];

  // Counts and tags come from what this child's device actually uploaded. The narrative does
  // not: the real one is written server-side by a model from this same data, which a browser
  // harness has no business reproducing. The line under it says so rather than letting a
  // stand-in paragraph read as the product's output.
  const tally = (values: string[]) => {
    const counts = new Map<string, number>();
    for (const value of values) counts.set(value, (counts.get(value) ?? 0) + 1);
    return [...counts.entries()].sort((a, b) => b[1] - a[1]);
  };
  const themes = tally(child.samples.flatMap((s) => s.themes));
  const emotions = tally(child.samples.flatMap((s) => s.emotions));
  const hasData = child.samples.length > 0 || child.events.length > 0;
  const narrative = MOCK_REPORTS[scope].summary;

  return (
    <>
      {children.length > 1 ? (
        <div style={{ marginBottom: 8 }}>
          {children.map((item, index) => (
            <span
              key={item.installId}
              className="kg-chip kg-chip--pick"
              data-on={index === selected}
              onClick={() => setSelected(index)}
            >
              {item.label}
            </span>
          ))}
        </div>
      ) : null}

      <div className="kg-seg-toggle">
        <button data-on={period === 'day'} onClick={() => setPeriod('day')} type="button">
          {S.parent_report_daily}
        </button>
        <button data-on={period === 'week'} onClick={() => setPeriod('week')} type="button">
          {S.parent_report_weekly}
        </button>
      </div>

      {!hasData ? (
        <p className="kg-empty">{S.parent_report_empty}</p>
      ) : (
        <>
          <Card>
            <p className="kg-label" style={{ lineHeight: 1.6 }}>
              {narrative}
            </p>
            <Hint>{basis}</Hint>
            <Hint>
              {format(S.parent_report_counts, child.samples.length, child.events.length)}
            </Hint>
            <Hint>
              Narrative is a stand-in. The real one is written server-side by the model from
              exactly this data.
            </Hint>
          </Card>

          {themes.length > 0 ? (
            <Card>
              <Title>{S.parent_report_themes}</Title>
              <div style={{ marginTop: 8 }}>
                {themes.map(([label, count]) => (
                  <Chip key={label}>
                    {label}&nbsp;&nbsp;{count}
                  </Chip>
                ))}
              </div>
            </Card>
          ) : null}

          {emotions.length > 0 ? (
            <Card>
              <Title>{S.parent_report_emotions}</Title>
              <div style={{ marginTop: 8 }}>
                {emotions.map(([label, count]) => (
                  <Chip key={label}>
                    {label}&nbsp;&nbsp;{count}
                  </Chip>
                ))}
              </div>
            </Card>
          ) : null}

          {/* Only at FULL_TEXT does anything here carry words, and it is worth seeing exactly
              what a parent gets when they turn that on. */}
          {scope === 'FULL_TEXT' && child.samples.some((s) => s.text) ? (
            <Card>
              <Title>Messages</Title>
              {child.samples
                .filter((s) => s.text)
                .slice(0, 10)
                .map((sample, index) => (
                  <p key={index} className="kg-event">
                    {sample.text}
                  </p>
                ))}
            </Card>
          ) : null}
        </>
      )}
    </>
  );
}

// ------------------------------------------------------------------ Rules

function Rules({ world, dispatch }: { world: World; dispatch: (next: World) => void }) {
  const [draft, setDraft] = useState<Policy>(world.family.policy);
  const [saved, setSaved] = useState(false);

  // Re-seed when the stored policy changes underneath the editor — a reset, or one of the
  // shortcut buttons. Without this the controls keep showing values that are no longer real and
  // the next Save writes the stale ones back, which is the worst possible failure for a screen
  // whose whole job is deciding what a child's phone does.
  const [seededVersion, setSeededVersion] = useState(world.family.policy.version);
  if (world.family.policy.version !== seededVersion) {
    setSeededVersion(world.family.policy.version);
    setDraft(world.family.policy);
    setSaved(false);
  }
  const set = (next: Partial<Policy>) => {
    setDraft((current) => ({ ...current, ...next }));
    setSaved(false);
  };

  return (
    <>
      <Card>
        <Title>{S.parent_policy_min_intensity}</Title>
        <Body>{S.parent_policy_min_intensity_hint}</Body>
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
              checked={draft.minIntensity === value}
              onChange={() => set({ minIntensity: value })}
            />
          ))}
        </div>
        <SwitchRow
          label={S.parent_policy_lock}
          checked={draft.lockSettings}
          onChange={(next) => set({ lockSettings: next })}
        />
        <SwitchRow
          label={S.parent_policy_block}
          checked={draft.blockAtHigh}
          onChange={(next) => set({ blockAtHigh: next })}
        />
      </Card>

      <Card>
        <Title>{S.parent_override_heading}</Title>
        <div style={{ marginTop: 8 }}>
          {(
            [
              ['FULL', S.parent_override_full, S.parent_override_full_hint],
              ['LIMITED', S.parent_override_limited, S.parent_override_limited_hint],
              ['NONE', S.parent_override_none, S.parent_override_none_hint],
            ] as [OverrideLevel, string, string][]
          ).map(([value, label, hint]) => (
            <Radio
              key={value}
              label={label}
              hint={hint}
              checked={draft.overrideLevel === value}
              onChange={() => set({ overrideLevel: value })}
            />
          ))}
        </div>
      </Card>

      <Card>
        <Title>{S.parent_scope_heading}</Title>
        <div style={{ marginTop: 8 }}>
          {(
            [
              ['CONCERNING_ONLY', S.parent_scope_concerning, S.parent_scope_concerning_hint],
              ['THEMES', S.parent_scope_themes, S.parent_scope_themes_hint],
              ['FULL_TEXT', S.parent_scope_full, S.parent_scope_full_hint],
            ] as [ReviewScope, string, string][]
          ).map(([value, label, hint]) => (
            <Radio
              key={value}
              label={label}
              hint={hint}
              checked={draft.reviewScope === value}
              onChange={() => {
                // Turning on full review is the one control here that changes what a child
                // experiences rather than what this screen shows, so it asks.
                if (value === 'FULL_TEXT' && draft.reviewScope !== 'FULL_TEXT') {
                  if (!window.confirm(S.parent_scope_full_confirm)) return;
                }
                set({ reviewScope: value });
              }}
            />
          ))}
        </div>
        <SwitchRow
          label={S.parent_reports_toggle}
          checked={draft.reportsEnabled}
          onChange={(next) => set({ reportsEnabled: next })}
        />
      </Card>

      <Card>
        <Title>{S.parent_policy_ai}</Title>
        <Body>{S.parent_policy_ai_hint}</Body>
        <div style={{ marginTop: 8 }}>
          <Radio
            label={S.parent_policy_ai_child}
            checked={!draft.aiForcedOff}
            onChange={() => set({ aiForcedOff: false })}
          />
          <Radio
            label={S.parent_policy_ai_off}
            checked={draft.aiForcedOff}
            onChange={() => set({ aiForcedOff: true })}
          />
        </div>
      </Card>

      {/* Explicit save, because several of these interact and a half-applied policy syncing to
          a child's phone mid-edit is worse than an unsaved one. */}
      <Btn
        onClick={() => {
          dispatch(writePolicy(world, draft));
          setSaved(true);
        }}
      >
        {S.parent_policy_save}
      </Btn>
      {saved ? (
        <Hint>
          {S.parent_policy_saved} Their phone keeps enforcing the old rules until it syncs.
        </Hint>
      ) : null}
    </>
  );
}

// ------------------------------------------------------------------ Account

function Account({
  world,
  patchParent,
}: {
  world: World;
  patchParent: (next: Partial<ParentDevice>) => void;
}) {
  const [invite, setInvite] = useState<string | null>(null);
  return (
    <>
      <Card>
        <Title>{format(S.parent_signed_in, world.parent.email)}</Title>
        {/* Shown once. The server keeps no readable copy, so a parent who does not write it
            down has lost it. */}
        {world.parent.recoveryCode ? (
          <p className="kg-body-text" style={{ color: 'var(--accent)' }}>
            {format(S.parent_recovery_code, world.parent.recoveryCode)}
          </p>
        ) : null}
      </Card>

      <Card>
        <Title>{S.parent_invite_caregiver}</Title>
        <Body>{S.parent_caregiver_hint}</Body>
        {invite ? (
          <p className="kg-code" style={{ fontSize: 24 }}>
            {invite.slice(0, 4)} {invite.slice(4)}
          </p>
        ) : null}
        <Btn variant="tonal" onClick={() => setInvite(mintCode())}>
          {S.parent_invite_caregiver_button}
        </Btn>
        <Field placeholder={S.parent_join_code_hint} />
        <Btn variant="text">{S.parent_join_family}</Btn>
      </Card>

      <Card>
        <Title>{S.parent_danger_heading}</Title>
        <Btn
          variant="tonal"
          onClick={() => patchParent({ signedIn: false, recoveryCode: null })}
        >
          {S.parent_sign_out}
        </Btn>
        <Btn variant="danger">{S.parent_delete_account}</Btn>
      </Card>
    </>
  );
}
