"""A local web UI for poking at a trained checkpoint.

    python -m scripts.serve_model --checkpoint models/phase_c/best.pt
    → http://127.0.0.1:8000

Deliberately dependency-free: `http.server` from the stdlib rather than Flask or
FastAPI, because this is a development tool and adding a web framework to
`requirements.txt` means every cloud GPU box installs it to run a page nobody opens
there.

The page debounces at 200 ms and only scores when the text changes, which is the same
strategy §32 specifies for the phone — not to save cycles here, but so that what you feel
while typing into this box is roughly what the runtime will feel like.

Binds to 127.0.0.1 and nothing else. It runs an unauthenticated endpoint that echoes back
whatever you type, and the whole point of the product is that typed text does not leave
the device.
"""

from __future__ import annotations

import argparse
import json
import threading
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from keyguard_ml.inference import Predictor
from keyguard_ml.labels import CONTEXT_LABELS, CONTEXT_LABEL_DEFINITIONS, ENTITY_TO_SAFETY_ENTITY
from keyguard_ml.risk_engine import RiskEngine

PRESETS = [
    ("Address — child's", "I live at 24 Oak Street."),
    ("Address — a business", "The restaurant is at 24 Oak Street."),
    ("Phone — child's", "My number is 514-555-1234."),
    ("Phone — a business", "Call the restaurant at 514-555-1234."),
    ("Alone — real", "I'm home alone tonight."),
    ("Alone — the film", "I watched Home Alone tonight."),
    ("Alone — idiom", "Just leave me alone."),
    ("Routine — child's", "Practice ends at 5 every Tuesday."),
    ("Routine — a shop's", "The store closes at 5 every Tuesday."),
    ("Slang address", "my addy is 24 oak st"),
    ("Indirect guardian", "got the house to myself tonight"),
    ("Relative time", "parents back in 2 hrs"),
    ("Full critical", "im home alone at 412 delmar rd until 9, come over"),
    ("Meetup at school", "meet me behind Lincoln Middle School at 5"),
]

PAGE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Keyguard model probe</title>
<style>
:root{
  --bg:#f6f7f9; --panel:#fff; --ink:#14161a; --muted:#6b7280; --line:#e3e6ea;
  --l0:#16a34a; --l1:#ca8a04; --l2:#c026d3; --l3:#dc2626; --accent:#2563eb;
}
@media (prefers-color-scheme:dark){:root{
  --bg:#0f1115; --panel:#171a20; --ink:#e8eaed; --muted:#9aa1ab; --line:#272c34;
}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--ink);
  font:15px/1.5 ui-sans-serif,system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
.wrap{max-width:980px;margin:0 auto;padding:28px 20px 64px}
h1{font-size:19px;margin:0 0 2px;letter-spacing:-.01em}
.sub{color:var(--muted);font-size:13px;margin:0 0 20px}
.card{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:16px;margin-bottom:14px}
textarea{width:100%;min-height:82px;resize:vertical;border:1px solid var(--line);border-radius:9px;
  padding:12px 13px;font:inherit;background:transparent;color:var(--ink);outline:none}
textarea:focus{border-color:var(--accent)}
.row{display:flex;gap:14px;align-items:center;flex-wrap:wrap;margin-top:10px}
.row label{font-size:13px;color:var(--muted);display:flex;gap:6px;align-items:center;cursor:pointer}
.presets{display:flex;flex-wrap:wrap;gap:6px;margin-top:12px}
.presets button{font:inherit;font-size:12px;padding:5px 10px;border-radius:999px;cursor:pointer;
  border:1px solid var(--line);background:transparent;color:var(--muted)}
