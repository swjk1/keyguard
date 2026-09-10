'use client';

import { useCallback, useEffect, useRef, useState } from 'react';

import { CATEGORY_LABEL, scan, type RulePack } from '../detect';

/**
 * The model tab — the two detection layers on the same sentence, side by side.
 *
 * The other scenes answer "what does the child see". This one answers the question that
 * decides whether the model ships at all: **where do the rules and the model disagree, and
 * which one is right?**
 *
 * Running them separately in two tools makes that comparison a memory exercise. Running them on
 * the same keystroke makes it a glance. The three outcomes each mean something different:
 *
 * - *both fire* — the model is corroborating a rule, which is the boring, good case.
 * - *rules only* — the model missed something a word list caught. Cheap to fix by training,
 *   and until it is fixed the rule layer is still carrying it, so nothing is unprotected.
 * - *model only* — the interesting one. Either a genuine catch no word list could have made
 *   ("got the house to myself tonight"), which is the entire argument for having a model, or a
 *   false positive that would now interrupt a child the rules would have left alone.
 *
 * The rule side is `detect.ts`, the same cut-down port the chat and document scenes use, so a
 * disagreement here is a disagreement the harness's other tabs would also have shown.
 *
 * The model side needs `python -m scripts.serve_model` running in `training/`. When it is not,
 * this renders as an instruction rather than an error — the probe being off is the normal
 * state of this harness.
 */

interface Entity {
  start: number;
  end: number;
  label: string;
  bucket: string | null;
}

interface ScoreResponse {
  text: string;
  context: Record<string, number>;
  thresholds: Record<string, number>;
  entities: Entity[];
  level: number;
  level_name: string;
  fired: string[];
  message: string;
  ms: number;
  offline?: boolean;
  hint?: string;
}

interface MetaResponse {
  encoder?: string;
  params?: string;
  checkpoint?: string;
  labels?: string[];
  offline?: boolean;
  hint?: string;
}

const LEVEL_NAME = ['None', 'Low', 'Medium', 'High'];

const SEEDS = [
  'im home alone at 412 delmar rd until 9, come over',
  'The restaurant is at 24 Oak Street.',
  'got the house to myself tonight',
  'Just leave me alone.',
  'my addy is 24 oak st',
  'meet me behind Lincoln Middle School at 5',
];

