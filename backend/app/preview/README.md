# The design harness

```sh
cd backend && npm run dev
# → http://localhost:3000/preview
```

**Two phones side by side**, the family between them, and three apps on the child's. It exists
because the Android loop — edit, `assembleFamilyDebug`, `adb install`, navigate to the screen,
set up the state — is about two minutes, and design work needs it to be two seconds. Testing a
*flow* on hardware is worse than that: pairing needs two devices and a running server.

## The two phones are actually connected

The handshake is real. Create an account on the parent, tap **Get a pairing code**, read the
code across, type it into the child's Family tab. From then on:

- Typing a flagged message in the **chat app** or the **document editor** queues a real event on
  the child, exactly as the keyboard does — an append to a local queue and nothing else.
- **Sync child** moves the queue to the family and pulls the policy down.
- The parent's Children tab shows the event arrive, with the outcome the child's device actually
  inferred.

**Rule changes do not reach the child until it syncs**, and that is deliberate rather than a
shortcut. The child enforces `cachedPolicy`; the parent writes `family.policy`. The gap between
them is where "I changed that, why is nothing different" comes from, it is invisible on hardware,
and the line under each phone shows both sides of it — what the child is enforcing, how many
events it is holding, and whether its policy is stale.

The status lines under the frames are developer instrumentation. None of it is UI the product
has, and none of it is something a user should ever see.

## What it is for

The obvious use is looking at layout and copy without a build. The less obvious one matters
more: **states that are impractical to reach on hardware**. Seeing what a child sees under
`FULL_TEXT` review with `OverrideLevel.NONE` needs two devices, a running server, a pairing
code and a policy round trip. Here it is two clicks. Combinations that take ten minutes to
reach are combinations nobody checks, which is exactly where things are broken and nobody
notices.

The control panel covers: both protection paths and every partial state, all three review
scopes, all three override levels, light and dark, and the keyboard-does-not-report-its-window
fallback that cannot be triggered deliberately on a device at all. Shortcuts skip the parts of
the flow that are not what you are testing — **Pair now** does the handshake in one click.

## Why there are two typing apps

A chat composer is not the only shape a text field comes in, and the differences are exactly the
ones most likely to break an overlay:

| | Chat | Documents |
|---|---|---|
| Field | one line, on the keyboard's edge | most of the screen |
| Anchor | above the composer | falls back to the keyboard edge |
| Ends with | a send button | a save, or just leaving |
| Flagged span | always visible | can be scrolled out of view |

The document editor is how the full-page branch of `OverlayAnchor` gets *looked at* rather than
merely unit-tested — anchoring above a field that fills the screen would push the warning off
the top, so it must not try.

## It has already earned it

Four real bugs so far, all found within minutes of a screen first rendering:

- **The warning covered the composer.** `OverlayAnchor` anchored to the keyboard's top edge,
  which in every chat app is exactly where the text field sits — so the warning asking you to
  remove the flagged text was covering the text. Fixed in both, and `OverlayTest` now asserts
  the field is cleared, not just the keyboard.
- **Stale step numbers** in the setup checklist ("Read what Keyguard can see", then "1. Allow…",
  then "2. Allow…"), left over from when those two steps stood alone in their own section.
- **A live pairing code captioned "That code has expired."** `parent_code_expired` was being used
  as the caption under a code that had just been minted. Removed from both apps; the instructions
  above the code already say it works once and lasts ten minutes.
- **A red keyboard.** Android writes colours as `#AARRGGBB` and CSS reads `#RRGGBBAA`, so the
  keyboard palette pasted across turned `#ff3c424c` — a grey — into red at 30% alpha. Only
  visible because the harness draws a keyboard at all.

## How it stays honest

- **`strings.ts` was generated from `app/src/main/res/values/strings.xml`**, so the browser
  shows the words the phone shows rather than a paraphrase. Format specifiers keep Android's
  `%1$s` form so a string moves between the two without editing.
- **`preview.css` custom properties are the values from `colors.xml` and `dimens.xml`**, with
  the resource file named in the comment beside each group. Dark mode mirrors `values-night/`,
  including the parts that deliberately have no night variant.
- **The frame is 393 × 852**, a Pixel-class viewport in dp — the same units Android lays out
  against. A card that fits here fits on the device.
- **The overlay scene runs the real rule pack**, read off disk from `detect/`, so typing
  produces genuine warnings with genuine severities. `detect.ts` is a cut-down port and says so:
  it keeps word boundaries, suppressors and co-occurrence, and drops the normalizer and the
  evasion pass. It therefore *under*-detects — anything it flags, the Kotlin engine flags too.

## Which direction changes flow

From here to Android. This is the design surface now: change the copy in `strings.ts`, the
palette in `preview.css`, or a layout in `screens/`, look at it, then carry the change back to
`res/values/` and `ui/` once it is settled.

Two things the harness cannot tell you, both worth remembering before trusting it:

- **The shade does not actually stop typing here.** On Android it is a separate window that
  absorbs touches; in a browser it is a div over an input that still has focus. It is drawn
  faithfully and it blocks clicks, but the keyboard under it is a picture.
- **Nothing here exercises the accessibility service, `WindowManager`, or `ACTION_SET_TEXT`.**
  Placement, the touch-absorbing shade and editing another app's field remain
  first-run-on-device risks whatever this looks like.

## Note for deployment

`/preview` and `/` are dev tooling and have no place in a production deployment. They are
harmless — static React over mock data, no API calls, no secrets — but they are also not
something to serve to the public. Delete `app/page.tsx` and `app/preview/` before deploying, or
gate them on `process.env.NODE_ENV !== 'production'`.
