# Keyguard

A safety keyboard that warns you *before* you send something you shouldn't — sensitive
personal information, or content that could hurt you or someone else. Prevention, not
after-the-fact detection.

Full implementation plan, including the platform research this design rests on:
`~/.claude/plans/based-on-this-transcript-rosy-cake.md`

## Status

| Module | State |
|---|---|
| `:detect` | Local detection engine plus the reporting vocabulary — complete, 61 tests |
| `:app` | Accessibility overlay, IME, autocorrect, sizing, AI client, supervision, reports, onboarding, tabbed child and parent apps — 235 tests; **the overlay and the reworked UI have never run on a device** |
| `backend/` | Parent accounts, family API, report generation — builds, 16 tests; **not deployed** |

Not yet built: rule-pack OTA delivery on the client side (the endpoint exists, nothing fetches
it), swipe typing, next-word prediction, theme/colour customization, and landscape layout.

**The overlay is new and unexercised.** Every line of it compiles and its pure logic is tested
on the JVM, but no part of it has been run against a real accessibility service, a real
`WindowManager`, or a real keyboard. Window placement, the touch-absorbing shade, and
`ACTION_SET_TEXT` against arbitrary host apps are all first-run-on-device risks, and the
Play Accessibility API review is an open question rather than a known outcome. See
**Where the keyboard went**.

Release signing is wired but keyless: `app/build.gradle.kts` reads a keystore from
`local.properties` and produces an unsigned APK when it finds none. `PRIVACY.md` is written and
needs hosting at a public URL before submission.

Parent/child supervision, reports, and recoverable parent accounts are built end to end but
**have never run against a live server**, so pairing, policy delivery, event upload, sample
upload, and report generation are all unexercised paths.

## Where the keyboard went

Keyguard is no longer primarily a keyboard. The child types on Gboard, or whatever else they
already use, and Keyguard runs as an accessibility service that floats a warning over the top.

The reason is the one nobody could engineer around: **a keyboard we write will never feel as
good as the one they already have.** Swipe typing, prediction quality, key feel, themes — a
decade of work each, none of it the product, all of it the first thing a user judges. A safety
keyboard that is 15% worse to type on gets uninstalled in a week and then protects nobody, which
is the same failure mode as a keyboard that cries wolf, arriving by a different road.

Note what this is *not*: Grammarly's Android app is a full keyboard, drawing its own keys, the
same shape Keyguard used to be. Android gives an IME no way to host another IME, so "overlay on
top of Gboard" is not something a keyboard can do — it needs a different mechanism entirely.

### What the change bought

- **Typing feel stops being our problem.** It is Google's, and they are better at it.
- **It sees text an IME could not.** Paste from another app, autofill, voice input, and words
  swipe-typed on a different keyboard were all invisible to the IME. They are not now.
- **It can act on the field.** `ACTION_SET_TEXT` is what makes *Remove it* a thing that
  happens rather than an instruction, and it is what "force delete" in the permission levels
  actually means.
- **Full-content review becomes possible at all.** An IME could only ever have approximated it.

### What it cost, stated plainly

- **Play reviews this under the Accessibility API policy**, which is stricter than the keyboard
  policy the app was under before. This is a real rejection risk and it is unverified. The
  service declares a narrow event set and a plain-language purpose, and the app already meets
  the prominent-disclosure and monitoring-tool obligations that policy asks about — but whether
  that is accepted is not knowable before a submission comes back.
- **There is no iOS equivalent.** The keyboard extension was portable in principle; this is not
  portable at all. `:detect` stays pure Kotlin and still ports; the shell around it would have
  to be something else entirely on iOS, and nobody has designed that.
- **The block is weaker than the keyboard's was.** The IME refused keystrokes at the source.
  The overlay covers the keyboard with a window that absorbs touches, which is not the same
  thing: a hardware keyboard is unaffected, voice input is unaffected, and if the platform will
  not report where the keyboard is then nothing is covered at all. `OverlayAnchor.shadeRect`
  returns null in that case and the warning stops claiming typing is paused, because saying so
  while it plainly is not would be the one straightforwardly dishonest message in the product.
- **The solo flavor's story changed shape.** It used to declare no permissions at all. It now
  declares `SYSTEM_ALERT_WINDOW` and ships an accessibility service, while still having no
  `INTERNET` permission. The claim went from "this app cannot do anything" to "this app can see
  what you type and cannot transmit it" — narrower, but still the half users actually care
  about, and still verifiable from the store listing.

### The keyboard is still there

`KeyguardInputMethodService` and every part of it still builds, still works, and still honours
the same policy. Onboarding treats the two as **alternative paths to the same finished state**:
grant the two overlay permissions, or enable and select the keyboard, and either way setup is
complete. Nobody is nagged for the permissions of a path they did not take —
`OnboardingProgress` is where that rule lives and `OnboardingProgressTest` asserts it in both
directions.

Keeping it is not sentiment. It is the entire existing install base, it is what the `solo`
build's zero-network claim is easiest to make about, and it is the only one of the two that has
ever been verified on a device.

### `:app/overlay` internals

| File | Role |
|---|---|
| `KeyguardAccessibilityService.kt` | The service. Watches text changes, scans, renders, reports. |
| `MonitoredField.kt` | Whether a field may be looked at. Two independent password checks. Pure. |
| `OverlayDecision.kt` | What the floating warning should be doing. Pure, and the whole state table. |
| `OverlayAnchor.kt` | Where the windows go, from the reported IME bounds. Pure, degrades rather than guessing. |
| `OverlayHost.kt` | Owns both windows. `FLAG_NOT_FOCUSABLE` here is load-bearing. |
| `WarningOverlayView.kt` | The warning, and the keyboard shade. |
| `OverlayPermissions.kt` | The two grants, and the intents to ask for them. |

## The keyboard

Still shipped, still working, and no longer the path most users will take. Everything in this
section describes the IME; the overlay above is what a new install is pointed at.

### Keyboard features present

Three key planes, shift with double-tap caps lock, hold-to-repeat backspace, long-press
alternates (accents plus top-row digits), autocorrect with a suggestion strip, an emoji panel,
clipboard paste, cursor keys, select-all, keypress haptics, and a next-keyboard switcher.