export function ModelScene({ pack }: { pack: RulePack }) {
  const [text, setText] = useState(SEEDS[0]);
  const [model, setModel] = useState<ScoreResponse | null>(null);
  const [meta, setMeta] = useState<MetaResponse | null>(null);
  const [busy, setBusy] = useState(false);
  const seq = useRef(0);

  const rules = scan(pack, text);

  const score = useCallback(async (value: string) => {
    const mine = ++seq.current;
    setBusy(true);
    try {
      const response = await fetch('/preview/api/score', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text: value, flat: false }),
      });
      const data = (await response.json()) as ScoreResponse;
      // Out-of-order responses: a slow request for an earlier keystroke must not overwrite a
      // fast one for a later keystroke.
      if (mine === seq.current) setModel(data);
    } catch {
      if (mine === seq.current) setModel({ offline: true } as ScoreResponse);
    } finally {
      if (mine === seq.current) setBusy(false);
    }
  }, []);

  useEffect(() => {
    fetch('/preview/api/score')
      .then((r) => r.json())
      .then(setMeta)
      .catch(() => setMeta({ offline: true }));
  }, []);

  // 200 ms, the debounce §32 specifies for the runtime. Not needed for cost here — it is so
  // that typing into this box feels like typing on the phone will.
  useEffect(() => {
    const timer = setTimeout(() => score(text), 200);
    return () => clearTimeout(timer);
  }, [text, score]);

  const offline = model?.offline || meta?.offline;
  const modelLevel = offline ? null : (model?.level ?? 0);
  const ruleLevel = rules.maxSeverity;

  let verdict: { tone: string; label: string } | null = null;
  if (!offline && modelLevel !== null && text.trim()) {
    if (modelLevel === ruleLevel) verdict = { tone: 'agree', label: 'both layers agree' };
    else if (modelLevel > ruleLevel)
      verdict = { tone: 'model', label: `model is higher — L${modelLevel} vs rules L${ruleLevel}` };
    else verdict = { tone: 'rules', label: `rules are higher — L${ruleLevel} vs model L${modelLevel}` };
  }

  return (
    <div className="kg-scene kg-scene--model">
      <div className="kg-scene-bar">
        <span>Model probe</span>
        <span className="kg-model-conn" data-off={!!offline}>
          {offline ? 'probe offline' : busy ? 'scoring…' : `${model?.ms ?? 0} ms`}
        </span>
      </div>

      <div className="kg-model-body">
        <textarea
          className="kg-model-input"
          value={text}
          onChange={(event) => setText(event.target.value)}
          rows={3}
          placeholder="Type a message the way a child would…"
        />

        <div className="kg-model-seeds">
          {SEEDS.map((seed) => (
            <button key={seed} type="button" onClick={() => setText(seed)}>
              {seed.length > 26 ? `${seed.slice(0, 26)}…` : seed}
            </button>
          ))}
        </div>

        {offline ? (
          <div className="kg-model-offline">
            <strong>The model probe is not running.</strong>
            <p>The rule layer below still works — it runs in the browser.</p>
            <code>cd training &amp;&amp; python -m scripts.serve_model --port 8731</code>
          </div>
        ) : null}

        <div className="kg-model-grid">
          <Layer
            name="Rule engine"
            sub="ships today · runs offline"
            level={ruleLevel}
            message={rules.top ? rules.top.message : null}
            detail={
              rules.findings.length
                ? rules.findings
                    .map((f) => CATEGORY_LABEL[f.category] ?? f.category)
                    .filter((v, i, a) => a.indexOf(v) === i)
                    .join(', ')
                : null
            }
          />
          <Layer
            name="Model + risk engine"
            sub={offline ? 'unavailable' : 'the new layer'}
            level={modelLevel}
            message={model?.message || null}
            detail={model?.fired?.length ? model.fired.join(' · ') : null}
          />
        </div>

        {verdict ? (
          <div className="kg-model-verdict" data-tone={verdict.tone}>
            {verdict.label}
          </div>
        ) : null}

        {!offline && model?.entities?.length ? (
          <div className="kg-model-section">
            <h4>Entities</h4>
            <div className="kg-model-ents">
              {model.entities.map((entity, index) => (
                <span key={index} title={entity.bucket ?? 'unmapped'}>
                  {text.slice(entity.start, entity.end)}
                  <em>{entity.label}</em>
                </span>
              ))}
            </div>
          </div>
        ) : null}

        {!offline && model?.context ? (
          <div className="kg-model-section">
            <h4>Safety signals</h4>
            {Object.entries(model.context).map(([label, probability]) => {
              const threshold = model.thresholds?.[label] ?? 0.5;
              const on = probability >= threshold;
              return (
                <div className="kg-model-sig" key={label} data-on={on}>
                  <span className="kg-model-sig-name">{label}</span>
                  <span className="kg-model-sig-bar">
                    <i style={{ width: `${probability * 100}%` }} />
                    <b style={{ left: `${threshold * 100}%` }} />
                  </span>
                  <span className="kg-model-sig-num">{probability.toFixed(2)}</span>
                </div>
              );
            })}
          </div>
        ) : null}

        <p className="kg-model-foot">
          {offline
            ? 'Rule layer only.'
            : `${meta?.encoder ?? ''} ${meta?.params ?? ''} · ${meta?.checkpoint ?? ''}`}
        </p>
      </div>
    </div>
  );
}

function Layer({
  name,
  sub,
  level,
  message,
  detail,
}: {
  name: string;
  sub: string;
  level: number | null;
  message: string | null;
  detail: string | null;
}) {
  return (
    <div className="kg-model-layer" data-level={level ?? 'na'}>
      <div className="kg-model-layer-head">
        <strong>{name}</strong>
        <span>{sub}</span>
      </div>
      <div className="kg-model-level">
        {level === null ? '—' : `L${level} · ${LEVEL_NAME[level]}`}
      </div>
      {message ? <p className="kg-model-msg">{message}</p> : null}
      {detail ? <p className="kg-model-detail">{detail}</p> : null}
    </div>
  );
}
