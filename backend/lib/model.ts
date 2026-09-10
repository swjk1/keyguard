import { anthropic } from '@ai-sdk/anthropic';
import type { LanguageModel } from 'ai';

/**
 * Resolves which model to call.
 *
 * Two paths, because the blocker for getting this running should never be "which kind of key
 * do you happen to have":
 *
 *  - **AI Gateway** (preferred): plain `provider/model` strings, routed by Vercel. Gives cost
 *    observability and lets the tier change by environment variable, which is what the
 *    cost benchmark needs.
 *  - **Direct Anthropic**: used when `ANTHROPIC_API_KEY` is set. Needs no Vercel account, so
 *    local development works with nothing but an API key.
 *
 * Direct wins when its key is present, since an explicitly-set provider key is a clear signal
 * of intent.
 */

export type Tier = 'standard' | 'escalation' | 'summary';

const DEFAULTS: Record<Tier, { gateway: string; direct: string }> = {
  standard: {
    gateway: 'anthropic/claude-haiku-4.5',
    direct: 'claude-haiku-4-5-20251001',
  },
  escalation: {
    gateway: 'anthropic/claude-sonnet-5',
    direct: 'claude-sonnet-5',
  },
  /**
   * Report writing.
   *
   * A better model than the standard tier despite not being latency-critical, which is the
   * opposite trade from `verify`. Verification runs on a keystroke cadence and is throttled by
   * cost; a report runs at most once an hour per child, is cached, and is the only thing a
   * parent actually reads. Spending Sonnet-level money on roughly twenty-four calls a day per
   * family is affordable in a way that spending it per message never was - and the job is
   * genuinely harder, since it has to say something true and useful about a week of a
   * teenager's life without overreaching from thin evidence.
   */
  summary: {
    gateway: 'anthropic/claude-sonnet-5',
    direct: 'claude-sonnet-5',
  },
};

export interface ResolvedModel {
  model: LanguageModel;
  /** Human-readable id, returned to the client so the benchmark can attribute cost. */
  id: string;
  via: 'gateway' | 'anthropic';
}

export function resolveModel(tier: Tier): ResolvedModel {
  const override =
    tier === 'escalation'
      ? process.env.KEYGUARD_ESCALATION_MODEL
      : tier === 'summary'
        ? process.env.KEYGUARD_SUMMARY_MODEL
        : process.env.KEYGUARD_MODEL;

  if (process.env.ANTHROPIC_API_KEY) {
    const id = override ?? DEFAULTS[tier].direct;
    // A gateway-style "provider/model" override is still usable here; strip the prefix.
    const bare = id.includes('/') ? id.slice(id.indexOf('/') + 1) : id;
    return { model: anthropic(bare), id: bare, via: 'anthropic' };
  }

  const id = override ?? DEFAULTS[tier].gateway;
  // A bare string is resolved by the SDK's global gateway provider.
  return { model: id, id, via: 'gateway' };
}

/** True when some usable credential is configured. Lets `/api/health` report honestly. */
export function isModelConfigured(): boolean {
  return Boolean(process.env.ANTHROPIC_API_KEY || process.env.AI_GATEWAY_API_KEY || process.env.VERCEL);
}
