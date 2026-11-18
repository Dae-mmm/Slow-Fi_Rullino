#!/usr/bin/env python3
"""Genera site/index.html (il desktop Slow-Fi 95) da contenuti e icone qui sotto.
Uso: python3 tools/build_site.py   — poi si fa il commit di site/."""
import html, pathlib, re

ROOT = pathlib.Path(__file__).resolve().parent.parent / "site"

# ---------------- Icone (originali, a pixel, 32×32) ----------------
def ico(body):
    return f'<svg viewBox="0 0 32 32" shape-rendering="crispEdges" aria-hidden="true">{body}</svg>'

ICONS = {
    "film": ico(  # caricatore di pellicola
        '<rect x="6" y="3" width="6" height="4" fill="#3a3533"/><rect x="4" y="6" width="20" height="23" fill="#1b1918"/>'
        '<rect x="5" y="7" width="18" height="21" fill="#4a4542"/><rect x="7" y="10" width="14" height="14" fill="#ff6a3d"/>'
        '<rect x="7" y="13" width="14" height="2" fill="#1b0e09"/><rect x="9" y="17" width="10" height="2" fill="#fff3ea"/>'
        '<rect x="24" y="12" width="5" height="10" fill="#c9a27a"/><rect x="25" y="13" width="1" height="1" fill="#000"/>'
        '<rect x="25" y="16" width="1" height="1" fill="#000"/><rect x="25" y="19" width="1" height="1" fill="#000"/>'
        '<rect x="5" y="7" width="1" height="21" fill="#7a736e"/>'),
    "txt": ico(  # blocco note
        '<rect x="6" y="2" width="20" height="28" fill="#000"/><rect x="7" y="3" width="18" height="26" fill="#fff"/>'
        '<rect x="7" y="3" width="18" height="4" fill="#ffff80"/><rect x="9" y="1" width="2" height="4" fill="#808080"/>'
        '<rect x="15" y="1" width="2" height="4" fill="#808080"/><rect x="21" y="1" width="2" height="4" fill="#808080"/>'
        '<rect x="9" y="10" width="14" height="1" fill="#000080"/><rect x="9" y="14" width="12" height="1" fill="#000080"/>'
        '<rect x="9" y="18" width="14" height="1" fill="#000080"/><rect x="9" y="22" width="9" height="1" fill="#000080"/>'),
    "doc": ico(  # documento con lucchetto
        '<polygon points="6,2 20,2 26,8 26,30 6,30" fill="#000"/><polygon points="7,3 19,3 25,9 25,29 7,29" fill="#fff"/>'
        '<polygon points="19,3 19,9 25,9" fill="#c0c0c0"/><rect x="9" y="8" width="8" height="1" fill="#808080"/>'
        '<rect x="9" y="11" width="13" height="1" fill="#808080"/><rect x="9" y="14" width="13" height="1" fill="#808080"/>'
        '<rect x="15" y="19" width="10" height="9" fill="#e0a000"/><rect x="17" y="16" width="6" height="1" fill="#555"/>'
        '<rect x="16" y="17" width="1" height="3" fill="#555"/><rect x="23" y="17" width="1" height="3" fill="#555"/>'
        '<rect x="19" y="22" width="2" height="3" fill="#000"/>'),
    "mail": ico(  # busta
        '<rect x="2" y="7" width="28" height="19" fill="#000"/><rect x="3" y="8" width="26" height="17" fill="#fffbe6"/>'
        '<polygon points="3,8 16,18 29,8 29,10 16,20 3,10" fill="#808080"/><rect x="22" y="10" width="5" height="6" fill="#ff6a3d"/>'
        '<rect x="23" y="11" width="3" height="4" fill="#fff"/>'),
    "trash": ico(  # cestino
        '<rect x="7" y="6" width="18" height="3" fill="#808080"/><rect x="13" y="4" width="6" height="2" fill="#808080"/>'
        '<polygon points="8,9 24,9 22,30 10,30" fill="#000"/><polygon points="9,10 23,10 21,29 11,29" fill="#c0c0c0"/>'
        '<rect x="12" y="12" width="1" height="15" fill="#808080"/><rect x="16" y="12" width="1" height="15" fill="#808080"/>'
        '<rect x="20" y="12" width="1" height="15" fill="#808080"/><rect x="12" y="7" width="9" height="3" fill="#ff6a3d"/>'),
    "user": ico(  # account con divieto
        '<rect x="11" y="4" width="10" height="10" fill="#000"/><rect x="12" y="5" width="8" height="8" fill="#e8c39e"/>'
        '<rect x="6" y="15" width="20" height="13" fill="#000"/><rect x="7" y="16" width="18" height="12" fill="#3060c0"/>'
        '<circle cx="23" cy="23" r="7" fill="#c00000"/><circle cx="23" cy="23" r="5" fill="#fff"/>'
        '<polygon points="19.5,21 26.5,21 26.5,25 19.5,25" fill="#c00000"/>'),
    "info": ico(
        '<circle cx="16" cy="16" r="13" fill="#000"/><circle cx="16" cy="16" r="12" fill="#fff"/>'
        '<rect x="14" y="13" width="4" height="11" fill="#000080"/><rect x="14" y="7" width="4" height="4" fill="#000080"/>'),
    "power": ico(
        '<circle cx="16" cy="17" r="11" fill="none" stroke="#000" stroke-width="3"/><rect x="12" y="2" width="8" height="8" fill="#c0c0c0"/>'
        '<rect x="14" y="3" width="4" height="13" fill="#c00000"/>'),
    "photo": ico(
        '<rect x="3" y="6" width="26" height="20" fill="#000"/><rect x="4" y="7" width="24" height="18" fill="#7ec8f0"/>'
        '<polygon points="4,25 12,14 18,21 21,18 28,25" fill="#2e7d32"/><rect x="21" y="9" width="4" height="4" fill="#ffd700"/>'),
    "exe": ico(
        '<rect x="3" y="5" width="26" height="22" fill="#000"/><rect x="4" y="6" width="24" height="20" fill="#c0c0c0"/>'
        '<rect x="4" y="6" width="24" height="4" fill="#000080"/><rect x="7" y="13" width="18" height="2" fill="#808080"/>'
        '<rect x="7" y="17" width="12" height="2" fill="#808080"/><rect x="7" y="21" width="15" height="2" fill="#808080"/>'),
}