### The shared row

Warning strip, suggestions, and idle toolbar all occupy **one row** above the keys, with strict
priority: **warning > suggestions > toolbar**. A spelling hint must never displace a safety
warning. That row sits empty most of the time, since most messages are unremarkable — which is
why the utility buttons and suggestions cost no vertical space, the scarcest resource on screen.

### High severity stops the keyboard

At `Severity.HIGH` the keys stop accepting text. The user has to take the flagged content
back out, or press **Ignore**, before typing continues — an unmissable interruption for the
cases where a warning that can be skimmed past is not enough.

The exits are the load-bearing part, because a keyboard that refuses input and does not say
how to get it back is worse than no keyboard:

- **Backspace and the keyboard switcher are never gated.** One is how the warning gets
  resolved, the other is how the user leaves. Both stay at full opacity while everything else
  dims, so neither reads as inert.
- **The strip force-expands while blocking**, whatever the intensity setting says. At
  *Subtle* it would otherwise stay collapsed and hide both buttons.
- **The paused notice is worded**, not just a greying-out — dimmed keys on their own read as
  a bug.
- **Ignore survives the next keystroke.** The strip's own `dismissed` flag is deliberately
  re-armed by typing; the block override is not, or Ignore would buy exactly one character.
  It is dropped once the flagged content is resolved, so a later high-severity finding blocks
  afresh instead of riding a stale press.

Two paths are never blocked. Password fields are not scanned, so there is nothing to block
on. And **the crisis path never takes the keyboard away** — confiscating the keys from
someone mid-sentence about hurting themselves is the opposite of the response that path owes
them, so `requiresCrisisResponse` is excluded ahead of the severity check in
`InputGate.qualifies`.

Refusal lives in the IME, not the key views: alpha does not stop a touch, and every entry
point (keys, long-press alternates, emoji, paste, suggestions) has to converge on one guard
for the block to actually hold. Blocking follows the *local* verdict for the same reason the
strip does — waiting on a network call to decide whether the keys work would be the worst
imaginable place for a spinner. A verification downgrade out of HIGH gives the keys back.

Two open questions this leaves, both product calls rather than technical ones:

- **It ignores the intensity setting.** *Subtle* promises "never block sending" and now hard-
  blocks typing at HIGH, which reads as a contradiction. Either the copy changes or blocking
  becomes intensity-dependent. Supervision answers this for supervised devices only — a parent
  sets `blockAtHigh` explicitly — which leaves the unsupervised default exactly as contradictory
  as it was, and now visibly so by comparison.
- **A false positive is now expensive.** The corpus reports zero false positives at HIGH, but
  those numbers measure the rules against a corpus written alongside them (see Testing), so
  they are not evidence about real traffic. This is the feature that makes held-out data
  urgent rather than merely advisable.

### Autocorrect

Candidates come from the **platform spell checker** (`SpellCheckerSession`) rather than a bundled
dictionary — no multi-megabyte word list in the APK (which matters greatly for the eventual iOS
port and its ~30-50MB extension ceiling), inherits the user's configured language, stays current
without app updates. The cost is that it may be absent, so callers tolerate getting nothing.

They are then re-ranked by `KeyProximity`, which weights substitutions by physical key distance.
Plain edit distance treats every substitution as equally likely, which is wrong on a phone:
"hellp" is far more likely "hello" than "help", because `p` neighbours `o`. Transpositions cost
one cheap edit, since "teh" and "adn" are among the most common real errors.

Auto-apply is deliberately conservative — it needs a close match **and** a clear margin over the
runner-up, because a silent wrong replacement is much worse than a missed correction. Backspace
immediately after a correction reverts it; an autocorrect that cannot be taken back is precisely
why people switch the feature off, and then it helps nobody.

## The rest of the product

These apply to both paths. Detection, verification, supervision and the crisis response are
shared: the overlay and the keyboard are two front ends onto the same engine and the same
policy, which is why an override level or a review scope means the same thing whichever one a
family is running.

### AI verification

**Off by default, and no endpoint is configured**, so a default build cannot make a network
request even with the toggle on — `verify_base_url` is blank, and a blank URL means no client is
constructed at all. See `backend/README.md`.

The layer only ever *refines* the local verdict, which renders immediately and unconditionally.
Every failure path — no network, timeout, 429, 503, malformed body — returns null, and null means
keep the local result. There is no spinner and nothing to wait for, which is both a UX
requirement and what Apple's 4.4.1 demands.

`VerifyPolicy` holds the cost reasoning — debounce, content-addressed cache, daily ceiling,
escalation bypass — and is unit-tested branch by branch, because every branch is a spend
decision. Its real job is figurative language: "I want to die of embarrassment" versus "I want to
die". No word list separates those, and the local suppressors only cover cases someone thought to
enumerate in advance.

### Parent and child sides

A parent pairs a child's phone with an 8-character code, then sees what warnings that phone
gets and sets what the keyboard does there. Both sides ship in the same APK: the parent screen
is `ParentActivity`, the child's is a section of `SetupActivity`.

**No text ever crosses.** `SupervisionEvent` carries a category, a severity, an outcome and a
timestamp, and has no field for a message — the same discipline as `Finding`, for a stronger
reason, since this is the one record designed to be read by someone other than the person who
typed it. `SupervisionEventTest` asserts the absence against the serialized bytes rather than
the data class, because the bytes are what leave the phone, and the server's ingest schema is
`strict()` so a client that started attaching a snippet would be rejected rather than stored.
Nor is the host app recorded: which app a child was typing in is a materially larger
surveillance surface than "a self-harm warning fired at 4pm".

**Crisis events are reported**, on an explicit product decision. What that does *not* change is
the crisis moment itself — `requiresCrisisResponse` still forbids any "this will be reported"
messaging there, so the strip stays supportive and says nothing about a parent. The child is
told at pairing, on the supervision screen, and by the standing notification. The risk being
accepted is real and worth naming: for a child in an unsafe home, a parent alert on a crisis
disclosure is the thing that stops them reaching out at all.

