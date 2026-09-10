# Keyguard privacy policy

_Last updated: 9 September 2026_

Keyguard warns you before you send something you might regret. To do that it has to read what
you are typing. This page explains exactly what that means, what happens to that text, and what
never leaves your phone.

This policy covers the Keyguard Android app. It is written to be read, not to be survived — if
anything here is unclear, that is a fault worth reporting.

## Two versions, and which one you have

Keyguard ships in two builds that make genuinely different promises. **Everything below says
which one it applies to**, because the difference is the whole point of having two.

| | **Keyguard** | **Keyguard Family** |
|---|---|---|
| Warns you as you type | yes | yes |
| Can send anything off your phone | **no — it has no internet permission** | only what is described below |
| Parent supervision | absent | optional, and only after pairing |

If you are not sure which you have, open **Settings → Apps → Keyguard → Permissions**. The
plain version does not list Internet. That is not a claim you have to take on trust; see
[Permissions](#permissions).

## How Keyguard sees what you type

There are two ways to run Keyguard, and they see different things. You choose one during setup.

### As an overlay (the usual way)

Keyguard runs as an **accessibility service** and floats a warning over the top of whatever
keyboard you already use. You keep typing on Gboard, or SwiftKey, or whatever you like.

This is the more capable of the two, and it is only fair to say so plainly. In this mode
Keyguard can read **the text in the field you are typing into**, in any app, whatever keyboard
you used to type it. That includes text you pasted in, dictated, or swipe-typed.

It still cannot see:

- messages you **receive**
- anything else on screen — labels, other people's messages, buttons, notifications
- password fields, ever — see below
- which apps you use, or when: Keyguard checks the app's name only to decide whether a field is
  one it should ignore, and never records it
- your contacts, location, photos, files, or accounts

Android makes you switch an accessibility service on yourself, in system settings, and warns you
when you do — because a service like this **can** be used to watch everything. That warning is
fair, and the rest of this page is our answer to it.

### As a keyboard

Keyguard can also run as its own keyboard, the way it originally did. In this mode it sees
**only what you type on the Keyguard keyboard** — nothing you type elsewhere, and nothing
already on screen.

This mode needs no accessibility service. If you would rather Keyguard saw less, this is the
option that gives it less.

## What happens to what you type

**Checking happens on your phone.** Keyguard compares what you type against a list of patterns
built into the app. That comparison runs on the device, in memory, as you type.

**Nothing is stored.** The text is held only while you are typing and is discarded when you move
to another field or another app. It is not written to a database, a file, or a log, and it is
not retained after you close the app.

**Password fields are never checked at all.** When you focus a password or other secure field,
Keyguard switches off entirely for that field. It does not read it, does not warn on it, and
does not record anything about it. Keyguard checks this two separate ways, because the single
check that most apps rely on has known gaps.

**Nothing you type is ever written to logs or crash reports.** This holds internally too: the
part of the app that reports a match records only *where* in the text it matched, never the text
itself, so no part of Keyguard can log or store what you wrote even by accident.

**In the plain version, nothing is transmitted.** That build has no internet permission and no
server configured. It cannot send what you type anywhere, because there is nowhere for it to
send it and no way to reach it.

## What is stored on your phone

Keyguard saves a small amount of data in its own private storage. None of it leaves the phone in
the plain version, and all of it is deleted when you uninstall the app.

| What | Why |
|---|---|
| Your settings | Warning intensity, keyboard size, where the warning appears, autocorrect and haptics |
| Whether you accepted the disclosure | So you are not asked again |
| Counts of warnings shown, ignored, and acted on | The "Is it working?" figures on the settings screen |
| Words you have added by using them | So autocorrect stops fighting you over names and slang |

The counts are **numbers only** — how many warnings appeared and what you did about them. They
do not include what you typed, which app you were in, or when. You can clear them at any time
from the settings screen.

## Permissions

**The plain version requests one permission: "display over other apps".**

That is what lets the warning appear above your keyboard. Without it Keyguard would be able to
read what you type and have no way to warn you about it — so the app refuses to run at all in
that state, rather than watching silently.

**It does not hold the internet permission.** That is the part of this policy you do not have to
take on trust: without that permission Android will not allow the app to open a network
connection, whatever its code tries to do. You can check this yourself in **Settings → Apps →
Keyguard → Permissions**, or on the app's Play listing.

The accessibility service is not a permission in the usual sense — you switch it on yourself in
system settings, and you can switch it off there at any time. Keyguard stops seeing anything the
moment you do.

Keyguard does not request contacts, location, storage, camera, microphone, or any other personal
data, and has no way to reach them.

## Keyguard Family: what a parent can see

Everything in this section applies **only** to the Family version, and **only** after a child's
phone has been paired with a parent using a code. An unpaired phone reports nothing to anyone,
and pairing on its own changes nothing about how the keyboard behaves.

**A supervised phone always says so.** There is a standing notification, and a block on the
setup screen that cannot be dismissed, naming exactly what the parent can see. If you are using
a supervised phone, that screen is the authoritative answer — not this page.

**Only the parent can end supervision.** A supervised phone cannot quietly unpair itself, because
a monitoring tool the monitored person can switch off silently reports "all quiet" that means
nothing. Uninstalling Keyguard is the real exit, and the parent sees a device that stopped
reporting.

### What is sent depends on a setting the parent chooses

The child's phone tells them which of these is in force.

**Only concerning messages** *(the default)*. When a warning happens, the phone sends the *kind*
of warning, how serious it was, what you did about it, and when. **It never sends what you
typed**, and it never sends which app you were in. There is no field in that record for a
message, and the server rejects one that tries to add it.

**Topics and mood.** As above, plus a few words describing what each message was *about* —
school, friends, gaming, a falling-out — and a rough mood. **It still never sends what you
typed.** These tags cannot be turned back into a sentence, name a person, or identify a
conversation.

**Everything you type.** The messages themselves are sent and the parent can read them. This is
a big step, most families do not need it, and a child on a phone where it is switched on is told
so in as many words on the setup screen.

**Password fields are excluded at every level.** No parental setting can change that.

**If a parent turns a setting back down, what was already collected is deleted.** Messages
collected under "everything you type" do not sit on the server afterwards.

### Reports

A parent can see a daily and weekly summary, written from whatever the setting above allowed to
be collected. Where no messages were collected, the summary is written from the counts and the
topic tags only, and it says so on the screen the parent reads.

### Crisis messages are included

If Keyguard detects language suggesting a young person may be at risk of harming themselves, a
supervised phone reports that a warning of that kind happened — the same category, severity and
outcome as any other. This is a deliberate decision and it is worth naming the risk being
accepted: for a young person in an unsafe home, a parent being alerted may be the thing that
stops them reaching out at all.

What it does **not** change is the moment itself. Keyguard never says anything about reporting,
parents, or consequences while showing a crisis message. It offers a helpline and support.

### AI checking

The Family version can optionally send a **flagged phrase**, plus up to a few recent messages
for context, to be checked by an AI — mainly so it can tell "I want to die of embarrassment"
from something serious. It is **off by default**, your full typing is never sent, and nothing is
ever sent from a password field. A parent can turn this off for a child's phone; they cannot
turn it on for them.

## Children

Keyguard is designed to be safe for a young person to use. The plain version reports nothing to
anyone: there is no account, no parent, and no third party who can see what a Keyguard user
types or is warned about.

The Family version reports to a paired parent, on the terms set out above, and always tells the
person being monitored that it is doing so.

## Crisis support

If Keyguard detects language suggesting you may be at risk of harming yourself, it offers a
helpline for your region. That happens entirely on your phone, using a list built into the app.
Keyguard never takes your keyboard away in that moment, whatever else is configured — including
on a supervised phone with the strictest settings a parent can choose.

In the plain version nothing about it is reported to anyone, no message is sent, and no record
is kept beyond the anonymous counter described above.

## Changes to this policy

If a future version of Keyguard collects or transmits anything not described here, this policy
will be updated before that version is released, and the app will ask you before anything leaves
your phone.

## Contact

Questions about this policy, or about anything Keyguard does: [contact address to be added
before publication].
