// Slow-Fi 95: finestre trascinabili, ridimensionabili, riducibili a icona; menu Start; orologio.
(() => {
  "use strict";
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => [...r.querySelectorAll(s)];
  const desk = $("#desk");
  const tasks = $("#tasks");
  const startBtn = $("#start");
  const startMenu = $("#startmenu");
  const phone = () => matchMedia("(max-width:700px)").matches;

  // Le pagine con un indirizzo proprio (servono agli store e ai link): aprono la loro finestra.
  const ROUTES = { "/rullino": "rullino", "/privacy": "privacy", "/elimina-account": "account", "/contatti": "contatti" };
  const PATH_OF = Object.fromEntries(Object.entries(ROUTES).map(([p, id]) => [id, p]));

  let z = 20;
  let cascade = 0;
  const state = {}; // id -> { el, task }

  function icon(id) {
    const s = $(`#tpl-${id}`);
    return s ? s.dataset.icon : "doc";
  }
  function svg(name) {
    const t = $(`#ico-${name}`);
    return t ? t.innerHTML : "";
  }

  function focus(id) {
    const w = state[id];
    if (!w) return;
    $$(".win.active").forEach(e => e.classList.remove("active"));
    $$("#tasks .btn.on").forEach(e => e.classList.remove("on"));
    w.el.hidden = false;
    w.el.classList.add("active");
    w.el.style.zIndex = ++z;
    w.task.classList.add("on");
    const p = PATH_OF[id] || "/";
    if (location.pathname !== p) history.replaceState(null, "", p);
    document.title = w.el.dataset.title + " — Slow-Fi";
  }

  function topVisible() {
    return Object.keys(state).filter(k => !state[k].el.hidden)
      .sort((a, b) => (+state[b].el.style.zIndex || 0) - (+state[a].el.style.zIndex || 0))[0];
  }
  function afterHide() {
    const next = topVisible();
    if (next) focus(next);
    else {
      $$("#tasks .btn.on").forEach(e => e.classList.remove("on"));
      if (location.pathname !== "/") history.replaceState(null, "", "/");
      document.title = "Slow-Fi";
    }
  }

  function open(id) {
    closeStart();
    if (state[id]) { focus(id); return; }
    const tpl = $(`#tpl-${id}`);
    if (!tpl) return;
    const el = document.createElement("section");
    el.className = "win raised" + (tpl.dataset.dialog ? " dialog" : "");
    el.dataset.title = tpl.dataset.title;
    el.setAttribute("role", "dialog");
    el.setAttribute("aria-label", tpl.dataset.title);
    const w = Math.min(+tpl.dataset.w || 560, innerWidth - 40);
    const h = Math.min(+tpl.dataset.h || 420, innerHeight - 80);
    el.style.width = w + "px";
    el.style.height = h + "px";
    const left = Math.max(10, Math.min(innerWidth - w - 10, 120 + cascade * 26));
    const top = Math.max(6, Math.min(innerHeight - h - 40, 30 + cascade * 24));
    cascade = (cascade + 1) % 8;
    el.style.left = left + "px";
    el.style.top = top + "px";
    el.innerHTML = `
      <div class="bar">${svg(icon(id))}<span class="t"></span>
        ${tpl.dataset.dialog ? "" : `<button class="btn ctl mn" aria-label="Riduci a icona">_</button><button class="btn ctl mx" aria-label="Ingrandisci">□</button>`}
        <button class="btn ctl x" aria-label="Chiudi">✕</button>
      </div>
      ${tpl.dataset.dialog ? "" : `<div class="menu"><span>File</span><span>Modifica</span><span>Visualizza</span><span>?</span></div>`}
      <div class="body sunken ${tpl.dataset.body || ""}"></div>
      ${tpl.dataset.status ? `<div class="status"><span></span><span></span></div>` : ""}
      ${tpl.dataset.dialog ? "" : `<div class="grip" aria-hidden="true"></div>`}`;
    $(".t", el).textContent = tpl.dataset.title;
    $(".body", el).append(...tpl.cloneNode(true).childNodes);
    if (tpl.dataset.status) {
      const [a, b] = tpl.dataset.status.split("|");
      const s = $$(".status span", el);
      s[0].textContent = a || "";
      s[1].textContent = b || "";
    }
    desk.append(el);

    const task = document.createElement("button");
    task.className = "btn";
    task.innerHTML = svg(icon(id)) + "<span></span>";
    task.lastChild.textContent = tpl.dataset.title;
    task.addEventListener("click", () => {
      if (el.classList.contains("active") && !el.hidden) { el.hidden = true; el.classList.remove("active"); task.classList.remove("on"); afterHide(); }
      else focus(id);
    });
    tasks.append(task);
    state[id] = { el, task };

    el.addEventListener("pointerdown", () => focus(id));
    $(".x", el).addEventListener("click", e => { e.stopPropagation(); close(id); });
    const mn = $(".mn", el);
    if (mn) mn.addEventListener("click", e => { e.stopPropagation(); el.hidden = true; el.classList.remove("active"); task.classList.remove("on"); afterHide(); });
    const mx = $(".mx", el);
    if (mx) {
      const toggle = () => el.classList.toggle("max");
      mx.addEventListener("click", e => { e.stopPropagation(); toggle(); });
      $(".bar", el).addEventListener("dblclick", e => { if (!e.target.closest(".ctl")) toggle(); });
    }
    $$("[data-open]", el).forEach(b => b.addEventListener("click", e => { e.preventDefault(); open(b.dataset.open); }));
    $$("[data-close]", el).forEach(b => b.addEventListener("click", () => close(id)));
    drag(el, $(".bar", el));
    const grip = $(".grip", el);
    if (grip) resize(el, grip);
    focus(id);
  }

  function close(id) {
    const w = state[id];
    if (!w) return;
    w.el.remove();
    w.task.remove();
    delete state[id];
    afterHide();
  }

  // Trascina dalla barra del titolo.
  function drag(el, handle) {
    handle.addEventListener("pointerdown", e => {
      if (e.target.closest(".ctl") || el.classList.contains("max") || phone()) return;
      const sx = e.clientX, sy = e.clientY, ox = el.offsetLeft, oy = el.offsetTop;
      handle.setPointerCapture(e.pointerId);
      const move = ev => {
        el.style.left = Math.min(innerWidth - 60, Math.max(-el.offsetWidth + 80, ox + ev.clientX - sx)) + "px";
        el.style.top = Math.min(innerHeight - 60, Math.max(0, oy + ev.clientY - sy)) + "px";
      };
      const up = () => { handle.removeEventListener("pointermove", move); handle.removeEventListener("pointerup", up); };
      handle.addEventListener("pointermove", move);
      handle.addEventListener("pointerup", up);
    });
  }

  // Ridimensiona dall'angolo in basso a destra.
  function resize(el, grip) {
    grip.addEventListener("pointerdown", e => {
      e.stopPropagation();
      const sx = e.clientX, sy = e.clientY, ow = el.offsetWidth, oh = el.offsetHeight;
      grip.setPointerCapture(e.pointerId);
      const move = ev => {
        el.style.width = Math.max(240, ow + ev.clientX - sx) + "px";
        el.style.height = Math.max(140, oh + ev.clientY - sy) + "px";
      };
      const up = () => { grip.removeEventListener("pointermove", move); grip.removeEventListener("pointerup", up); };
      grip.addEventListener("pointermove", move);
      grip.addEventListener("pointerup", up);
    });
  }

  // Icone: un clic seleziona, doppio clic apre (un tocco solo sul telefono).
  $$(".icon").forEach(b => {
    b.addEventListener("click", () => {
      $$(".icon.sel").forEach(i => i.classList.remove("sel"));
      b.classList.add("sel");
      if (phone() || matchMedia("(pointer:coarse)").matches) open(b.dataset.open);
    });
    b.addEventListener("dblclick", () => open(b.dataset.open));
    b.addEventListener("keydown", e => { if (e.key === "Enter") open(b.dataset.open); });
  });
  desk.addEventListener("pointerdown", e => {
    if (e.target === desk) $$(".icon.sel").forEach(i => i.classList.remove("sel"));
  });

  // Menu Start.
  function closeStart() { startMenu.hidden = true; startBtn.classList.remove("on"); }
  startBtn.addEventListener("click", e => {
    e.stopPropagation();
    startMenu.hidden = !startMenu.hidden;
    startBtn.classList.toggle("on", !startMenu.hidden);
  });
  $$("#startmenu [data-open]").forEach(b => b.addEventListener("click", () => open(b.dataset.open)));
  document.addEventListener("pointerdown", e => { if (!e.target.closest("#startmenu,#start")) closeStart(); });
  document.addEventListener("keydown", e => { if (e.key === "Escape") closeStart(); });

  // Orologio nel vassoio.
  const clock = $("#clock");
  const tick = () => { clock.textContent = new Date().toLocaleTimeString("it-IT", { hour: "2-digit", minute: "2-digit" }); };
  tick();
  setInterval(tick, 15000);

  // Avvio: una volta per sessione, saltabile con un clic.
  const boot = $("#boot");
  let seen = false;
  try { seen = sessionStorage.getItem("sf95") === "1"; sessionStorage.setItem("sf95", "1"); } catch (_) { seen = true; }
  const route = ROUTES[location.pathname.replace(/\/$/, "")];
  if (seen || route) boot.hidden = true;
  else {
    const done = () => { boot.hidden = true; };
    setTimeout(done, 1500);
    boot.addEventListener("click", done);
  }

  // Finestra di partenza: quella dell'indirizzo, altrimenti il benvenuto.
  open(route || "benvenuto");
})();
