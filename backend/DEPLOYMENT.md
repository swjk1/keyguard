# Production deployment

Keyguard's account and family routes deliberately fail closed unless Redis is configured.
Do not point a release app at a deployment until `/api/health` returns HTTP 200 with `ok: true`.

## Required configuration

- `KEYGUARD_TOKEN_SECRET`: at least 32 random bytes; rotating it invalidates child install tokens.
- `UPSTASH_REDIS_REST_URL` and `UPSTASH_REDIS_REST_TOKEN`: durable account, session, family and rate-limit storage.
- AI credentials are optional for the local-only launch. If enabled, configure the Vercel AI Gateway or `ANTHROPIC_API_KEY` and validate the child-data processing terms first.

## Release sequence

1. Link `backend/` to a Vercel project and provision Upstash Redis in the same intended data region.
2. Add production and preview environment variables. Use separate Redis databases and token secrets.
3. Deploy a preview and run `npm run smoke -- https://preview.example`.
4. Verify account creation/recovery/deletion, two-parent access, child pairing, policy sync, event upload, notification disclosure and unpairing on physical devices.
5. Promote to production, run the smoke test again, and set the Android release `verify_base_url` to the production HTTPS origin.
6. Configure uptime monitoring on `/api/health` and alerts for elevated 401, 429 and 5xx rates without logging request bodies.

The smoke test creates a random parent account and deletes it in a `finally` block. It never calls the paid AI verification endpoint.
