/**
 * The whole testing environment: two phones and the family that connects them.
 *
 * The harness started as one phone with a control panel full of switches, which was fine for
 * judging a layout and useless for judging a *flow*. Pairing, policy delivery and event upload
 * are three-party interactions — child, server, parent — and the interesting failures live in
 * the gaps between them: a parent who changed a rule an hour ago on a child that has not synced,
 * a child unpaired remotely who does not know it yet, a queue of events sitting on a phone that
 * has not been opened.
 *
 * So the model here is not "UI state". It is a small simulation of the real system, with the
 * same three places state can live and the same lag between them:
 *
 * - [Family] is the server. Policy changes land here immediately.
 * - [ChildDevice.cachedPolicy] is what the child is *actually enforcing*, which is whatever it
 *   last synced. This is the field that makes the harness worth having: on the device the same
 *   gap exists and is invisible, and it is where "I changed that, why is nothing different"
 *   comes from.
 * - [ChildDevice.queuedEvents] is the local queue the keyboard writes to and only the sync
 *   drains, mirroring `EventQueue` and `SupervisionSync`.
 *
 * Nothing here talks to the real API. It is a faithful model of the contract, not a client.
 */

export type DeviceRole = 'UNSET' | 'CHILD' | 'PARENT';
export type OverrideLevel = 'FULL' | 'LIMITED' | 'NONE';
export type ReviewScope = 'CONCERNING_ONLY' | 'THEMES' | 'FULL_TEXT';
export type Intensity = 'SUBTLE' | 'STANDARD' | 'INSISTENT';
export type OverlayPosition = 'ABOVE_KEYBOARD' | 'SCREEN_TOP';

/** Mirrors `ComposeOutcome`. */
export type Outcome = 'SENT' | 'SENT_INFERRED' | 'ABANDONED_DELETED' | 'ABANDONED_SWITCHED';

export interface Policy {
  version: number;
  minIntensity: Intensity;
  blockAtHigh: boolean;
  lockSettings: boolean;
  aiForcedOff: boolean;
  overrideLevel: OverrideLevel;
  reviewScope: ReviewScope;
  reportsEnabled: boolean;
}

/** Mirrors `SupervisionEvent`. Note the absence of any text field — see the class on Android. */
export interface SupervisionEvent {
  at: number;
  category: string;
  severity: number;
  outcome: Outcome;
  heeded: boolean;
}

/** One message as a report may see it. `text` only ever exists under FULL_TEXT. */
export interface ActivitySample {
  at: number;
  themes: string[];
  emotions: string[];
  chars: number;
  flagged: boolean;
  text?: string;
}

export interface PairedChild {
  installId: string;
  label: string;
  pairedAt: number;
  lastSeen: number | null;
  events: SupervisionEvent[];
  samples: ActivitySample[];
}

export interface Family {
  /** Created lazily, the first time a parent asks for a code. */
  created: boolean;
  /** Single-use, and cleared the moment it is redeemed. */
  pairingCode: string | null;
  policy: Policy;
  children: PairedChild[];
}

export interface ChildDevice {
  installId: string;
  label: string;

  accessibilityGranted: boolean;
  overlayGranted: boolean;
  keyboardEnabled: boolean;
  keyboardSelected: boolean;

  disclosureAccepted: boolean;
  aiEnabled: boolean;
  autocorrect: boolean;
  haptics: boolean;

  intensity: Intensity;
  overlayPosition: OverlayPosition;
  overlayOpacity: number;
  overlayEnabled: boolean;

  supervised: boolean;
  /** What this device is enforcing right now: the policy as of its last sync, not the server's. */
  cachedPolicy: Policy;
  lastSyncAt: number | null;

  /** Written by the keyboard, drained only by a sync. Mirrors `EventQueue`. */
  queuedEvents: SupervisionEvent[];
  queuedSamples: ActivitySample[];
}

export interface ParentDevice {
  signedIn: boolean;
  email: string;
  /** Shown once after registering, then never again. */
  recoveryCode: string | null;
}

export interface World {
  theme: 'light' | 'dark';
  family: Family;
  child: ChildDevice;
  parent: ParentDevice;
}

export const DEFAULT_POLICY: Policy = {
  // Matches `FamilyPolicy.DEFAULT` exactly: pairing on its own must change nothing.
  version: 0,
  minIntensity: 'SUBTLE',
  blockAtHigh: true,
  lockSettings: false,
  aiForcedOff: false,
  overrideLevel: 'FULL',
  reviewScope: 'CONCERNING_ONLY',
  reportsEnabled: true,
};

/**
 * A fresh pair of phones, as they would actually arrive.
 *
 * Nothing granted, nothing accepted, no account, no pairing. Starting anywhere further along
 * would hide the first-run screens, which are the ones most worth looking at and the ones a
 * developer with a configured phone never sees again.
 *
 * Deep-cloned on every call rather than handed out as a shared object. Every action in this
 * file is already copy-on-write, so nothing mutates the template today — but a reset that
 * quietly returned a world some earlier session had touched would be the hardest kind of bug
 * to see, and the clone costs nothing.
 */