def esc(s): return html.escape(s, quote=True)

# ---------------- Contenuti delle finestre ----------------
privacy = (pathlib.Path(__file__).resolve().parent / "site_privacy.html").read_text().replace(' class="h-page"', "")
privacy = privacy.replace('href="/elimina-account"', 'href="/elimina-account" data-open="account"')

WINDOWS = [
    dict(id="benvenuto", title="Benvenuto in Slow-Fi 95", icon="info", w=500, h=330, status="Pronto|Slow-Fi 95", body="""
<h1>Meno scatti. Più attesa.</h1>
<p class="lead">Slow-Fi è una famiglia di app che rimettono nel digitale i limiti buoni dell'analogico: poche pose, niente anteprima, il tempo dello sviluppo.</p>
<p>Fai doppio clic sulle icone del desktop, oppure apri il menu <b>Start</b> in basso a sinistra.</p>
<div class="row">
  <button class="btn" data-open="rullino">Apri Rullino.exe</button>
  <button class="btn" data-open="leggimi">LEGGIMI.txt</button>
</div>"""),
    dict(id="leggimi", title="LEGGIMI.txt - Blocco note", icon="txt", w=520, h=380, body_class="notepad", body=esc("""SLOW-FI — LEGGIMI
=================

Slow-Fi fa app lente, apposta.

1. Il limite è il punto.
   Ventiquattro pose ti fanno scegliere.
   Senza anteprima guardi la scena, non lo schermo.

2. Le tue foto restano tue.
   Niente pubblicità, niente tracciamento.
   Foto e negativi stanno sul tuo telefono.

3. Pellicole da collezionare.
   Il catalogo delle emulsioni cresce nel tempo;
   alcune si vincono solo nei concorsi.

App n° 01: Rullino (doppio clic su Rullino.exe)
Altre app: in sviluppo. Abbi pazienza, è lo spirito.

slow-fi.app
""")),
    dict(id="rullino", title="Rullino.exe", icon="film", w=640, h=470, body_class="dark", status="App n° 01|Android · iPhone", body="""
<p class="kicker">Slow-Fi · app n° 01</p>
<h1>Rullino</h1>
<p class="lead">La fotocamera a pellicola nel telefono, con tutti i suoi difetti preferiti: la grana, l'esposizione da azzeccare, la doppia esposizione per sbaglio (o apposta).</p>
<div class="row"><span class="btn">Presto su Google Play</span><span class="btn">Presto su App Store</span></div>
<h2>Come funziona</h2>
<div class="grid">
  <div class="card"><p class="kicker">01 · Carica</p><h3>Scegli l'emulsione</h3><p>Ogni pellicola ha la sua sensibilità, i suoi colori e la sua grana. Rullini da 24 o 36 pose.</p></div>
  <div class="card"><p class="kicker">02 · Scatta</p><h3>Leva e otturatore</h3><p>Tira la leva, scegli il tempo sulla ghiera e scatta. Non vedi il risultato: lo scoprirai dopo.</p></div>
  <div class="card"><p class="kicker">03 · Riavvolgi</p><h3>Doppie esposizioni</h3><p>Premi R, gira la manovella di una posa e scatta di nuovo sopra la stessa foto.</p></div>
  <div class="card"><p class="kicker">04 · Sviluppa</p><h3>Il laboratorio</h3><p>Porta il rullino al negozio, chiedi push o pull e aspetta i negativi. Poi il tavolo luminoso e le stampe.</p></div>
</div>
<h2>Pellicola vera, nei limiti del digitale</h2>
<div class="grid">
  <div class="card"><h3>Esposimetro</h3><p>Una barretta come nei mirini reflex ti dice se sei sotto o sovra, e su che tempo girare la ghiera.</p></div>
  <div class="card"><h3>Emulsioni</h3><p>Il catalogo cresce nel tempo: nuove pellicole si scaricano dal negozio dell'app.</p></div>
  <div class="card"><h3>Rullini condivisi</h3><p>Passa un rullino a metà a qualcuno: lo finisce lui, anche sopra le tue pose.</p></div>
  <div class="card"><h3>Concorsi</h3><p>Temi, emulsioni imposte, a volte un rullino in regalo. Una sola foto a testa, una giuria che vota da 1 a 10.</p></div>
</div>"""),
    dict(id="privacy", title="Privacy.doc - Rullino", icon="doc", w=680, h=500, status="Informativa privacy|Bozza in revisione", body=privacy),
    dict(id="account", title="Eliminare l'account", icon="user", w=520, h=400, body="""
<p class="kicker">Rullino · account</p>
<h1>Eliminare l'account</h1>
<p>Puoi eliminare il tuo account Rullino quando vuoi, direttamente dall'app:</p>
<ol class="steps">
  <li>Apri Rullino e vai in <b>Opzioni</b>.</li>
  <li>Nella scheda <b>Account</b> tocca <b>Elimina l'account</b>.</li>
  <li>Conferma. L'account si cancella subito.</li>
</ol>
<h2>Cosa viene cancellato</h2>
<p>Dai nostri server: email, nome utente, nome visualizzato, ruolo, eventuale abbonamento, le partecipazioni ai concorsi con le foto e le liberatorie inviate, i voti dati se eri in giuria.</p>
<p>Foto, rullini, negativi e liberatorie che stanno solo sul telefono non passano dai nostri server: restano sul telefono finché non li cancelli o disinstalli l'app.</p>
<h2>Non hai più l'app?</h2>
<p>Scrivi a <a href="mailto:privacy@slow-fi.app?subject=Eliminare%20l%27account%20Rullino">privacy@slow-fi.app</a> dall'indirizzo email con cui ti sei registrato e chiedi la cancellazione. Rispondiamo entro 30 giorni, di solito molto prima.</p>"""),
    dict(id="contatti", title="Posta in uscita", icon="mail", w=460, h=320, body="""
<table>
  <tr><th>A</th><td><a href="mailto:ciao@slow-fi.app">ciao@slow-fi.app</a></td></tr>
  <tr><th>Per</th><td>Idee, problemi con l'app, collaborazioni</td></tr>
</table>
<table>
  <tr><th>A</th><td><a href="mailto:privacy@slow-fi.app">privacy@slow-fi.app</a></td></tr>
  <tr><th>Per</th><td>Domande sui dati, richieste di accesso o cancellazione</td></tr>
</table>
<p>Rispondiamo con calma, ma rispondiamo.</p>
<div class="row"><a class="btn" href="mailto:ciao@slow-fi.app">Scrivi un messaggio</a></div>"""),
    dict(id="cestino", title="Cestino", icon="trash", w=480, h=300, status="4 oggetti|Svuotato mai", body=f"""
<div class="files">
  <div>{ICONS['photo']}anteprima_istantanea.jpg</div>
  <div>{ICONS['photo']}scatto_0847_di_0847.jpg</div>
  <div>{ICONS['exe']}ansia_da_like.exe</div>
  <div>{ICONS['txt']}filtro_vintage_finto.preset</div>
</div>"""),
    dict(id="spegni", title="Chiudi Slow-Fi", icon="power", w=380, h=170, dialog=True, body=f"""
{ICONS['info']}<div><p><b>È ora di spegnere.</b></p><p>Prendi la macchina e vai fuori a scattare: la luce di adesso non torna.</p>
<div class="row"><button class="btn" data-close>OK</button></div></div>"""),
]