.presets button:hover{border-color:var(--accent);color:var(--accent)}
.banner{display:flex;gap:12px;align-items:baseline;padding:13px 15px;border-radius:10px;
  color:#fff;font-weight:600;transition:background .15s}
.banner .lvl{font-size:13px;letter-spacing:.08em;text-transform:uppercase;opacity:.9}
.banner .msg{font-weight:500;font-size:14px}
.l0{background:var(--l0)}.l1{background:var(--l1)}.l2{background:var(--l2)}.l3{background:var(--l3)}
.hl{font-size:16px;line-height:2;word-wrap:break-word;min-height:32px}
mark{padding:2px 3px;border-radius:4px;background:rgba(37,99,235,.16);
  box-shadow:inset 0 -2px 0 var(--accent);color:inherit}
mark small{font-size:10px;letter-spacing:.05em;color:var(--accent);
  text-transform:uppercase;vertical-align:super;margin-left:3px;font-weight:700}
table{width:100%;border-collapse:collapse;font-size:13px}
td{padding:5px 0;vertical-align:middle}
td.name{width:150px;color:var(--muted)}
td.name.on{color:var(--ink);font-weight:600}
.bar{position:relative;height:9px;border-radius:5px;background:var(--line);overflow:visible}
.fill{height:100%;border-radius:5px;background:var(--muted);transition:width .12s}
.fill.on{background:var(--accent)}
.tick{position:absolute;top:-3px;width:2px;height:15px;background:var(--ink);opacity:.45;border-radius:1px}
td.num{width:52px;text-align:right;font-variant-numeric:tabular-nums;color:var(--muted);font-size:12px}
.rules{margin-top:10px;font-size:12.5px;color:var(--muted)}
.rules code{background:var(--line);padding:1px 6px;border-radius:5px;font-size:11.5px}
.meta{font-size:11.5px;color:var(--muted);margin-top:18px;line-height:1.7}
h2{font-size:12px;text-transform:uppercase;letter-spacing:.07em;color:var(--muted);margin:0 0 11px}
.empty{color:var(--muted);font-style:italic}
</style></head><body><div class="wrap">
<h1>Keyguard model probe</h1>
<p class="sub" id="modelinfo">loading…</p>

<div class="card">
  <textarea id="msg" placeholder="Type a message the way a child would…" autofocus></textarea>
  <div class="row">
    <label><input type="checkbox" id="flat"> ignore tuned thresholds (use a flat 0.5)</label>
    <span class="sub" style="margin:0" id="timing"></span>
  </div>
  <div class="presets" id="presets"></div>
</div>

<div class="card">
  <div class="banner l0" id="banner">
    <span class="lvl" id="lvl">Level 0 · none</span>
    <span class="msg" id="bmsg">Nothing to warn about.</span>
  </div>
  <div class="rules" id="rules"></div>
</div>

<div class="card">
  <h2>Detected entities <span style="text-transform:none;letter-spacing:0">— what the token head sees</span></h2>
  <div class="hl" id="hl"><span class="empty">nothing yet</span></div>
</div>

<div class="card">
  <h2>Safety signals <span style="text-transform:none;letter-spacing:0">— what the context head sees</span></h2>
  <table id="sig"></table>
</div>

<p class="meta" id="foot"></p>
</div>
<script>
const $=id=>document.getElementById(id);
let labels=[],defs={},thresholds={},timer=null,last=null,seq=0;

fetch('/api/meta').then(r=>r.json()).then(m=>{
  labels=m.labels; defs=m.definitions; thresholds=m.thresholds;
  $('modelinfo').textContent=`${m.encoder} · ${m.params} · ${m.checkpoint}`;
  $('foot').innerHTML=`Thresholds were selected on the validation split by maximising recall subject to `
    +`precision ≥ 0.90. A tick on each bar marks that label's threshold. `
    +`Risk levels come from ${m.rules} deterministic rules in <code>risk_rules.json</code>, `
    +`the same table the Kotlin runtime is meant to read — the model never picks a level itself.`;
  m.presets.forEach(([name,text])=>{
    const b=document.createElement('button'); b.textContent=name;
    b.onclick=()=>{$('msg').value=text; score(true);};
    $('presets').appendChild(b);
  });
  buildTable(); score(true);
});

function buildTable(){
  $('sig').innerHTML=labels.map(l=>`<tr>
    <td class="name" id="n-${l}" title="${(defs[l]||'').replace(/"/g,'&quot;')}">${l}</td>
    <td><div class="bar"><div class="fill" id="f-${l}"></div><div class="tick" id="t-${l}"></div></div></td>
    <td class="num" id="p-${l}">–</td></tr>`).join('');
}

function esc(s){return s.replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]));}

function render(d){
  const b=$('banner'); b.className='banner l'+d.level;
  $('lvl').textContent=`Level ${d.level} · ${d.level_name.toLowerCase()}`;
  $('bmsg').textContent=d.message||(d.level===0?'Nothing to warn about.':'');
  $('rules').innerHTML=d.fired.length
    ? 'fired: '+d.fired.map(r=>`<code>${r}</code>`).join(' ')
    : (d.text.trim()? 'no rule matched' : '');

  if(!d.text.trim()){ $('hl').innerHTML='<span class="empty">nothing yet</span>'; }
  else if(!d.entities.length){ $('hl').innerHTML=esc(d.text)+' <span class="empty">— no entities</span>'; }
  else{
    let out='',cur=0;
    for(const e of d.entities){
      out+=esc(d.text.slice(cur,e.start));
      out+=`<mark title="${e.bucket||'unmapped'}">${esc(d.text.slice(e.start,e.end))}<small>${e.label}</small></mark>`;
      cur=e.end;
    }
    $('hl').innerHTML=out+esc(d.text.slice(cur));
  }

  for(const l of labels){
    const p=d.context[l], thr=d.thresholds[l], on=p>=thr;
    const f=$('f-'+l); f.style.width=(p*100).toFixed(1)+'%'; f.className='fill'+(on?' on':'');
    $('t-'+l).style.left=`calc(${(thr*100).toFixed(1)}% - 1px)`;
    $('p-'+l).textContent=p.toFixed(3);
    $('n-'+l).className='name'+(on?' on':'');
  }
  $('timing').textContent=`scored in ${d.ms} ms`;
}

