'use client';

import type { ReactNode } from 'react';

/**
 * The small primitives every screen is built from.
 *
 * Each one maps to a specific Android widget, and the mapping is named in the comment so a
 * change made here has an obvious destination. Keeping them this thin is deliberate: the
 * harness exists to judge layout and copy, and a component library with its own opinions would
 * start producing a design the phone cannot reproduce.
 */

export function Card({ children, style }: { children: ReactNode; style?: React.CSSProperties }) {
  // MaterialCardView with @style/Widget.Keyguard.Card.
  return (
    <div className="kg-card" style={style}>
      {children}
    </div>
  );
}

export function Title({ children }: { children: ReactNode }) {
  // TextAppearance.Keyguard.CardTitle
  return <p className="kg-title">{children}</p>;
}

export function Body({ children }: { children: ReactNode }) {
  // TextAppearance.Keyguard.Body
  return <p className="kg-body-text">{children}</p>;
}

export function Hint({ children }: { children: ReactNode }) {
  // TextAppearance.Keyguard.OptionHint
  return <p className="kg-hint">{children}</p>;
}

export function Btn({
  children,
  onClick,
  variant = 'filled',
  disabled,
}: {
  children: ReactNode;
  onClick?: () => void;
  variant?: 'filled' | 'tonal' | 'text' | 'danger' | 'outline';
  disabled?: boolean;
}) {
  const suffix = variant === 'filled' ? '' : ` kg-btn--${variant}`;
  return (
    <button className={`kg-btn${suffix}`} onClick={onClick} disabled={disabled} type="button">
      {children}
    </button>
  );
}

/** MaterialSwitch in a row with its label — the shape every settings toggle uses. */
export function SwitchRow({
  label,
  checked,
  onChange,
  disabled,
}: {
  label: string;
  checked: boolean;
  onChange: (next: boolean) => void;
  disabled?: boolean;
}) {
  return (
    <div
      className="kg-switchrow"
      style={disabled ? { opacity: 0.45 } : undefined}
      onClick={() => !disabled && onChange(!checked)}
      role="switch"
      aria-checked={checked}
      tabIndex={0}
      onKeyDown={(event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          if (!disabled) onChange(!checked);
        }
      }}
    >
      <span className="kg-label">{label}</span>
      <span className="kg-switch" data-on={checked} />
    </div>
  );
}

/**
 * One radio option, with the one-line hint underneath that the parent policy screens rely on.
 *
 * The hint is part of the option rather than a separate element because these are decisions with
 * consequences for someone else — "Nothing can be ignored" and "Everything they type" cannot be
 * understood from a label alone, and separating the explanation invites dropping it.
 */
export function Radio({
  label,
  hint,
  checked,
  onChange,
  disabled,
}: {
  label: string;
  hint?: string;
  checked: boolean;
  onChange: () => void;
  disabled?: boolean;
}) {
  return (
    <div style={disabled ? { opacity: 0.45 } : undefined}>
      <label className="kg-radio">
        <input type="radio" checked={checked} onChange={onChange} disabled={disabled} />
        <span className="kg-label">{label}</span>
      </label>
      {hint ? <p className="kg-hint" style={{ marginLeft: 26, marginBottom: 4 }}>{hint}</p> : null}
    </div>
  );
}

export function Field({
  placeholder,
  value,
  onChange,
}: {
  placeholder: string;
  value?: string;
  onChange?: (next: string) => void;
}) {
  return (
    <input
      className="kg-field"
      placeholder={placeholder}
      value={value}
      onChange={(event) => onChange?.(event.target.value)}
    />
  );
}

export function Chip({ children }: { children: ReactNode }) {
  return <span className="kg-chip">{children}</span>;
}
