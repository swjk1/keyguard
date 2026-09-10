# Keyguard verification backend

Second-opinion verification for spans the on-device engine flagged.

## What this is not

**Not the primary detector.** The local engine has to stand alone — Apple's guideline 4.4.1
requires a keyboard extension to "remain functional without full network access and without
requiring full access" — so this endpoint only ever *refines* a verdict the device already has.
If it is slow, throttled, or down, the keyboard keeps working with its local result. Being
throttled costs a user refinement, never protection.

## What it is genuinely for

Telling figurative language from real intent. "I want to die of embarrassment" and "I want to
die" are lexically near-identical and mean completely different things, and no word list will
ever separate them. That single capability is most of the value here: a crisis warning fired at
someone joking about a heatwave trivialises the real thing and trains them to ignore every
future warning.

## Routes

| Route | Purpose |
|---|---|
| `POST /api/register` | Exchanges a client-generated UUID for a signed install token. Anonymous — no account, no email, no device identifier. |
| `POST /api/auth/register` | Creates a parent account and returns a revocable session plus a one-time recovery code. |
| `POST /api/auth/login` | Signs a parent in on another device. |
| `POST /api/auth/recover` | Rotates the password, recovery code, and every active session. |
| `GET /api/auth/me` / `POST /api/auth/logout` | Reads or revokes the current parent session. |
| `DELETE /api/auth/account` | Deletes the account, sessions, family, child memberships and activity. |
| `POST /api/verify` | Reviews flagged spans. Structured output via `generateObject`, so the SDK retries a malformed shape and the client never parses half-valid JSON. |
| `GET /api/rules?since=N` | Serves the rule pack, reading the same file `:detect` bundles so the two cannot drift. |
| `POST /api/family/create` | Turns a parent's install into a family. Idempotent. |
| `POST /api/family/code` | Mints a single-use pairing code, 10-minute TTL. |
| `POST /api/family/join` | Redeems a code and binds a child device. |
| `GET  /api/family/policy` | The child device asking what to enforce. |
| `PUT  /api/family/policy` | The parent changing it. Version is server-assigned. |
| `POST /api/family/events` | The child's queue of warnings. |
| `GET  /api/family/activity` | The whole parent screen: policy plus every child's events. |
| `DELETE /api/family/child` | Unpairing. Parent only — there is no child-side counterpart. |
| `POST /api/family/parent-code` / `parent-join` | Adds another authenticated caregiver with a single-use code. |

## Family supervision

Storage is Upstash Redis via `lib/store.ts`, and **it fails closed** — the opposite of the rate
limiter directly above it. With no store there is no policy to serve and nowhere to put an
event, and inventing a permissive answer would tell a child's device it is unsupervised while a
parent believes otherwise. Routes return 503 and devices keep their cached policy.

The event schema in `lib/family.ts` is the enforcement point for the product's central promise:
category, severity, outcome, timestamp. Nothing else. Zod is `strict()` on ingest, so a client
build that started attaching message text would be rejected here rather than quietly stored —
which is the check worth having, because the client is the easy thing to change and the server
is the thing that would have to keep the text.

Two limits beyond the shared family budget: pairing is capped at 10 attempts an hour per
install, since it is the one route where the caller is guessing at a secret; and event history
is capped at 500 per child with a 30-day TTL.

**Not exercised.** Like `/api/verify`, no request has been served. Everything below is a
design, not a measurement.

## Cost controls

The planning meeting's longest argument was per-user inference spend. Three mechanisms:

1. **Client-side policy** (`VerifyPolicy` in the app) — debounce, content-addressed cache, and
   a local daily ceiling. Most declined calls never leave the device.
2. **Server-side rate limits** (`lib/ratelimit.ts`) — a per-install daily cap plus a burst
   limiter. Authoritative, and retunable from configuration once the benchmark produces real
   numbers, without shipping an app update.
3. **Model routing** — plain `"provider/model"` strings through the AI Gateway, so the tier can
   be changed by environment variable. Haiku 4.5 by default; a stronger model only when the
   device asks for escalation.

If Redis is absent the limiter **fails open**. A misconfigured limiter must not take safety
verification offline — overspend is recoverable, a broken product is worse.

## Setup

```sh
npm install
cp .env.example .env.local     # then fill in KEYGUARD_TOKEN_SECRET
npm run dev
```

`KEYGUARD_TOKEN_SECRET` is required — generate with `openssl rand -base64 32`. Rotating it
invalidates every issued token; clients re-register automatically on a 401.

Provision Upstash Redis through the Vercel Marketplace rather than hand-rolling it:

```sh
vercel integration add upstash
vercel env pull
```

## Deploying

Not deployed. The current workspace has no linked Vercel identity and no Redis credentials.
See `DEPLOYMENT.md`; accounts and family routes intentionally return 503 until both Upstash
Redis variables are configured. AI is optional for a local-only first release.

Then point the app at it by setting `verify_base_url` in
`app/src/main/res/values/strings.xml`. **While that string is blank the app creates no client
at all**, so a default build cannot make a network request even if the user switches the
toggle on.

## Status

Typechecks, builds, and seven credential/family-contract tests pass. **Not live-tested** — no
account or family request has reached a configured Redis deployment, and the prompt has not
been evaluated against real messages.
The verdict quality, the latency budget, and the per-user cost are all unmeasured.