**A policy sets a floor, not a value.** `minIntensity` is the least protection the child may
run with and they can still choose more; only `lockSettings` pins it exactly. `SupervisedSettings`
is the single place a caller asks what a setting actually *is*, because the failure mode is a
supervised value that applies in the settings screen and not in the keyboard. The child's own
preferences are never overwritten — the policy is applied on read — so unpairing does not leave
behind settings nobody picked.

`blockAtHigh` is also the first coherent answer to the standing contradiction below, where
*Subtle* promises never to gate and the block fires anyway: on a supervised device, someone
chose.

**The keyboard's entire involvement is one append.** The IME writes to `EventQueue` and stops;
every network call happens in the app process, driven by `SupervisionJobService`. On Android
that is the same process and the split buys nothing technical — it is a shape the iOS port
cannot do without, since 4.4.1 limits a keyboard extension to collecting activity that enhances
the keyboard itself, and reporting to a parent plainly is not that.

**Only a parent can unpair.** A supervised device that could quietly unpair itself is not
supervised, and a monitoring tool the monitored party can silently switch off reports "all
quiet" that means nothing. The child's genuine exit is uninstalling the keyboard, which the
parent sees as a device that stopped reporting — and the supervision screen says so in as many
words rather than leaving it implied by a missing button.

Two things this does not do, and one that is unverified:

- **Parents must be on Android.** Most parents of Android children are not. A web dashboard
  would have covered them for less work than the Android screen cost; this remains the single
  largest gap in the feature.
- **Parent access is account-based.** Email/password sign-in, revocable sessions, a one-time
  recovery code, account deletion, and additional caregiver invitations are implemented.
  Email delivery/verification still needs a production mail provider if the recovery-code
  model is replaced with emailed recovery.
- **The Play monitoring declaration is unverified.** `IsMonitoringTool` is written as
  `<meta-data>`, because Play documents it as an `<application>` attribute and no such
  attribute exists — AAPT rejects it outright. If review rejects the app for a missing flag,
  that element is the first thing to check.

### Parent and child are now separate sides of the app

`RoleActivity` is the launcher, and it asks once. A child device shows setup, sizing and the
supervision disclosure; a parent device shows the dashboard, the policy editor and reports, and
is never told to enable a keyboard it has no use for. Before this, both were reachable from one
screen — convenient on a test phone, and wrong everywhere else, because a supervised child was
one tap from the screen that administers their own supervision.

**A supervised device is locked to the child side.** `RolePolicy` checks that before it reads
the stored role at all, so a hand-edited preference is not a way out. This is the same argument
as "only a parent can unpair": a role chooser that let a child switch sides would be the unpair
button the product deliberately never built, reintroduced through the back door. A signed-in
parent has to sign out first, which needs the password to undo.

### What the child may do with a warning

Until now the product had exactly one answer to "the user disagrees with this warning": a button
that made it go away. That is right for an adult with a safety keyboard on their own phone, and
not obviously right for a nine-year-old whose parent installed it. `OverrideLevel` is the
parent's answer, and the levels are named for what the *child* can do rather than for how strict
the parent is being — because that is what every call site is actually asking.

| Level | Ignore button | At high severity |
|---|---|---|
| `FULL` | always | typing pauses, one tap resumes it |
| `LIMITED` | below high severity only | typing pauses until the text is removed |
| `NONE` | never | typing pauses until the text is removed |

`FULL` is the default and stays the default, so pairing on its own changes nothing — the same
rule `FamilyPolicy.DEFAULT` already followed for the intensity floor.

Three things this does not do, each deliberate:

- **It never deletes on its own.** "Force delete" is a button the child presses, not something
  that happens to them mid-sentence. Silently eating what someone typed — including the ninety
  per cent of the message that was fine — teaches people to compose somewhere else and paste in,
  which defeats the product entirely.
- **Removal is always available.** At every level, deleting the flagged span lifts the block
  with no button press. Without that, `NONE` would be a keyboard someone cannot type on and
  cannot get out of.
- **The crisis path is never gated, at any level.** A parent tightening the screws must not be
  able to take the keyboard away from a child who is trying to say they are in trouble.
  `InputGate.qualifies` checks `requiresCrisisResponse` ahead of everything, and
  `OverrideLevelTest` asserts it for every level.

An acknowledgement is also re-checked against the policy on every scan rather than trusted once.
A child who pressed Ignore before a sync, whose parent then moved them to `NONE`, is re-blocked
on the next keystroke instead of riding out the message on a permission that has been withdrawn.

### How much a parent can see

`ReviewScope` is the setting that decides whether the product's central privacy claim still
holds, so it is worth being blunt about what each rung costs.

| Scope | Warnings | Per-message tags | Message text |
|---|---|---|---|
| `CONCERNING_ONLY` *(default)* | yes | no | **never** |
| `THEMES` | yes | every message | **never** |
| `FULL_TEXT` | yes | every message | **yes** |

`CONCERNING_ONLY` is unchanged from what shipped: `SupervisionEvent` has no text field,
`SupervisionEventTest` asserts that against the serialized bytes, and the ingest schema is
`strict()`. None of that was dismantled. What was added is a rung a parent can *choose* to
climb.

**The middle rung is the one worth arguing for.** A parent asking "what is my kid actually up
to" is usually not asking to read their messages; they are asking whether this week was normal.
`THEMES` answers that from derived tags — school, a falling-out, three anxious days — while no
sentence ever leaves the device. Offering only "nothing" and "everything" would push families to
"everything" for a question that never needed it.

`FULL_TEXT` genuinely breaks the invariant, on purpose. It changes the Play Data Safety
declaration, changes what the child-facing disclosure says, and moves the product from "a
keyboard that warns you" to "a keyboard that reports you". It is worth having because some
families need it, and worth making loud because most do not: the parent gets a confirmation
dialog, and the child's supervision screen says in as many words that their messages can be
read.

Three properties that make the setting mean something:

- **Raising the scope never makes the keyboard stricter.** Collection and interruption are
  separate decisions; conflating them would mean a parent who wanted a better weekly summary
  accidentally made their child's keyboard harsher.
- **Narrowing reaches data already stored.** `applyScopeNarrowing` deletes or strips what was
  collected under the old scope, and the device drops its queued samples too. Otherwise "turn it
  off" would leave a fortnight of messages on the server, still readable.