DESK_ICONS = [("rullino", "film", "Rullino.exe"), ("leggimi", "txt", "LEGGIMI.txt"), ("privacy", "doc", "Privacy.doc"),
              ("contatti", "mail", "Posta"), ("account", "user", "Elimina account"), ("cestino", "trash", "Cestino")]
START = [("rullino", "film", "Rullino"), ("leggimi", "txt", "Leggimi"), ("privacy", "doc", "Privacy"),
         ("account", "user", "Eliminare l'account"), ("contatti", "mail", "Contatti"), None, ("spegni", "power", "Chiudi Slow-Fi…")]

def window_src(w):
    attrs = {"id": f"tpl-{w['id']}", "class": "src", "data-title": w["title"], "data-icon": w["icon"],
             "data-w": str(w["w"]), "data-h": str(w["h"])}
    if w.get("body_class"): attrs["data-body"] = w["body_class"]
    if w.get("status"): attrs["data-status"] = w["status"]
    if w.get("dialog"): attrs["data-dialog"] = "1"
    a = " ".join(f'{k}="{esc(v)}"' for k, v in attrs.items())
    return f'<div {a} hidden>{w["body"]}</div>'

icons_tpl = "\n".join(f'<template id="ico-{k}">{v}</template>' for k, v in ICONS.items())
desk_icons = "\n".join(f'<button class="icon" data-open="{i}">{ICONS[ic]}<span>{esc(lbl)}</span></button>' for i, ic, lbl in DESK_ICONS)
start_items = "\n".join("<li><hr></li>" if s is None else f'<li><button data-open="{s[0]}">{ICONS[s[1]]}<span>{esc(s[2])}</span></button></li>' for s in START)
sources = "\n".join(window_src(w) for w in WINDOWS)

