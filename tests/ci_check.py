"""Checks, on a GitHub runner with real internet, the parts that depend on outside services:
the own (Piper) voice, the web search and the page reader."""
import json, threading, http.server, socketserver, functools, os, time, sys
from playwright.sync_api import sync_playwright
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
H = functools.partial(http.server.SimpleHTTPRequestHandler, directory=ROOT)
class Q(socketserver.TCPServer): allow_reuse_address = True
srv = Q(("127.0.0.1", 8780), H); threading.Thread(target=srv.serve_forever, daemon=True).start()
def note(k, v): print(f"::notice title={k}::" + str(v)[:1800].replace("%", "%25").replace("\r", "").replace("\n", "%0A")); sys.stdout.flush()
with sync_playwright() as p:
    b = p.chromium.launch(executable_path="/usr/bin/google-chrome", args=["--autoplay-policy=no-user-gesture-required"])
    ctx = b.new_context()
    ctx.add_init_script("localStorage.setItem('dzk.settings', JSON.stringify({boot:false, engine:'own', geo:false}));")
    page = ctx.new_page(); logs = []
    page.on("console", lambda m: logs.append(m.text) if m.type in ("error", "warning") else None)
    page.on("pageerror", lambda e: logs.append("PAGEERROR " + str(e)))
    page.goto("http://127.0.0.1:8780/index.html"); page.wait_for_timeout(1500)
    for label, js in [
        ("web", "(async () => { const r = await __dzkDbg.webSearch('preço iPhone 17 Brasil'); return r.length + ' | ' + r.slice(0,4).map(x => x.src + ': ' + x.title + ' — ' + x.desc.slice(0,120)).join(' ;; '); })()"),
        ("deep", "(async () => { const r = await __dzkDbg.deepSearch('buscar', 'horário de funcionamento Smart Fit domingo'); return r.text.length + ' | fontes ' + JSON.stringify(r.sources) + ' | ' + r.text.slice(0, 1400); })()"),
        ("ler", "(async () => { const t = await __dzkDbg.readPage('https://pt.wikipedia.org/wiki/J.A.R.V.I.S.', 700); return t.length + ' | ' + t; })()"),
        ("own_tts", "(async () => { await __dzkDbg.ownWarm(); const t0 = performance.now(); try { const b = await __dzkDbg.ownTTS('Às suas ordens, senhor. Todos os sistemas estão operando normalmente.'); return 'ok dur ' + b.duration.toFixed(2) + 's em ' + Math.round(performance.now() - t0) + 'ms'; } catch (e) { return 'ERRO ' + (e && (e.stack || e.message) || e); } })()"),
    ]:
        try: note(label, page.evaluate(js))
        except Exception as e: note(label, "EXC " + str(e))
    note("console", " || ".join(logs[-8:]))
    b.close()
srv.shutdown()