export function freshWorld(): World {
  return structuredClone(INITIAL_WORLD_TEMPLATE);
}

const INITIAL_WORLD_TEMPLATE: World = {
  theme: 'light',
  family: {
    created: false,
    pairingCode: null,
    policy: { ...DEFAULT_POLICY },
    children: [],
  },
  child: {
    installId: 'child-device-1',
    label: "Maya's phone",
    accessibilityGranted: false,
    overlayGranted: false,
    keyboardEnabled: false,
    keyboardSelected: false,
    disclosureAccepted: false,
    aiEnabled: false,
    autocorrect: true,
    haptics: true,
    intensity: 'STANDARD',
    overlayPosition: 'ABOVE_KEYBOARD',
    overlayOpacity: 96,
    overlayEnabled: true,
    supervised: false,
    cachedPolicy: { ...DEFAULT_POLICY },
    lastSyncAt: null,
    queuedEvents: [],
    queuedSamples: [],
  },
  parent: { signedIn: false, email: 'parent@example.com', recoveryCode: null },
};

// ---------------------------------------------------------------- actions

/**
 * Mints a pairing code.
 *
 * Crockford base32 with the look-alikes removed, same alphabet as `PairingCode` on the device,
 * so a code read off one phone frame can be typed into the other without the I/1 and O/0
 * confusion that alphabet exists to avoid.
 */
export function mintCode(): string {
  const alphabet = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
  let code = '';
  for (let i = 0; i < 8; i += 1) {
    code += alphabet[Math.floor(Math.random() * alphabet.length)];
  }
  return code;
}

export function normalizeCode(input: string): string {
  // The device folds look-alikes rather than rejecting them; typing O for 0 should just work.
  return input
    .toUpperCase()
    .replace(/[^0-9A-Z]/g, '')
    .replace(/O/g, '0')
    .replace(/[IL]/g, '1')
    .replace(/U/g, 'V');
}

export function issuePairingCode(world: World): World {
  return {
    ...world,
    family: { ...world.family, created: true, pairingCode: mintCode() },
  };
}

/**
 * Redeems a code on the child device.
 *
 * Returns null when the code is wrong, which the caller shows as the same message an expired one
 * gets — the server answers both identically, and a harness that distinguished them would teach
 * the wrong expectation about the real flow.
 */
export function redeemCode(world: World, input: string, label: string): World | null {
  const code = normalizeCode(input);
  if (!world.family.pairingCode || code !== world.family.pairingCode) return null;

  const now = Date.now();
  return {
    ...world,
    family: {
      ...world.family,
      // Single-use, atomically. Two devices racing the same code cannot both join.
      pairingCode: null,
      children: [
        ...world.family.children,
        {
          installId: world.child.installId,
          label,
          pairedAt: now,
          lastSeen: now,
          events: [],
          samples: [],
        },
      ],
    },
    child: {
      ...world.child,
      label,
      supervised: true,
      // The policy arrives with the join response, so a freshly paired device is already in step.
      cachedPolicy: { ...world.family.policy },
      lastSyncAt: now,
    },
  };
}

/**
 * The child talking to the server: policy down, queued events up.
 *
 * Order matters and matches `SupervisionSync`. Policy first, because the answer might be "you
 * are no longer supervised", and uploading a queue to a family that removed you wastes the
 * request and stores events a parent has already said they do not want.
 */
export function sync(world: World): World {
  if (!world.child.supervised) return world;
  const now = Date.now();
  const membership = world.family.children.find((c) => c.installId === world.child.installId);

  if (!membership) {
    // Removed while offline. The device learns it here, drops its policy and its queue, and
    // stops reporting. This is the transition that is nearly impossible to observe on hardware.
    return {
      ...world,
      child: {
        ...world.child,
        supervised: false,
        cachedPolicy: { ...DEFAULT_POLICY },
        queuedEvents: [],
        queuedSamples: [],
        lastSyncAt: now,
      },
    };
  }

  const scope = world.family.policy.reviewScope;
  const keepSamples = scope !== 'CONCERNING_ONLY';
  const keepText = scope === 'FULL_TEXT';

  return {
    ...world,
    family: {
      ...world.family,
      children: world.family.children.map((child) =>
        child.installId !== world.child.installId
          ? child
          : {
              ...child,
              lastSeen: now,
              events: [...world.child.queuedEvents, ...child.events],
              samples: keepSamples
                ? [
                    // The scope decides whether the text is stored, on the server, from the
                    // family's own policy — not from the shape of what arrived.
                    ...world.child.queuedSamples.map((s) =>
                      keepText ? s : { ...s, text: undefined },
                    ),
                    ...child.samples,
                  ]
                : child.samples,
            },
      ),
    },
    child: {
      ...world.child,
      cachedPolicy: { ...world.family.policy },
      queuedEvents: [],
      queuedSamples: [],
      lastSyncAt: now,
    },
  };
}