page = f"""<!doctype html>
<html lang="it">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Slow-Fi</title>
<meta name="description" content="Slow-Fi: app che rallentano il digitale. La prima è Rullino, una fotocamera a pellicola simulata.">
<meta name="theme-color" content="#007a78">
<link rel="icon" href="/favicon.svg" type="image/svg+xml">
<link rel="stylesheet" href="/desktop.css">
<noscript><link rel="stylesheet" href="/noscript.css"></noscript>
<script src="/desktop.js" defer></script>
</head>
<body>
<div id="boot" aria-hidden="true"><div class="logo">S—F<small>SLOW-FI 95</small></div><div class="bar"><i></i></div><div>Avvio in corso… con calma.</div></div>

<main id="desk">
<nav id="icons" aria-label="Desktop">
{desk_icons}
</nav>
</main>

<div id="startmenu" class="raised" hidden>
  <div class="side"><b>Slow</b>-Fi 95</div>
  <ul>
{start_items}
  </ul>
</div>

<footer id="taskbar">
  <button id="start" class="btn" aria-haspopup="menu"><span class="sf">S—F</span>Start</button>
  <div id="tasks"></div>
  <div id="tray">{ICONS['film']}<span id="clock"></span></div>
</footer>

<!-- Contenuti delle finestre (leggibili anche senza JavaScript) -->
{sources}

{icons_tpl}
</body>
</html>
"""
(ROOT / "index.html").write_text(page)
print("ok", len(page))