- **The server enforces it, not just the client.** `POST /api/family/samples` refuses samples
  under `CONCERNING_ONLY` and strips text under `THEMES`, keyed on the family's stored policy.
  That is a stronger check than a schema assertion, because it is tied to what this family
  agreed to rather than to what the wire format happens to allow.

Password fields are never captured at any scope. `FieldPolicy` sits below all of this and no
parental setting reaches past it.

### Daily and weekly reports

The activity list does not answer the question parents actually ask. It lists warnings, and the
overwhelmingly common case is that there were none — so it says "no warnings" week after week,
which is either reassuring or useless depending on whether you believe it. What a parent wants
to know is closer to "is my kid alright", and that is a question about ordinary conversation.

A report is a short paragraph plus counts: themes, mood, things worth a look, and what the child
seems to be into. It is composed server-side by `lib/report.ts` from whatever the review scope
allowed to be collected, and cached per time bucket so opening the screen twice does not pay for
two model calls.

**The honesty constraints matter more than the prose.** A summary is generated text about a real
child, read by someone who will act on it:

- **It must not invent a concern.** A parent told their child seems withdrawn will go and ask
  them about it; if that came from four messages and a lexical mood guess, the model has started
  a conversation on no evidence. There is a volume floor below which no model is called at all,
  the model is told how many messages the period covers, and the prompt states explicitly that
  "nothing much stood out" is a good answer.
- **It must not launder weak evidence into confident prose.** A theme tag is a word-list match.
  A fluent paragraph built on tags reads far more authoritative than the tags deserve, which is
  why the report carries its counts alongside the narrative and states its own basis — and why
  the parent screen renders that basis line directly under the summary.
- **It never quotes the child.** Even under `FULL_TEXT`, where the model is given excerpts. A
  parent who wants to read the messages can; quoting turns the report into a surveillance
  artefact a child would find far more invasive than the summary.

With no model configured, or for a period too thin to summarize, `fallbackNarrative` composes
the report arithmetically from the counts. That is not a degraded mode to apologise for: for a
quiet week it is a *better* report, because "nothing notable happened" is the honest answer and
a model asked to write three sentences about it will find something to say.

### Themes, emotions, and why they are separate from categories

`Category` answers "is this dangerous". `Theme` answers "is this a normal Tuesday". They are
different vocabularies in different lists, matched by different automatons at different times —
the harm lexicon on every keystroke to decide whether to interrupt, the topic vocabulary once
per finished message to decide what to tell a parent later.

Keeping them apart is what makes the keystroke budget survive a 341-term topic list, and it is
what lets a reader tell from an entry alone whether a word can interrupt someone.

`Emotion` is the weakest signal in the product and should be read that way. Lexical mood
detection cannot see sarcasm, cannot weigh "not happy" against "happy", and will call a message
about a sad film sad. It is included because the aggregate is more honest than any single
reading: one message tagged `SAD` means nothing, thirty across a week against a baseline of four
means something. The report layer is therefore required to present emotions as a distribution
over a period, never as a verdict on a message.

### Crisis resources

