import { NextResponse } from 'next/server';

/**
 * Proxies the harness's model tab to the local Python probe server.
 *
 * The model is a PyTorch checkpoint; it lives in `training/` and is served by
 * `python -m scripts.serve_model`. The browser could call that server directly, but then it
 * would need CORS opened on a port bound to the developer's own machine, and "any website you
 * visit can POST to your localhost" is a bad trade for saving one file. Proxying keeps the
 * fetch same-origin and leaves the Python server bound to 127.0.0.1 with no CORS at all.
 *
 * It lives under `app/preview/` rather than `app/api/` on purpose. The preview README says to
 * delete `app/preview/` before deploying; a route parked in `app/api/` would survive that
 * deletion and quietly ship a proxy to an arbitrary URL. Keeping it inside the folder means the
 * one documented step removes the whole feature. The production guard below is the second line
 * of defence for when someone deploys without reading.
 */

export const dynamic = 'force-dynamic';

const MODEL_URL = process.env.KEYGUARD_MODEL_PROBE_URL ?? 'http://127.0.0.1:8731';

/** Short: the probe is on the same machine, and the harness scores on every keystroke pause. */
const TIMEOUT_MS = 4000;

async function forward(path: string, init?: RequestInit) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  try {
    const upstream = await fetch(`${MODEL_URL}${path}`, { ...init, signal: controller.signal });
    return NextResponse.json(await upstream.json(), { status: upstream.status });
  } catch {
    // Not an error worth a stack trace: the probe server being off is the normal state of this
    // harness, since the model tab is the only thing that wants it. The scene renders this as
    // an instruction rather than a failure.
    return NextResponse.json(
      {
        offline: true,
        hint: 'Start the probe: cd training && python -m scripts.serve_model --port 8731',
        url: MODEL_URL,
      },
      { status: 503 },
    );
  } finally {
    clearTimeout(timer);
  }
}

export async function GET() {
  if (process.env.NODE_ENV === 'production') return new NextResponse(null, { status: 404 });
  return forward('/api/meta');
}

export async function POST(request: Request) {
  if (process.env.NODE_ENV === 'production') return new NextResponse(null, { status: 404 });
  const body = await request.text();
  return forward('/api/score', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body,
  });
}