/** The parent writing a policy. The version is the server's to assign. */
export function writePolicy(world: World, next: Omit<Policy, 'version'>): World {
  const previous = world.family.policy;
  const policy: Policy = { ...next, version: previous.version + 1 };

  // Narrowing the scope has to reach data already stored, not just data collected from now on.
  // Otherwise "turn full review off" leaves a fortnight of messages on the server, still
  // readable by the parent who just said they no longer wanted them.
  const depth = (s: ReviewScope) => (s === 'CONCERNING_ONLY' ? 0 : s === 'THEMES' ? 1 : 2);
  const narrowed = depth(policy.reviewScope) < depth(previous.reviewScope);

  return {
    ...world,
    family: {
      ...world.family,
      policy,
      children: !narrowed
        ? world.family.children
        : world.family.children.map((child) => ({
            ...child,
            samples:
              policy.reviewScope === 'CONCERNING_ONLY'
                ? []
                : child.samples.map(({ text, ...rest }) => rest),
          })),
    },
  };
}

/** Unpairing, which only a parent can do. The child finds out on its next sync. */
export function removeChild(world: World, installId: string): World {
  return {
    ...world,
    family: {
      ...world.family,
      children: world.family.children.filter((c) => c.installId !== installId),
    },
  };
}

/** The keyboard's entire involvement in reporting: an append to a local queue. */
export function queueEvent(world: World, event: SupervisionEvent): World {
  if (!world.child.supervised) return world;
  return { ...world, child: { ...world.child, queuedEvents: [event, ...world.child.queuedEvents] } };
}

export function queueSample(world: World, sample: ActivitySample): World {
  if (!world.child.supervised) return world;
  const scope = world.child.cachedPolicy.reviewScope;
  // ContentCapture is the gate: CONCERNING_ONLY records nothing at all, and only FULL_TEXT
  // keeps the words.
  if (scope === 'CONCERNING_ONLY') return world;
  const recorded: ActivitySample =
    scope === 'FULL_TEXT' ? sample : { ...sample, text: undefined };
  return {
    ...world,
    child: { ...world.child, queuedSamples: [recorded, ...world.child.queuedSamples] },
  };
}

// ---------------------------------------------------------------- derived

export type ProtectionStatus =
  | 'on'
  | 'partial-missing-draw'
  | 'partial-missing-a11y'
  | 'partial-keyboard'
  | 'off';

export function protectionStatus(child: ChildDevice): ProtectionStatus {
  const overlayReady = child.accessibilityGranted && child.overlayGranted;
  const keyboardReady = child.keyboardEnabled && child.keyboardSelected;
  if (overlayReady || keyboardReady) return 'on';
  if (child.accessibilityGranted && !child.overlayGranted) return 'partial-missing-draw';
  if (child.overlayGranted && !child.accessibilityGranted) return 'partial-missing-a11y';
  if (child.keyboardEnabled) return 'partial-keyboard';
  return 'off';
}

/** Mirrors `OverrideLevel.mayDismiss`. */
export function mayDismiss(level: OverrideLevel, severity: number): boolean {
  if (level === 'FULL') return true;
  if (level === 'NONE') return false;
  return severity < 3;
}

/**
 * Everything below reads `cachedPolicy`, never `family.policy`.
 *
 * That is the whole point of modelling the two separately: the child enforces what it last
 * synced, so a rule the parent changed a minute ago has genuinely not arrived yet.
 */
export function effectiveIntensity(child: ChildDevice): Intensity {
  if (!child.supervised) return child.intensity;
  const order: Intensity[] = ['SUBTLE', 'STANDARD', 'INSISTENT'];
  if (child.cachedPolicy.lockSettings) return child.cachedPolicy.minIntensity;
  return order.indexOf(child.intensity) >= order.indexOf(child.cachedPolicy.minIntensity)
    ? child.intensity
    : child.cachedPolicy.minIntensity;
}

export function effectiveOverride(child: ChildDevice): OverrideLevel {
  return child.supervised ? child.cachedPolicy.overrideLevel : 'FULL';
}

export function effectiveScope(child: ChildDevice): ReviewScope {
  return child.supervised ? child.cachedPolicy.reviewScope : 'CONCERNING_ONLY';
}

export function blocksAtHigh(child: ChildDevice): boolean {
  return child.supervised ? child.cachedPolicy.blockAtHigh : true;
}

export function aiLocked(child: ChildDevice): boolean {
  return child.supervised && child.cachedPolicy.aiForcedOff;
}

/** True when the parent has changed something the child has not picked up yet. */
export function policyOutOfDate(world: World): boolean {
  return world.child.supervised && world.child.cachedPolicy.version !== world.family.policy.version;
}