`CrisisResources` resolves a helpline from the device region across ~24 countries, falling
back to [findahelpline.com](https://findahelpline.com) when the region is unknown. **No input
path returns nothing** — that invariant is asserted directly, because an earlier build
hardcoded `tel:988` (US/Canada only) and would have sent everyone else to a dead number.

### Suppressors

Ordinary speech is full of phrases lexically identical to serious ones: "want to die of
embarrassment", "cutting myself shaving". Firing a crisis intervention at those trivialises
the real thing and teaches users to ignore the strip, which is how the genuine signal gets
lost. `SuppressorRule` cancels a finding when a nearby phrase marks it figurative.

This is a blunt instrument and will never be complete — reliably telling hyperbole from intent
is a contextual judgement, which is what the AI layer is for. `GoldenCorpusTest` enforces
**zero tolerance** on crisis false positives, separately from the general false-positive rate,
since `requiresCrisisResponse` fires at any severity.

## The two apps

Both sides are tabbed shells over fragments, sharing `ui/Shell.kt`. They were single scrolling
screens until recently — 733 lines of layout for the child, 585 for the parent — and the problem
with that was not length. It was that everything was equally prominent, which is another way of
saying nothing was: the question people actually reopen the app to ask ("am I protected?") sat
six screens below the disclosure, and a parent checking on their child walked past six radio
groups of policy editor to reach the activity feed.

### Child

| Tab | What it answers |
|---|---|
| **Protection** | Am I covered, and what is left to do |
| **Look** | Where the warning sits, how big everything is, live preview |
| **Family** | Who can see this, or how to pair — hidden entirely when neither applies |
| **Privacy** | What the app can see, what leaves the device, and the counters |

The banner at the top of Protection is the point of the whole rework. It says one of three
things, and the third — *Almost there* — exists for the half-granted overlay, which can read
what someone types and cannot warn them about it. That state must never render as working, and
it is exactly where you land if you grant one permission and get distracted. The banner names
the specific missing piece rather than deferring to the checklist.

### Parent

| Tab | What it answers |
|---|---|
| **Children** | Who is paired, and what has happened |
| **Reports** | The daily and weekly summary, one child at a time |
| **Rules** | What the policy is |
| **Account** | Sign-in, recovery code, caregivers, and the destructive pair |

Signed out, there are no tabs at all — just the sign-in form. A navigation bar leading to four
sections that cannot load is a worse first impression than one focused screen.

### Details worth knowing

- **Sections are shown and hidden, not replaced.** `replace` would rebuild the keyboard preview
  and rerun the detection engine on every tab switch, and lose scroll position everywhere. The
  cost is that a hidden fragment stays RESUMED and never gets another `onResume`, so
  `SectionFragment` re-reads on `onHiddenChanged` as well. Miss that and Protection shows a stale
  answer to the one question it exists to answer.
- **The initial tab is selected before the navigation listener is attached.** Doing it the other
  way runs one transaction from the listener and a second explicitly — and because `commit` is
  asynchronous, the second pass finds nothing by tag and adds a *duplicate* fragment underneath
  the first.
- **The parent shell owns one client, one executor, and one overview.** Sections read it through
  `ParentHost` and are told when it changes. Fetching per section would mean four requests for
  one screenful of data and four different answers on a flaky connection.
- **A recovery code is stashed on the shell, not shown where it is produced.** The sign-in panel
  disappears the instant registration succeeds, so the code would flash up on a view being torn
  down. Account asks for it once and consumes it.
- **Neither shell calls `setSupportActionBar`.** The ActionBar would own the title and fight the
  shell setting it per tab; the toolbar is used as a plain view in both.
- **One spacing scale and one card style**, in `dimens.xml` and `themes.xml`. The old layouts
  picked margins ad hoc — 4dp here, 6dp there, 28dp somewhere else — which is most of why they
  read as a prototype.
- **Status colours are green and amber, not green and red.** "Not set up yet" is an unfinished
  task, not a failure, and painting it red makes an app that has never been opened look broken.
  Red stays reserved for the warning surface, where it means something.

### A bug this rework surfaced

Onboarding's final step said "type in the box below" and offered a text field. For an overlay
user that field can never fire a warning, because `MonitoredField` deliberately excludes
Keyguard's own package — so the app failed its own demonstration and looked broken rather than
careful. The step now offers the field only on the keyboard path, and tells overlay users to
open any other app instead, saying plainly that Keyguard ignores its own screens.

## Open questions

Things that are known to be unresolved rather than known to be finished.

**A parent may not consent to AI verification, but may consent to reading every message.** The
policy route blocks `aiVerification: FORCED_ON` on the stated principle that a parent cannot
consent to their child's typed text leaving the device — and it accepts `reviewScope:
FULL_TEXT`, which does exactly that, more thoroughly, since verification sends only flagged
spans while full review sends whole messages. Both rules cannot be right. The refinement was
left in place rather than quietly dropped as a side effect of shipping the other, because
changing it should be a decision rather than an accident. Either keep the principle, and
`FULL_TEXT` should require a child-side acknowledgement rather than a policy write; or drop it,
since a parent who may read every message can hardly be forbidden from consenting to a flagged
phrase being checked. The comment in `app/api/family/policy/route.ts` states this in place.

**The Play Accessibility API review is unverified.** The app moved from the keyboard policy to
a stricter one and nobody knows yet whether the declared purpose is accepted. This is the
largest schedule risk in the project and it cannot be retired without submitting.

**The overlay's block is weaker than the keyboard's was**, and the product has not decided how
much that matters. A hardware keyboard, voice input, or a keyboard that does not report its
window all defeat the shade. The current answer is to be honest — the warning stops claiming
typing is paused when it is not — but "honest about a weaker guarantee" may not be the right
answer for the `NONE` permission level, which parents will read as absolute.

**The overlay cannot tell a send from a deletion.** The IME could, because it owned the keys —
`SendInference` watched which mutation emptied the buffer, and `SendInferenceTest` has the whole
table. An overlay sees a field with text and then a field without, which in a chat app is
overwhelmingly a send but is indistinguishable from someone holding backspace. The current
answer is to guess conservatively: an emptied field is reported `SENT_INFERRED` unless the user
pressed *Remove it*, which is the one deletion the service performed itself and can be sure of.
That under-reports `heeded`, which is the safe direction — a parent told "the warning worked"
when the message was actually sent is the failure that matters — but it does mean the heeded
figures from an overlay device are a floor rather than a measurement, and they are not
comparable with the same figures from a keyboard device.

**Emotion tagging is the weakest thing in the product.** It is a word list, presented in
aggregate with hedging, and it may still be more than the evidence supports. The volume floors
and the fallback narrative are mitigations, not a fix; the fix is a real classifier or dropping
the feature.

**Reports have never been generated from real data.** Every constraint in the prompt is
reasoning about a failure mode, not a measurement. Whether the model actually declines to invent
concerns on thin data is an empirical question nobody has run.

**Sample storage has no integration test.** `appendSamples` stripping text and the samples route
refusing a withdrawn scope both need a live Redis, and the suite runs without one. The stripping
is a one-line expression under a comment saying so, which is the best that can be done in a unit
test; a store-backed test is the honest follow-up.

## Flavors

Two product flavors on a `mode` dimension, because the two builds make different promises to
store review and have to declare different things:

| | `solo` | `family` |
|---|---|---|
| Supervision | absent | present |
| Reports | absent | present |
| AI verification | absent | optional, off by default |
| Overlay + accessibility service | present | present |
| Permissions declared | **SYSTEM_ALERT_WINDOW only** | SYSTEM_ALERT_WINDOW, INTERNET, ACCESS_NETWORK_STATE, POST_NOTIFICATIONS, RECEIVE_BOOT_COMPLETED |
| `isMonitoringTool` | not declared | `child_monitoring` |
| Play policy category | Accessibility API policy | Accessibility API **and** Stalkerware policy |
| Data safety | no data collected | AI payloads, supervision events, and — at `FULL_TEXT` — message content |

`solo` gets there by removing manifest entries in `app/src/solo/AndroidManifest.xml`, not by a
runtime check. Dropping INTERNET is the load-bearing one: the app already made no requests with
a blank `verify_base_url`, but that is a property of a string resource and invisible to anyone
auditing the app. Without the permission it is a property of the package, which is what lets the
disclosure tell a user to go and verify the claim themselves.

**The solo claim changed shape when the overlay landed, and it is weaker than it was.** It used
to be "this build declares no permissions at all". It is now "this build can see what you type
and cannot transmit any of it" — because an accessibility service that reads text in other apps
is a materially larger capability than an IME that only saw its own keystrokes, and pretending
otherwise would be the kind of claim this file exists not to make. What survives intact is the
half users actually care about: with no INTERNET permission the OS will not let this build open
a socket whatever its code does.

Both flavors now ship the accessibility service, so both are reviewed under Play's Accessibility
API policy. `solo` is no longer an ordinary app from review's point of view, which is a real
cost of the overlay decision and is worth weighing if the solo build ever needs to ship first.

The supervision, reporting and AI code still compiles into `solo` and is unreachable —
`verify_base_url` is blank, and `SetupActivity`, `ParentActivity` and `SupervisionSync` all
refuse to build a client without it. `ContentCapture` additionally returns null on an unpaired
device regardless, since `SupervisedSettings.reportsEnabled` is false when nothing is
supervising.

## Installing

```sh
./gradlew :app:assembleSoloDebug
# → app/build/outputs/apk/solo/debug/app-solo-debug.apk

adb install -r app/build/outputs/apk/solo/debug/app-solo-debug.apk
```

Use `:app:assembleFamilyDebug` for anything involving supervision or AI verification. The
localhost endpoint and the cleartext exemption live in `app/src/familyDebug/`, a *variant*
source set rather than a build-type one, so `soloDebug` keeps the blank endpoint that makes it
the same app the store gets.

A first launch with setup unfinished opens the guided flow (`OnboardingActivity`): disclosure,
enable, choose, then a field to try it in. It is skippable, and `SetupActivity` behind it
carries every one of the same actions. `my address is 123 Main Street` triggers a high-severity
warning.

Both debug builds use applicationId `com.keyguard.app.debug`, so they install alongside a
release build — but not alongside each other.

### What to check first

**Start with the overlay, because none of it has ever run.** Everything in this block is a
first-run-on-device risk, and the pure logic passing on the JVM says nothing about how a real
`WindowManager` behaves.

- Grant both permissions from setup, open any chat app, and type `my address is 123 Main
  Street` → a warning appears **above the keyboard**, not over it and not off-screen.
- **The host app's keyboard must stay open.** If it closes the moment the warning appears, the
  `FLAG_NOT_FOCUSABLE` flag on the warning window has been lost — this is the single most
  likely regression and it looks like the host app crashing.
- Tap **Remove it** → only the flagged span disappears, and the rest of the message survives.
- At high severity the keyboard is covered and taps on it do nothing. Then check the honest
  failure: on a keyboard that does not report its window, nothing is covered *and the warning
  must not say typing is paused*.
- Switch *Where warnings appear* to top of screen → the warning moves and stops depending on
  the keyboard being found at all.
- Focus a password field → nothing appears, and nothing is scanned. Try a banking app and a
  password manager, not just a test field.
- Revoke the draw-over permission while the service is running → the service must stop warning
  rather than continuing to watch silently.
- Type in Keyguard's own setup screen → nothing appears, or the preview is monitoring itself.

Then the permission levels, on a paired device:

- Set *Nothing can be ignored*, sync the child, type a high-severity message → the keyboard is
  covered and there is **no Ignore button**, only *Remove it*.
- Press Ignore at *Ignore any warning*, then have the parent switch to *Nothing can be ignored*
  and sync → the child re-blocks on the next keystroke rather than finishing the message.
- Type something that fires the crisis path at *Nothing can be ignored* → the keyboard is **not**
  covered, and the overlay offers help rather than removal.

Then review scope and reports:

- At *Only concerning messages*, type ordinary messages → the parent's report says the period was
  quiet and no samples are stored.
- Switch to *Topics and mood*, type about school and a friend → the weekly report names those
  themes and still shows **no text**.
- Switch to *Everything they type* → the parent confirms, and the child's supervision screen
  immediately says their messages can be read.
- Switch back down → already-collected messages must be gone from the parent's view, not just
  absent from new ones.

Then the older keyboard path, which is the part that has been verified before. The interesting
behaviour there is the send/abandon inference, since it is the part the design depends on and
the part most likely to differ per host app:

- Type a flagged message and press the keyboard's own Enter → confirm gate appears.
- Type a flagged message and send it with an **in-app** send button (Instagram, Discord) →
  classified `SENT_INFERRED`. This is the case that cannot be intercepted, only observed.
- Select all and delete → must classify `ABANDONED_DELETED`, *not* sent.
- Focus a password field → the warning strip must never appear, and the keys must never block.

Then the block, where the failure mode is a keyboard nobody can type on:

- Type `my address is 123 Main Street` → keys dim, strip says typing is paused, **Ignore** and
  **Remove it** are both visible even at *Subtle* intensity.
- Backspace and the 🌐 switcher must still work while blocked.
- Press **Ignore**, then keep typing → must not re-block on the next keystroke.
- Delete the flagged text → keys come back with no button press; retype it → blocks again.
- Type something that fires the crisis path → the crisis strip appears and the keys stay live.

Supervision needs two devices and a running backend (`cd backend && npm run dev`; the
`familyDebug` build already points at `localhost:3000`, reachable from a physical device via
`adb reverse tcp:3000 tcp:3000`):

- Parent phone → *Set up parent mode* → *Get a pairing code*. Child phone → *Family
  supervision* → type the code → the supervised block replaces the pairing form and the
  standing notification appears.
- Type a flagged message on the child, then open the app there → the queue drains. Refresh on
  the parent → the event appears with a category and an outcome, and **no text**.
- Set a floor of *Insistent* on the parent, wait for a sync on the child → the child's
  intensity control snaps to Insistent and says *Set by your parent*.
- Tick *Don't let them change it* → the child's control goes read-only.
- Unpair from the parent → the child's next sync drops supervision, clears the queue, and takes
  the notification down. The pairing form comes back.
- Turn the backend off and repeat a sync → nothing changes on the child. A dead server must
  never look like being unpaired.

## Architecture

Detection runs in two layers, and the split is not just a cost optimization — Apple's
guideline 4.4.1 requires a keyboard extension to "remain functional without full network
access and without requiring full access", so the local layer has to stand on its own.

1. **Local (`:detect`)** — on-device, always runs, no network. Structured PII matchers plus
   a curated lexicon with danger levels and a contextual scorer.
2. **AI verify** — a cheap fast model that confirms or downgrades local findings and catches
   context-dependent harms the lexicon can't. Strictly a second opinion; the keyboard never
   blocks on it and never shows a spinner.

`:detect` is deliberately pure Kotlin with **zero Android dependencies**, so its tests run
on the JVM in milliseconds and the eventual iOS port only reimplements a thin matcher
against the same rule-pack JSON.

### `:detect` internals

| File | Role |
|---|---|
| `DetectionEngine.kt` | Public entry point. Construct once, reuse — building the automatons is the expensive part. |
| `PiiMatchers.kt` | Layer A: structured regex matchers. Run on the **original** text, since structure is the signal. |
| `Normalizer.kt` | Case folding, leetspeak, zero-width stripping, run collapsing — with span mapping back to the original buffer. |
| `AhoCorasick.kt` | Multi-pattern matcher. One O(n) pass regardless of lexicon size. |
| `LexiconMatcher.kt` | Layer B: word-boundary-validated lexicon pass, plus an evasion pass on the separator-stripped form. |
| `ContextScorer.kt` | Layer C: co-occurrence escalation, grooming-context escalation, overlap merging, AI-verify eligibility. |
| `RollingContext.kt` | Time-windowed conversation memory. In-memory only, never persisted. |
| `FieldPolicy.kt` | Hard gate: never scan password fields. |
| `Themes.kt` | `Theme`, `Emotion`, `MessageSummary` — the reporting vocabulary, and why it is coarse. |
| `ThemeScanner.kt` | The topic and mood pass. Same trick as the lexicon, deliberately off the keystroke path. |
| `resources/rules/pack-v0.json` | The rule pack. Data, not code — retuning ships OTA without an app release. |

### `:app` internals

| File | Role |
|---|---|
| `KeyguardInputMethodService.kt` | The IME. Scans synchronously per keystroke — a local scan is ~100µs, so no debounce and no spinner. |
| `input/SendInference.kt` | Sent-vs-abandoned state machine. Pure logic, no Android imports, fully unit-tested. |
| `input/InputGate.kt` | Whether the keys are live. High severity stops input until resolved or ignored. Pure and tested. |
| `input/ShadowBuffer.kt` | Our own view of the field, since the platform's is unreliable. Memory-only. |
| `keyboard/KeyboardLayout.kt` | Key specs per plane. Every row must sum to `ROW_UNITS` or columns misalign. |
| `keyboard/KeyboardView.kt` | Programmatic key grid with shift/caps, plane switching, backspace repeat, edge insets. |
| `keyboard/Highlighter.kt` | Builds the red-highlighted echo of the user's text. `plan()` is pure and tested; `apply()` is the Spannable shim. |
| `keyboard/WarningStrip.kt` | The warning surface, including the separate crisis and confirm-send states. |
| `settings/Appearance.kt` | All user-adjustable sizing, with ranges and clamping. |
| `settings/AppearanceControl.kt` | The sliders declared as data, so ranges and read/write paths live in one place. |
| `settings/Settings.kt` | Persistence: sizing, warning intensity, and the disclosure gate. |
| `SetupActivity.kt` | Disclosure, enable flow, sizing sliders with a live preview, a field to try it in, and the child side of pairing. |
| `ParentActivity.kt` | The parent's view: pairing code, activity per child, policy editor. |
| `net/ApiClient.kt` | One HTTP surface and one install identity for everything that talks to the backend. |
| `family/SupervisionEvent.kt` | What a parent sees. No text field, by construction. |
| `family/FamilyPolicy.kt` | What a parent has decided. A floor, not a value. Pure and tested. |
| `family/SupervisedSettings.kt` | The single place a caller asks what a setting actually is. |
| `family/EventQueue.kt` | Bounded, persisted queue. The IME's only involvement in reporting. |
| `family/SupervisionSync.kt` | Policy pull and event upload, from the app process — never the IME. |
| `family/SupervisionNotice.kt` | The standing notice a monitored user is owed. |
| `family/PairingCode.kt` | Crockford base32, with the look-alikes folded rather than rejected. |
| `family/OverrideLevel.kt` | How much authority the child has over a warning. Pure and tested. |
| `family/ReviewScope.kt` | How much of what they type a parent can see. Three rungs, default unchanged. |
| `family/ActivitySample.kt` | One ordinary message, and `ContentCapture` — the one gate that decides what may be recorded. |
| `family/SampleQueue.kt` | The sample queue. Separate file from `EventQueue` so a scope drop can delete one and not the other. |
| `family/FamilyReport.kt` | What a parent reads. Carries its own basis, so a tag-built summary is distinguishable from a text-built one. |
| `settings/DeviceRole.kt` | Parent or child, and `RolePolicy` — why a supervised device cannot switch. |
| `RoleActivity.kt` | The launcher. Asks once, then routes and gets out of the way. |

### Sizing

Keyboard and warning-strip dimensions are user-adjustable, not baked into `dimens.xml`.
Seven controls: keyboard height, letter size, key gap, side padding, bottom padding, warning
text size, and warning message lines.

The settings screen renders a **live preview** built from the real `KeyboardView`,
`WarningStrip`, and `DetectionEngine` — not a mock — so the preview cannot drift from what
the keyboard actually draws, and finding a comfortable size doesn't mean switching apps
between every adjustment.

Values are always clamped on read as well as write (`Appearance.sanitized()`). A persisted
zero-height keyboard would be unrecoverable from inside the keyboard itself, so this is
load-bearing rather than defensive habit.

### Why one module instead of `:keyboard` + `:app`

The plan sketched these separately, but an IME and its container UI must ship in a single
APK, so the boundary would add indirection without buying isolation. Kept as one module
until something genuinely needs sharing with a second artifact.

### Design decisions worth knowing

- **`Finding` carries no raw text**, only offsets. No caller can accidentally log, persist,
  or transmit what the user typed; the UI slices the buffer itself to render.
- **Word-boundary validation by default** on lexicon terms. Without it, a term like "ass"
  fires inside "class" and "assignment" (the Scunthorpe problem).
- **Run collapsing uses a threshold of 3**, not 2. Collapsing pairs would turn "cool" into
  "col"; leaving 3+ alone would let "fuuuck" through.
- **Luhn validation on card numbers.** Without it the highest-severity rule in the pack
  fires on every long digit run — order ids, game ids, dates.
- **`SELF_HARM` is not a privacy warning.** It sets `requiresCrisisResponse` and routes to
  crisis resources and supportive copy — never a scolding alert, never a reporting threat.
- **Severity 0 findings render nothing.** They exist to route a case the local rules
  genuinely can't resolve to AI verification.
- **The warning strip is the primary UI, not in-field underlining.** An IME cannot reliably
  style text inside another app's field — hosts strip spans from committed text, and
  composing-region styling only covers a transient region. The strip is fully ours.
- **Flagged content is highlighted in red**, on explicit product-owner instruction. The
  trade-off accepted: red is every platform's spellcheck colour, so some users will read a
  safety warning as a typo and skim past it. Mitigated by never carrying the signal with
  colour alone — spans are bolded and the strip always shows a worded message, which also
  keeps it usable for red-green colour blindness. Two red weights keep serious cases
  distinguishable from moderate ones. The self-harm path stays teal: that is a support
  message, not problematic content, and styling it as an error would be wrong.
- **Every keyboard row sums to the same weight** (`KeyboardLayout.ROW_UNITS`), which is what
  makes columns line up. The 9-key middle row is indented half a key on each side rather
  than stretched to full width. `KeyboardLayoutTest` asserts this so misalignment is caught
  mechanically instead of by eye.
- **Sent vs. abandoned is decided by which mutation emptied the buffer**, not by whether a
  delete was ever seen. Backspacing partway and then sending is a send; a selection wipe is
  a deletion. See `SendInferenceTest` for the full table.
- **A block must always show its own exits.** `StripState.Warning.blocking` forces the action
  row visible independently of `expanded`, because the two buttons are the only way out and a
  collapsed strip would hide both behind a tap nobody knows to make.
- **The blocking strip keeps a neutral background.** Flooding it red would put the red
  highlight spans on a red field and make the flagged text — the exact thing the user has to
  find and delete — the hardest part to read.
- **One intensity setting, not a popup toggle.** Subtle / Standard / Insistent covers the
  same range as separate switches without exporting an unresolved design argument to users.

## Building

Requires JDK 17+. Android Studio's bundled JBR works:

```sh
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"   # adjust per machine
./gradlew :detect:test
```

On Windows PowerShell:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :detect:test
```

`local.properties` holds the machine-local Android SDK path and is not committed.

## Testing

```sh
./gradlew :detect:test            # full suite
./gradlew :detect:test --info     # includes recall / false-positive / latency numbers
```

`GoldenCorpusTest` is the quality regression gate, asserting two numbers that pull against
each other:

- **Recall ≥ 0.90** on labelled positives — keeps the product honest about what it catches.
- **False-positive rate ≤ 0.05** on ordinary chat — this is what actually decides whether
  the keyboard survives on someone's phone. A safety keyboard that interrupts normal
  conversation gets switched off within a day, and then it protects nobody.

Current: recall 1.000 (71/71), false positives 0.000 (0/63), zero crisis false positives,
median scan ~136µs for a 500-char buffer against a 5ms budget.

The lexicon grew from 43 harm terms to 210, suppressors from 12 to 69, and the corpus from
33/28 to 71/63 alongside it. Scan time moved from ~100µs to ~136µs — a five-fold lexicon for a
third more time, which is the Aho-Corasick pass doing what it was chosen for.

**The added corpus cases target the ambiguous half of the new lexicon**, not the obvious half.
"send me nudes" proves nothing that "send nudes" did not; "diet coke", "5 kms away", "lean
back", "my addy", "he's such a fag for smoking that cigarette" and "I'll kill you in the game
later" are the entries that decide whether the expansion cost precision. They caught four real
regressions on first run, and one term — `kms` — was removed outright as a result.

**`kms` is worth knowing about**, because it constrains every future SELF_HARM addition.
`requiresCrisisResponse` fires at *any* severity, so an ambiguous self-harm term cannot be made
safe by lowering it; the only options are a suppressor that is exhaustive or removal. "kms" is
real self-harm slang and collides completely with kilometres, so it went. Any SELF_HARM term
added to the pack inherits that constraint.

**Read these numbers with suspicion.** The rules and the corpus were still authored together, so
they measure internal consistency, not real-world quality. Real precision/recall numbers require
held-out data the engine's author never saw — expanding the corpus from published sources
(Presidio recognizers, Perspective API taxonomy, OLID/HateXplain, PAN12) is a prerequisite for
trusting them. The slur list in particular is a placeholder for a published taxonomy rather than
a curated set.

## Privacy invariants

These are load-bearing for both users and store review. Do not weaken without reading the
plan's store-risk section.

- Password and secure fields are never scanned (`FieldPolicy`), and no network call is made
  for them.
- The shadow buffer and `RollingContext` are memory-only and must be cleared on input-field
  change, so context never crosses apps or conversations.
- Only the minimal flagged window is ever eligible to leave the device — never the full
  keystream.
- No raw user text in logs, crash reports, or telemetry. Telemetry is category, severity,
  and outcome counters only.
- **Warning reporting carries no text and no host app, at every review scope.** Category,
  severity, outcome, timestamp. Asserted against the serialized payload in
  `SupervisionEventTest` and enforced again by a `strict()` schema on the server, so adding a
  snippet would have to be done twice and on purpose. `ReviewScope` does not touch this stream.
- **Ordinary messages are recorded only above `CONCERNING_ONLY`, and their text only at
  `FULL_TEXT`.** `ContentCapture` is the single gate and is tested as a decision table; the
  server refuses or strips independently, keyed on the family's stored policy. A parent who
  narrows the scope has the already-stored data deleted or stripped, and the device drops its
  queue — see `applyScopeNarrowing` and `discardSamplesIfScopeNarrowed`.
- **The accessibility service never records which app a field belonged to.** The package name is
  read in memory to decide whether to look at a field and is attached to nothing that leaves the
  device. This is the same rule the IME followed, restated in `MonitoredField` because that is
  the first place in the codebase to have the package name conveniently to hand.
- **The overlay reads only editable fields, and never password ones.** Two independent password
  checks — the platform's `isPassword` flag and `FieldPolicy` on the declared input type —
  because either alone has known gaps with custom and WebView inputs. Non-editable nodes are
  skipped entirely, so "what my child wrote" never becomes "everything my child looked at".
- **The monitored user is told.** A supervised device shows a standing notification and an
  undismissable block on the setup screen naming exactly what a parent can and cannot see.