function score(now){
  const text=$('msg').value, flat=$('flat').checked, key=text+'|'+flat;
  if(key===last&&!now) return;
  last=key;
  const mine=++seq;
  fetch('/api/score',{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({text,flat})})
    .then(r=>r.json()).then(d=>{ if(mine===seq) render(d); });
}

// 200 ms debounce — the same shape as the runtime strategy in §32.
$('msg').addEventListener('input',()=>{clearTimeout(timer);timer=setTimeout(score,200);});
$('flat').addEventListener('change',()=>score(true));
</script></body></html>
"""


class Handler(BaseHTTPRequestHandler):
    predictor: Predictor
    engine: RiskEngine
    checkpoint: str

    def log_message(self, *args):  # noqa: D102 - silence per-request logging
        pass

    def _send(self, code: int, body: bytes, content_type: str) -> None:
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # noqa: N802
        if self.path in ("/", "/index.html"):
            self._send(200, PAGE.encode("utf-8"), "text/html; charset=utf-8")
        elif self.path == "/api/meta":
            self._send(200, json.dumps(self.meta()).encode("utf-8"), "application/json")
        else:
            self._send(404, b"not found", "text/plain")

    def do_POST(self) -> None:  # noqa: N802
        if self.path != "/api/score":
            self._send(404, b"not found", "text/plain")
            return
        length = int(self.headers.get("Content-Length") or 0)
        payload = json.loads(self.rfile.read(length) or b"{}")
        result = self.score(payload.get("text", ""), bool(payload.get("flat")))
        self._send(200, json.dumps(result).encode("utf-8"), "application/json")

    # -- work -------------------------------------------------------------------
    def meta(self) -> dict:
        model = self.predictor.model
        return {
            "labels": list(CONTEXT_LABELS),
            "definitions": CONTEXT_LABEL_DEFINITIONS,
            "thresholds": self.predictor.thresholds,
            "encoder": self.predictor.config.encoder_name,
            "params": f"{model.num_parameters()/1e6:.1f}M params",
            "checkpoint": self.checkpoint,
            "rules": len(self.engine.rules),
            "presets": PRESETS,
        }

    def score(self, text: str, flat: bool) -> dict:
        import time

        thresholds = (
            {label: 0.5 for label in CONTEXT_LABELS} if flat else self.predictor.thresholds
        )
        if not text.strip():
            return {
                "text": text,
                "context": {label: 0.0 for label in CONTEXT_LABELS},
                "thresholds": thresholds,
                "entities": [],
                "level": 0,
                "level_name": "NONE",
                "fired": [],
                "message": "",
                "ms": 0,
            }

        began = time.perf_counter()
        prediction = self.predictor.predict_texts([text])[0]
        entities = prediction.entities()
        buckets = {
            bucket
            for _, _, entity in entities
            if (bucket := ENTITY_TO_SAFETY_ENTITY.get(entity))
        }
        result = self.engine.evaluate(prediction.context, buckets, thresholds)
        elapsed = (time.perf_counter() - began) * 1000.0

        return {
            "text": text,
            "context": prediction.context,
            "thresholds": thresholds,
            "entities": [
                {
                    "start": start,
                    "end": end,
                    "label": label,
                    "bucket": ENTITY_TO_SAFETY_ENTITY.get(label),
                }
                for start, end, label in entities
            ],
            "level": result.level,
            "level_name": result.level_name,
            "fired": result.fired,
            "message": result.message,
            "ms": round(elapsed, 1),
        }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", default="models/_cpu_signal/best.pt")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--no-open", action="store_true")
    args = parser.parse_args()

    path = Path(args.checkpoint)
    if not path.exists():
        raise SystemExit(f"no checkpoint at {path}. Train one, or pass --checkpoint.")

    print(f"loading {path} …")
    Handler.predictor = Predictor(path, device="cpu")
    Handler.engine = RiskEngine()
    Handler.checkpoint = str(path)

    url = f"http://127.0.0.1:{args.port}"
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"serving {url}  (Ctrl-C to stop)")
    if not args.no_open:
        threading.Timer(0.6, lambda: webbrowser.open(url)).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nstopped")
        server.shutdown()


if __name__ == "__main__":
    main()
