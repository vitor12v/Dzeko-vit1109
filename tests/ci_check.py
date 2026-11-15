"""Checks, on a GitHub runner with real internet, the parts that depend on outside services:
the own (Piper) voice, the web search and the page reader."""
import json, threading, http.server, socketserver, functools, os, time, sys
from playwright.sync_api import sync_playwright
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
H = functools.partial(http.server.SimpleHTTPRequestHandler, directory=ROOT)
class Q(socketserver.TCPServer): allow_reuse_address = True
srv = Q(("127.0.0.1", 8780), H); threading.Thread(target=srv.serve_forever, daemon=True).start()
def note(k, v): print(f"::notice title={k}::{str(v)[:900]}"); sys.stdout.flush()
with sync_playwright() as p:
    b = p.chromium.launch(executable_path="/usr/bin/google-chrome", args=["--autoplay-policy=no-user-gesture-required"])
    ctx = b.new_context()
    ctx.add_init_script("localStorage.setItem('dzk.settings', JSON.stringify({boot:false, engine:'own', geo:false}));")
    page = ctx.new_page(); logs = []
    page.on("console", lambda m: logs.append(m.text) if m.type in ("error", "warning") else None)
    page.on("pageerror", lambda e: logs.append("PAGEERROR " + str(e)))
    page.goto("http://127.0.0.1:8780/index.html"); page.wait_for_timeout(1500)
    for label, js in [
        ("jina_wiki", "(async () => { const r = await fetch('https://r.jina.ai/https://pt.wikipedia.org/wiki/Homem_de_Ferro'); const t = await r.text(); return r.status + ' ' + t.length + ' | ' + t.slice(0, 500); })()"),
        ("jina_ddg", "(async () => { const r = await fetch('https://r.jina.ai/https://html.duckduckgo.com/html/?q=' + encodeURIComponent('preço iPhone 17 Brasil')); const t = await r.text(); return r.status + ' ' + t.length + ' | ' + t.slice(0, 700); })()"),
        ("jina_bing", "(async () => { const r = await fetch('https://r.jina.ai/https://www.bing.com/search?q=' + encodeURIComponent('preço iPhone 17 Brasil') + '&setlang=pt-BR&cc=BR'); const t = await r.text(); return r.status + ' ' + t.length + ' | ' + t.slice(0, 700); })()"),
        ("ddg_lite_json", "(async () => { try { const r = await fetch('https://api.duckduckgo.com/?format=json&no_html=1&q=' + encodeURIComponent('Homem de Ferro')); return r.status + ' ' + (await r.text()).slice(0, 300); } catch (e) { return 'ERRO ' + e; } })()"),
        ("searx", "(async () => { try { const r = await fetch('https://search.inetol.net/search?format=json&q=' + encodeURIComponent('preço iPhone 17')); return r.status + ' ' + (await r.text()).slice(0, 300); } catch (e) { return 'ERRO ' + e; } })()"),
        ("wiki_search", "(async () => { const r = await fetch('https://pt.wikipedia.org/w/api.php?origin=*&action=query&list=search&format=json&srlimit=3&srsearch=' + encodeURIComponent('preço iPhone 17')); return (await r.text()).slice(0, 300); })()"),
    ]:
        try: note(label, page.evaluate(js))
        except Exception as e: note(label, "EXC " + str(e))
    note("console", " || ".join(logs[-8:]))
    b.close()
srv.shutdown()
