// The Flashcards page: start page -> review -> summary -> saved.
//
// The server (flashcards/web/app.py) holds the session and does every rule
// (flashcards/service.py); this file only draws what it returns and sends
// what you click. Nothing is written to the workbook until you press Save on
// the summary.
//
// DOM is built with h() and textContent, never innerHTML with data, so a
// word like "a < b" shows as typed.
"use strict";

const TOKEN = document.querySelector('meta[name="fc-token"]').content;
// app.py: /api/ping keeps the idle watchdog (launcher.py, IDLE_EXIT_SECONDS
// = 600) from stopping the server while this tab is open. Must stay well
// under 600 s; browsers run background-tab timers about once a minute at most.
const HEARTBEAT_MS = 60_000;
// service.py MODES / ORDERS / DIRECTIONS.
const MODES = [
  ["due", "Due now", "What FSRS says is due, most forgotten first, then new words"],
  ["topic", "Topics", "Only the topics you pick"],
  ["mix", "All topics", "Every word in the workbook"],
  ["difficult", "Difficult", "Words at or below a confidence you choose"],
];
// session.EXERCISES
const EXERCISE_LABELS = {
  flashcard: "Flashcards",
  choice: "Multiple choice",
  blank: "Fill in the blank",
  recall: "Type the word",
  listen: "Listening",
  scramble: "Sentence scramble",
  article: "Article (der/die/das)",
  forms: "Forms (plural, past)",
  mixed: "Mixed",
};
const ORDER_LABELS = {
  weighted: "Weighted (weakest likeliest)",
  lowest_confidence_first: "Lowest confidence first",
  random: "Random",
  oldest_first: "Oldest first",
};

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------

function h(tag, attrs = {}, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === undefined || v === null || v === false) continue;
    if (k.startsWith("on")) el.addEventListener(k.slice(2), v);
    else if (k === "class") el.className = v;
    // setProperty, not Object.assign: only it can set custom properties (--gc on the grade buttons).
    else if (k === "style") for (const [prop, val] of Object.entries(v)) el.style.setProperty(prop.replace(/[A-Z]/g, (m) => `-${m.toLowerCase()}`), val);
    else if (k in el && typeof v !== "string") el[k] = v;
    else el.setAttribute(k, v === true ? "" : v);
  }
  for (const c of children.flat()) if (c !== null && c !== undefined && c !== false) el.append(c instanceof Node ? c : String(c));
  return el;
}

// Text in a language written right to left (Persian, Arabic, ...) needs
// dir="rtl", or its punctuation jumps to the wrong end (models.is_rtl).
function txt(tag, text, rtl, cls) {
  return h(tag, { class: cls, dir: rtl ? "rtl" : "auto" }, text);
}

class ServerStopped extends Error {}

async function api(method, path, body) {
  let resp;
  try {
    resp = await fetch(path, {
      method,
      headers: { "X-FC-Token": TOKEN, ...(body !== undefined ? { "Content-Type": "application/json" } : {}) },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch (e) {
    showStopped();
    throw new ServerStopped("The app is not running");
  }
  if (resp.status === 403) {
    // The token changes at every start: this tab belongs to an earlier run.
    throw new Error("This page is from an earlier start of the app. Reload the page (F5).");
  }
  const data = await resp.json().catch(() => ({}));
  if (!resp.ok) throw new Error(data.error || `Error ${resp.status}`);
  return data;
}

// Runs an action and reports its error on the page instead of the console.
async function safely(fn) {
  try { return await fn(); } catch (e) {
    if (!(e instanceof ServerStopped)) flash(e.message, "err");
    return undefined;
  }
}

function flash(text, kind = "info", sticky = false) {
  const box = document.getElementById("flash");
  const msg = h("div", { class: `msg ${kind}` }, h("span", { class: "grow" }, text),
    h("button", { class: "iconbtn", title: "Close", "aria-label": "Close", onclick: () => msg.remove() }, "✕"));
  box.replaceChildren(msg);
  if (!sticky && kind !== "err") setTimeout(() => msg.remove(), 6000);
}

function showStopped() { document.getElementById("stopped").hidden = false; }

function remember(key, value) { try { localStorage.setItem(key, JSON.stringify(value)); } catch (e) { /* private mode */ } }
function recall(key, fallback) { try { const v = localStorage.getItem(key); return v === null ? fallback : JSON.parse(v); } catch (e) { return fallback; } }

// ---------------------------------------------------------------------------
// pronunciation
// ---------------------------------------------------------------------------
//
// The browser's own speech engine (Web Speech API), which speaks with the
// voices installed in the operating system: nothing to install, works offline.
// Languages arrive as codes from the server (models.language_code).
//
// Local voices only, unless you allow online ones: Edge's "Online (Natural)"
// and Chrome's "Google ..." voices send the text to Microsoft / Google, which
// would break "nothing leaves your computer" (README, Privacy). The settings
// are per browser (localStorage), not in config.json: they describe this
// browser's voices, not your vocabulary.
const speech = {
  ok: "speechSynthesis" in window,
  voices: [],
  auto: recall("fc.autoSpeak", true),
  online: recall("fc.onlineVoices", false),
};

function loadVoices() { speech.voices = speech.ok ? speechSynthesis.getVoices() : []; }
if (speech.ok) {
  loadVoices();
  // Voices load asynchronously in Chrome/Edge; redraw so the buttons enable.
  speechSynthesis.addEventListener("voiceschanged", () => { loadVoices(); if (state.overview) render(); });
}

function voiceFor(lang) {
  if (!lang) return null;
  const match = speech.voices.filter((v) => v.lang.toLowerCase().split(/[-_]/)[0] === lang && (v.localService || speech.online));
  return match.find((v) => v.localService && v.default) || match.find((v) => v.localService) || match[0] || null;
}

// Language codes this browser can speak, as allowed by the online-voices setting.
// Sent with every new session: only the page knows the voices, and the server
// needs them to decide whether listening is possible (service.start).
function speakableLanguages() {
  return [...new Set(speech.voices.filter((v) => v.localService || speech.online)
    .map((v) => v.lang.toLowerCase().split(/[-_]/)[0]))];
}

function speak(text, lang, rate = 0.9) {
  const voice = voiceFor(lang);
  if (!voice || !text) return;
  speechSynthesis.cancel();  // a new word interrupts the previous one
  const u = new SpeechSynthesisUtterance(text);
  u.voice = voice;
  u.lang = voice.lang;
  u.rate = rate;  // default 0.9: a little slower than conversation, for learning
  speechSynthesis.speak(u);
}

function noVoiceHint(lang) {
  if (!speech.ok) return "This browser has no speech engine";
  if (!lang) return "Unknown language name in A1/B1, so no voice can be chosen";
  return "No voice for this language on this computer. Windows: Settings → Time & language → Speech → Add voices"
    + (speech.online ? "" : ", or tick “Also use online voices” under Pronunciation on the start page");
}

function speakBtn(text, lang) {
  const voice = voiceFor(lang);
  return h("button", {
    class: "iconbtn speak", type: "button", disabled: !voice,
    title: voice ? `Listen (${voice.name})` : noVoiceHint(lang), "aria-label": `Pronounce ${text}`,
    onclick: (e) => { e.stopPropagation(); speak(text, lang); },
  }, "🔊");
}

// ---------------------------------------------------------------------------
// state
// ---------------------------------------------------------------------------

const state = {
  overview: null,  // /api/overview
  view: null,      // /api/session
  revealed: false, // answer shown for the current card
  form: null,      // deck settings on the start page, seeded from the saved prefs
  busy: false,     // a grade is on its way: ignore key repeats
  shown: 0,        // counts card presentations, so auto-pronounce fires once per card side
  placed: [],      // scramble: tile indices in the order you tapped them (reset per card)
  spoken: null,
};

async function load() {
  const [overview, view] = await Promise.all([api("GET", "/api/overview"), api("GET", "/api/session")]);
  state.overview = overview;
  state.view = view;
  if (!state.form) {
    const p = overview.prefs;
    state.form = {
      mode: p.mode, topics: p.topics || [], threshold: p.difficult_threshold, order: p.order,
      direction: p.direction, size: p.session_size, repeat: p.repeat_until_mastered,
      exercise: p.exercise || "flashcard",
      // ?? not ||: 0 is a valid choice (new words only in leftover slots).
      newPerSession: p.new_per_session ?? 5,
    };
  }
  // Topics renamed or removed in the workbook since they were saved.
  const known = new Set((overview.topics || []).map((t) => t.topic));
  state.form.topics = state.form.topics.filter((t) => known.has(t));
  render();
}

function setView(view) {
  state.view = view;
  state.revealed = false;
  state.shown += 1;
  state.placed = [];
  render();
  window.scrollTo({ top: 0 });
}

function render() {
  const pair = state.overview?.languages;
  document.getElementById("brand-name").textContent = pair ? `${pair.term} Flashcards` : "Flashcards";
  document.title = pair ? `${pair.term} Flashcards` : "Flashcards";
  const chip = document.getElementById("pair");
  chip.hidden = !pair;
  if (pair) chip.textContent = `${pair.term} → ${pair.native}`;

  const root = document.getElementById("view");
  const stage = state.view?.stage || "setup";
  if (stage === "review") root.replaceChildren(renderReview(state.view));
  else if (stage === "summary") root.replaceChildren(renderSummary(state.view));
  else if (stage === "saved") root.replaceChildren(renderSaved(state.view));
  else root.replaceChildren(renderSetup(state.overview));
}

// ---------------------------------------------------------------------------
// start page
// ---------------------------------------------------------------------------

function renderSetup(o) {
  if (!o.reference) return h("div", {}, welcome(o), referencePanel(o, true));
  if (o.error) return h("div", {}, h("div", { class: "msg err" }, o.error), referencePanel(o, true));

  const wrap = h("div", {});
  if (o.schedule_problems?.length) {
    wrap.append(h("div", { class: "msg warn" }, h("span", { class: "grow" },
      `These rows have a schedule (columns M–R) that could not be read and are treated as new: ${o.schedule_problems.join("; ")}`)));
  }
  if (o.language_conflicts?.length) {
    wrap.append(h("div", { class: "msg warn" }, h("span", { class: "grow" },
      `These sheets declare a different language pair in A1/B1: ${o.language_conflicts.join(", ")}`)));
  }

  wrap.append(h("div", { class: "tiles" },
    h("div", { class: "tile hero" }, h("div", { class: "k" }, "Due now"), h("div", { class: "v" }, o.due),
      h("div", { class: "d" }, "words to review")),
    h("div", { class: "tile" }, h("div", { class: "k" }, "New"), h("div", { class: "v" }, o.new),
      h("div", { class: "d" }, "never scheduled")),
    h("div", { class: "tile" }, h("div", { class: "k" }, "Later"), h("div", { class: "v" }, o.later),
      h("div", { class: "d" }, o.next_due ? `next ${o.next_due}` : "nothing scheduled"))));

  wrap.append(deckForm(o));
  wrap.append(topicTable(o));
  wrap.append(speechPanel(o));
  wrap.append(referencePanel(o, false));
  return wrap;
}

function welcome(o) {
  return h("div", { class: "card" },
    h("h2", {}, "Welcome"),
    h("p", {}, "Choose the Excel workbook with your vocabulary. It is remembered, so you only do this once."),
    h("p", { class: "muted" }, "Each topic sheet needs the two language names in A1 and B1 and ",
      h("code", {}, "Confidence (1-5)"), " in L1. See the README for the full layout."),
    o.stored_reference ? h("p", { class: "muted" }, "The workbook used before is missing: ", h("code", {}, o.stored_reference)) : null);
}

function deckForm(o) {
  const f = state.form;
  const scopeLine = h("div", { class: "scope" }, " ");
  const startBtn = h("button", { class: "btn primary big", onclick: () => start() }, "Start review");

  let timer = null;
  const refreshScope = () => {
    clearTimeout(timer);
    timer = setTimeout(async () => {
      if (f.mode === "topic" && !f.topics.length) {
        scopeLine.textContent = "Pick at least one topic.";
        startBtn.disabled = true;
        return;
      }
      try {
        const s = await api("POST", "/api/scope", deckParams());
        startBtn.disabled = !s.drawn;
        const parts = f.mode === "due" ? ` (${s.due} due, ${s.new} new)` : "";
        scopeLine.textContent = !s.in_scope ? "No words match these settings."
          : s.in_scope > s.drawn ? `${s.in_scope} words in scope; this session draws ${s.drawn}${parts}.`
          : `${s.drawn} word${s.drawn === 1 ? "" : "s"} in this session${parts}.`;
      } catch (e) {
        if (!(e instanceof ServerStopped)) scopeLine.textContent = e.message;
      }
    }, 150);
  };
  const set = (key, value, rerender = false) => {
    f[key] = value;
    if (rerender) render(); else refreshScope();
  };

  const seg = h("div", { class: "seg", role: "group", "aria-label": "Mode" },
    MODES.map(([key, label, tip]) => h("button", {
      "aria-pressed": String(f.mode === key), title: tip, onclick: () => set("mode", key, true),
    }, label)));

  const topicsBox = h("div", { class: "topic-list" },
    (o.topics || []).map((t) => h("label", { class: "check" },
      h("input", { type: "checkbox", checked: f.topics.includes(t.topic), onchange: (e) => {
        set("topics", e.target.checked ? [...f.topics, t.topic] : f.topics.filter((x) => x !== t.topic));
      } }), t.topic)));
  const showTopics = f.mode !== "mix";
  const topicsHint = f.mode === "topic" ? "Topics" : "Limit to topics (none ticked = all)";

  const pair = o.languages;
  const directions = { mixed: "Mixed (random)", term_to_native: `${pair.term} → ${pair.native}`, native_to_term: `${pair.native} → ${pair.term}` };

  const grid = h("div", { class: "form-grid" },
    h("label", { class: "field", title: "Fill in the blank uses each word's example sentence; a word whose sentence doesn't contain it gets multiple choice instead" },
      "Exercise",
      h("select", { onchange: (e) => set("exercise", e.target.value) },
        Object.entries(EXERCISE_LABELS).map(([k, v]) => {
          // Article: only with der/die/das nouns (service.overview article_words).
          // Listening: only with a voice for the word's language.
          // Forms: only with plurals / verb forms in column E (service.overview forms_words).
          const noVoice = k === "listen" && !voiceFor(o.language_codes?.term);
          const count = { article: [o.article_words, "nouns"], forms: [o.forms_words, "words"] }[k];
          return h("option", { value: k, selected: f.exercise === k, disabled: (count && !count[0]) || noVoice },
            count && count[0] ? `${v} · ${count[0]} ${count[1]}` : noVoice ? `${v} (no ${o.languages.term} voice)` : v);
        }))),
    h("label", { class: "field" }, "Order",
      h("select", { title: f.mode === "due" ? "Due words always come most-forgotten first; this orders the new words after them" : null,
        onchange: (e) => set("order", e.target.value) },
        Object.entries(ORDER_LABELS).map(([k, v]) => h("option", { value: k, selected: f.order === k }, v)))),
    h("label", { class: "field" }, "Direction",
      h("select", { onchange: (e) => set("direction", e.target.value) },
        Object.entries(directions).map(([k, v]) => h("option", { value: k, selected: f.direction === k }, v)))),
    h("label", { class: "field" }, "Words per session",
      h("input", { type: "number", min: 1, max: 500, step: 5, value: f.size,
        onchange: (e) => set("size", Math.max(1, Math.min(500, parseInt(e.target.value, 10) || 20))) })),
    f.mode === "due" ? h("label", { class: "field", title: "Kept for words never scheduled, even when more words are due. 0: new words only fill what the due words leave." },
      "New words per session",
      h("input", { type: "number", min: 0, max: 500, step: 1, value: f.newPerSession,
        onchange: (e) => { const n = parseInt(e.target.value, 10); set("newPerSession", Math.max(0, Math.min(500, Number.isNaN(n) ? 5 : n))); } })) : null,
    f.mode === "difficult" ? h("label", { class: "field" }, "“Difficult” means confidence ≤",
      h("select", { onchange: (e) => set("threshold", parseInt(e.target.value, 10)) },
        [1, 2, 3, 4, 5].map((n) => h("option", { value: n, selected: f.threshold === n }, n)))) : null);

  refreshScope();
  return h("div", { class: "card" },
    h("h2", {}, "Build a deck"),
    h("div", { style: { margin: ".8rem 0" } }, seg),
    showTopics ? h("div", { style: { marginBottom: ".9rem" } }, h("div", { class: "muted", style: { marginBottom: ".35rem" } }, topicsHint), topicsBox) : null,
    grid,
    h("div", { style: { marginTop: ".9rem" } }, h("label", { class: "check" },
      h("input", { type: "checkbox", checked: f.repeat, onchange: (e) => set("repeat", e.target.checked) }),
      "Keep repeating words below 5 until the deck is clear")),
    scopeLine,
    startBtn);
}

function deckParams() {
  const f = state.form;
  return { mode: f.mode, topics: f.mode === "mix" ? [] : f.topics, threshold: f.threshold, order: f.order,
    direction: f.direction, size: f.size, repeat: f.repeat, exercise: f.exercise, voices: speakableLanguages(),
    new_per_session: f.newPerSession };
}

async function start() {
  await safely(async () => setView(await api("POST", "/api/session", deckParams())));
}

function topicTable(o) {
  const rows = (o.topics || []).map((t) => h("tr", {},
    h("td", {}, t.topic), h("td", { class: "num" }, t.words), h("td", { class: "num" }, t.due),
    h("td", { class: "num" }, t.new), h("td", { class: "num" }, t.graded),
    h("td", { class: "num" }, t.avg_confidence ?? "—")));
  return h("details", { class: "panel" }, h("summary", {}, `Topics (${o.words} words)`),
    h("div", { class: "body table-wrap" }, h("table", {},
      h("thead", {}, h("tr", {}, ["Topic", "Words", "Due", "New", "Graded", "Avg conf."].map((c, i) => h("th", { class: i ? "num" : null }, c)))),
      h("tbody", {}, rows))));
}

function speechPanel(o) {
  const line = (name, lang) => {
    const v = voiceFor(lang);
    return h("li", {}, h("b", {}, name), ": ", v ? `${v.name}${v.localService ? "" : " (online)"}` : h("span", { class: "muted" }, noVoiceHint(lang)));
  };
  return h("details", { class: "panel" }, h("summary", {}, "Pronunciation"),
    h("div", { class: "body" },
      h("p", { class: "muted" }, "Words are spoken by your browser with the voices installed on this computer. "
        + "More voices: Windows Settings → Time & language → Speech → Add voices."),
      h("ul", {}, line(o.languages.term, o.language_codes?.term), line(o.languages.native, o.language_codes?.native)),
      h("label", { class: "check" },
        h("input", { type: "checkbox", checked: speech.auto, onchange: (e) => { speech.auto = e.target.checked; remember("fc.autoSpeak", speech.auto); } }),
        "Pronounce the word automatically during review"),
      h("br"),
      h("label", { class: "check" },
        h("input", { type: "checkbox", checked: speech.online, onchange: (e) => { speech.online = e.target.checked; remember("fc.onlineVoices", speech.online); render(); } }),
        "Also use online voices (better quality; the word is sent to Microsoft or Google)")));
}

function referencePanel(o, open) {
  const input = h("input", { type: "text", placeholder: "C:\\Users\\you\\OneDrive\\German_learning.xlsx", value: "" });
  const use = async (path) => {
    await safely(async () => {
      state.overview = await api("PUT", "/api/reference", { path });
      state.form = null;
      await load();
      flash("Workbook set.", "ok");
    });
  };
  const browse = async () => {
    await safely(async () => {
      const r = await api("POST", "/api/browse");
      if (r.path) await use(r.path);
      else flash("No file chosen. You can also paste the full path.");
    });
  };
  return h("details", { class: "panel", open },
    h("summary", {}, o.reference ? "Workbook" : "Choose your workbook"),
    h("div", { class: "body" },
      o.reference ? h("p", {}, h("code", {}, o.reference), h("span", { class: "muted" }, `  · modified ${o.modified || "?"}`)) : null,
      h("div", { class: "row" },
        h("button", { class: "btn", onclick: browse }, "Browse…"),
        h("div", { class: "grow" }, input),
        h("button", { class: "btn primary", onclick: () => use(input.value) }, "Use this file")),
      h("p", { class: "muted" }, "The app edits this file in place (columns L–R only) and saves a backup to ",
        h("code", {}, `${o.data_folder}/backups`), " before every change.")));
}

// ---------------------------------------------------------------------------
// review
// ---------------------------------------------------------------------------

function renderReview(v) {
  const c = v.card;
  const p = v.progress;
  const ex = v.exercise;
  const res = v.result;
  const pct = p.total ? Math.round((100 * p.done) / p.total) : 0;
  // "Answered": the answer side is open, by Show answer or by a checked exercise.
  const answered = state.revealed || !!res;
  const frontIsTerm = ex.kind !== "blank" && c.front.text === c.back.term;

  const card = h("div", { class: "flash-card" });
  if (c.front.text) {
    card.append(h("div", { class: "front-row" }, txt("div", c.front.text, c.front.rtl, "front"), speakBtn(c.front.text, c.front.lang)));
    if (c.front.hint) card.append(h("div", { class: "hint" }, c.front.hint));
  }
  if (ex.kind === "blank") card.append(blankPart(ex, res));
  card.append(h("div", { class: "meta" },
    h("span", { class: `chip ${c.schedule === "new word" ? "new" : c.schedule === "due now" ? "due" : ""}` }, c.schedule),
    h("span", { class: "chip" }, `confidence ${c.confidence}`)));

  if (!answered) {
    if (frontIsTerm) autoSpeak(c.front.text, c.front.lang);
    if (ex.kind === "choice" || ex.kind === "article") card.append(choicePart(ex, null));
    else if (ex.kind === "recall") card.append(blankPart(ex, null));
    else if (ex.kind === "listen") card.append(listenPart(ex));
    else if (ex.kind === "scramble") card.append(scramblePart(ex));
    else if (ex.kind === "forms") card.append(formsPart(ex));
    else if (ex.kind === "flashcard") {
      card.append(h("div", { class: "reveal" },
        h("button", { class: "btn primary big wide", id: "show", onclick: reveal }, "Show answer")));
    }
  } else {
    // The front didn't say the word: hearing it is part of the answer.
    if (!frontIsTerm) autoSpeak(c.back.term, c.back.term_lang);
    if (ex.kind === "choice" || ex.kind === "article") card.append(choicePart(ex, res));
    if (res && ex.kind === "scramble") card.append(scrambleResult(ex, res));
    if (res) card.append(verdict(res, ex));
    card.append(answer(c.back));
    if (res) card.append(h("div", { class: "muted", style: { marginTop: "1rem" } },
      "Suggested grade highlighted: press ", h("kbd", {}, "Enter"), " to take it, or pick another."));
    card.append(h("div", { class: "grades" }, v.grades.map((g) => h("button", {
      class: `grade${res && res.suggested === g.grade ? " suggested" : ""}`,
      style: { "--gc": `var(--g${g.grade})` }, title: g.next,
      onclick: () => grade(g.grade),
    }, h("b", {}, g.grade), h("span", { class: "gl" }, g.label),
      h("span", { class: "gn" }, `→ ${g.confidence} · ${g.next.replace("next review ", "")}`)))));
  }

  const keys = !answered && (ex.kind === "choice" || ex.kind === "article") ? [h("kbd", {}, "1"), "–", h("kbd", {}, String(ex.options.length)), " choose · "]
    : !answered && ex.kind === "scramble" ? [h("kbd", {}, "Backspace"), " undo · ", h("kbd", {}, "Enter"), " check · "]
    : !answered && ex.kind === "listen" ? [h("kbd", {}, "Enter"), " check · ", h("kbd", {}, "Ctrl"), "+", h("kbd", {}, "Space"), " play again · "]
    : !answered && (ex.kind === "blank" || ex.kind === "recall" || ex.kind === "forms") ? [h("kbd", {}, "Enter"), " check · "]
    : !answered ? [h("kbd", {}, "Space"), " show answer · "]
    : [h("kbd", {}, "1"), "–", h("kbd", {}, "5"), " grade · "];
  return h("div", {},
    h("div", { class: "progress", role: "progressbar", "aria-valuenow": pct, "aria-valuemin": 0, "aria-valuemax": 100 },
      h("div", { style: { width: `${pct}%` } })),
    h("div", { class: "caption" },
      h("span", {}, `${p.done} / ${p.total} words graded${p.repeats ? ` · ${p.repeats} repeat${p.repeats === 1 ? "" : "s"} queued` : ""}`),
      h("span", {}, `${c.topic} · ${EXERCISE_LABELS[ex.kind]}${["blank", "listen", "scramble", "forms"].includes(ex.kind) ? "" : ` · ${c.direction_label}`}`)),
    card,
    h("div", { class: "review-foot" },
      h("button", { class: "btn ghost", onclick: () => act("/api/session/skip"), title: "Next word, no change (S)" }, "Skip"),
      h("label", { class: "check muted", title: "Say the word when it appears (on the answer side if the card doesn't show it)" },
        h("input", { type: "checkbox", checked: speech.auto, onchange: (e) => { speech.auto = e.target.checked; remember("fc.autoSpeak", speech.auto); } }),
        "Pronounce automatically"),
      h("button", { class: "btn ghost", onclick: () => act("/api/session/end"), title: "Go to the summary; nothing is saved yet" }, "End session")),
    h("div", { class: "keys" }, ...keys, h("kbd", {}, "S"), " skip · ", h("kbd", {}, "P"), " pronounce"));
}

// Multiple choice: numbered options; after checking, the right one green and a wrong pick red.
function choicePart(ex, res) {
  // der / die / das side by side; real options (meanings, words) one per line.
  return h("div", { class: ex.kind === "article" ? "options row" : "options" }, ex.options.map((text, i) => {
    const cls = !res ? "" : text === res.expected ? " right" : i === res.picked ? " wrong" : " dim";
    return h("button", { class: `option${cls}`, disabled: !!res, dir: ex.rtl ? "rtl" : "auto",
      onclick: () => check(i) }, h("span", { class: "num" }, i + 1), text);
  }));
}

// Fill in the blank: the sentence with an input in the gap; the meaning as a hint.
function blankPart(ex, res) {
  const wrap = h("div", { class: "blank" });
  if (res && ex.kind === "recall") return wrap;  // the answer section below shows the word
  if (res) {
    wrap.append(h("div", { class: "sentence", dir: ex.rtl ? "rtl" : "auto" }, ex.before, h("mark", {}, res.expected), ex.after));
    return wrap;
  }
  const input = h("input", { type: "text", class: "gap", autocomplete: "off", autocapitalize: "off", spellcheck: false,
    "aria-label": "The missing word", size: Math.max(6, Math.min(24, (ex.answer_len || 10))),
    onkeydown: (e) => { if (e.key === "Enter") { e.preventDefault(); check(input.value); } } });
  // Through filter: native append() would print a skipped (null) hint as the text "null".
  wrap.append(...[
    h("div", { class: "sentence", dir: ex.rtl ? "rtl" : "auto" }, ex.before, input, ex.after),
    ex.hint ? h("div", { class: "blank-hint" }, txt("span", ex.hint, ex.hint_rtl)) : null,
    ex.hint_example ? h("div", { class: "blank-hint it" }, txt("span", ex.hint_example, ex.hint_rtl)) : null,
    h("div", { class: "reveal" }, h("button", { class: "btn primary big wide", onclick: () => check(input.value) }, "Check")),
  ].filter(Boolean));
  // After the page is drawn, so the field exists to focus.
  setTimeout(() => input.focus(), 0);
  return wrap;
}

// Listening: the word is played once when the card appears (whatever the
// auto-pronounce setting: here the sound IS the question), then on demand.
function listenPart(ex) {
  const play = (rate) => { speak(ex.say, ex.lang, rate); input.focus(); };
  const input = h("input", { type: "text", class: "gap", autocomplete: "off", autocapitalize: "off", spellcheck: false,
    "aria-label": "What you heard", size: Math.max(8, Math.min(28, ex.answer_len || 10)),
    onkeydown: (e) => {
      if (e.key === "Enter") { e.preventDefault(); check(input.value); }
      else if (e.key === " " && e.ctrlKey) { e.preventDefault(); play(); }
    } });
  const key = `listen:${state.shown}`;
  if (state.spoken !== key) {
    state.spoken = key;
    setTimeout(() => play(), 150);  // after the page is drawn
  } else {
    setTimeout(() => input.focus(), 0);
  }
  return h("div", { class: "blank" },
    h("div", { class: "row", style: { justifyContent: "center", margin: ".4rem 0 1rem" } },
      h("button", { class: "btn big", onclick: () => play() }, "🔊 Play again"),
      h("button", { class: "btn big", onclick: () => play(0.55), title: "Slower" }, "🐢 Slow")),
    h("div", { class: "sentence" }, input),
    ex.hint ? h("div", { class: "blank-hint" }, ex.hint) : null,
    h("div", { class: "reveal" }, h("button", { class: "btn primary big wide", onclick: () => check(input.value) }, "Check")));
}

// Sentence scramble: tap tiles into the line in order; tap a placed tile to put
// it back. Only the order is sent to the server (service.check), as tile indices.
function scramblePart(ex) {
  const placed = state.placed;
  const done = placed.length === ex.tiles.length;
  const tile = (i, onclick) => h("button", { class: "tile-btn", dir: "auto", onclick }, ex.tiles[i]);
  return h("div", { class: "scramble" },
    h("div", { class: "blank-hint" }, txt("span", ex.hint, ex.hint_rtl)),
    h("div", { class: `built${placed.length ? "" : " empty"}`, dir: ex.rtl ? "rtl" : "ltr" },
      placed.length ? placed.map((i, k) => tile(i, () => { placed.splice(k, 1); render(); })) : "Tap the words in order",
      done && ex.end ? h("span", { class: "end" }, ex.end) : null),
    h("div", { class: "pool", dir: ex.rtl ? "rtl" : "ltr" },
      ex.tiles.map((_, i) => placed.includes(i) ? h("span", { class: "tile-btn ghost" }, ex.tiles[i])
        : tile(i, () => { placed.push(i); render(); }))),
    h("div", { class: "reveal" }, h("button", { class: "btn primary big wide", disabled: !done,
      onclick: () => check([...placed]) }, done ? "Check" : `${ex.tiles.length - placed.length} word${ex.tiles.length - placed.length === 1 ? "" : "s"} left`)));
}

// Forms drill: "der Stift → Plural", type the form.
function formsPart(ex) {
  const input = h("input", { type: "text", class: "gap", autocomplete: "off", autocapitalize: "off", spellcheck: false,
    "aria-label": ex.label, size: Math.max(8, Math.min(28, ex.answer_len || 10)),
    onkeydown: (e) => { if (e.key === "Enter") { e.preventDefault(); check(input.value); } } });
  setTimeout(() => input.focus(), 0);
  return h("div", { class: "blank" },
    h("div", { class: "forms-ask" }, txt("span", ex.prompt, ex.rtl), " → ", h("b", {}, ex.label)),
    h("div", { class: "sentence" }, input),
    h("div", { class: "reveal" }, h("button", { class: "btn primary big wide", onclick: () => check(input.value) }, "Check")));
}

function scrambleResult(ex, res) {
  return h("div", { class: "blank" },
    h("div", { class: "sentence", dir: ex.rtl ? "rtl" : "auto" }, h("mark", {}, res.expected)),
    res.verdict !== "correct" ? h("div", { class: "blank-hint" }, "Yours: ", h("span", { dir: "auto" }, res.given)) : null);
}

function verdict(res, ex) {
  // One span inside .msg: .msg is a flex row and would put gaps between the bold parts.
  const box = (kind, ...parts) => h("div", { class: `msg ${kind} verdict` }, h("span", { class: "grow" }, ...parts));
  if (res.verdict === "correct") return box("ok", "✓ Correct");
  // Before the spelling message: a scramble's "close" is about order, not spelling.
  if (ex.kind === "scramble") {
    // The sentence itself is shown above (scrambleResult); repeating it here would be noise.
    return res.verdict === "close" ? box("warn", "≈ Almost: two neighbouring words are swapped.")
      : box("err", "✗ Not quite: compare your order with the sentence above.");
  }
  if (res.verdict === "close" && ex.kind === "forms") {
    // The only "close" in the forms drill: the participle without hat/ist (exercises.check_forms).
    return box("warn", "≈ Almost. You wrote ", h("b", { dir: "auto" }, res.given), ", the form is ",
      h("b", { dir: "auto" }, res.expected), ".");
  }
  if (res.verdict === "close") {
    return box("warn", "≈ Almost: check the spelling. You wrote ", h("b", { dir: "auto" }, res.given),
      ex.kind === "blank" ? ", the sentence has " : ", the word is ", h("b", { dir: "auto" }, res.expected), ".");
  }
  return box("err", "✗ Not quite. The answer is ", h("b", { dir: "auto" }, res.expected),
    res.given ? h("span", {}, " (you wrote ", h("b", { dir: "auto" }, res.given), ")") : null, ".");
}

async function check(answerValue) {
  await safely(async () => {
    // Not setView: same card, so auto-pronounce and the turn counter stay as they are.
    state.view = await api("POST", "/api/session/check", { answer: answerValue });
    render();
  });
}

function answer(b) {
  const out = h("div", { class: "answer" });
  if (b.article) out.append(h("div", { class: "muted" }, `${b.article_label}: `, h("b", {}, b.article)));
  out.append(h("div", { class: "front-row start" }, txt("div", b.term, b.term_rtl, "term"), speakBtn(b.term, b.term_lang)));
  if (b.pronunciation) out.append(h("div", { class: "hint", style: { textAlign: "left" } }, b.pronunciation));
  out.append(h("div", { class: "label" }, b.native_language));
  out.append(h("div", { class: "front-row start" }, txt("div", b.meaning, b.native_rtl, "meaning"), speakBtn(b.meaning, b.native_lang)));
  const meta = [b.word_type, b.forms ? `${b.forms_label}: ${b.forms}` : null].filter(Boolean);
  if (meta.length) out.append(h("div", { class: "muted", style: { marginTop: ".4rem" } }, meta.join(" · ")));
  if (b.example_term) {
    out.append(h("div", { class: "label" }, "Example"));
    out.append(h("div", { class: "front-row start" }, txt("div", b.example_term, b.term_rtl, "quote"), speakBtn(b.example_term, b.term_lang)));
    if (b.example_meaning) out.append(h("div", { class: "front-row start" }, txt("div", b.example_meaning, b.native_rtl, "quote it"), speakBtn(b.example_meaning, b.native_lang)));
  }
  if (b.synonyms) {
    out.append(h("div", { class: "label" }, "Related"));
    out.append(txt("div", b.synonyms, b.term_rtl));
  }
  if (b.notes) {
    out.append(h("div", { class: "label" }, "Notes"));
    out.append(h("div", {}, b.notes));
  }
  return out;
}

function autoSpeak(text, lang) {
  // Once per card side: render() also runs for unrelated reasons (voices loading).
  const key = `${state.shown}:${state.revealed || !!state.view?.result}`;
  if (!speech.auto || state.spoken === key) return;
  state.spoken = key;
  speak(text, lang);
}

function reveal() {
  state.revealed = true;
  render();
}

async function grade(n) {
  if (state.busy) return;
  state.busy = true;
  try { await act("/api/session/grade", { grade: n }); } finally { state.busy = false; }
}

async function act(path, body) {
  await safely(async () => setView(await api("POST", path, body)));
}

document.addEventListener("keydown", (e) => {
  if (state.view?.stage !== "review" || e.ctrlKey || e.metaKey || e.altKey) return;
  if (["INPUT", "SELECT", "TEXTAREA"].includes(document.activeElement?.tagName)) return;
  const ex = state.view.exercise;
  const res = state.view.result;
  const answered = state.revealed || !!res;
  if (!answered && ex.kind === "scramble" && (e.key === "Backspace" || e.key === "Enter")) {
    e.preventDefault();
    if (e.key === "Backspace" && state.placed.length) { state.placed.pop(); render(); }
    if (e.key === "Enter" && state.placed.length === ex.tiles.length) check([...state.placed]);
  } else if (!answered && (ex.kind === "choice" || ex.kind === "article") && /^[1-9]$/.test(e.key) && Number(e.key) <= ex.options.length) {
    e.preventDefault();
    check(Number(e.key) - 1);
  } else if (!answered && ex.kind === "flashcard" && (e.key === " " || e.key === "Enter")) {
    e.preventDefault();
    reveal();
  } else if (res && e.key === "Enter") {
    e.preventDefault();
    grade(res.suggested);
  } else if (answered && /^[1-5]$/.test(e.key)) {
    e.preventDefault();
    grade(Number(e.key));
  } else if (e.key === "s" || e.key === "S") {
    act("/api/session/skip");
  } else if (e.key === "p" || e.key === "P") {
    const c = state.view.card;
    speak(c.back.term, c.back.term_lang);
  }
});

// ---------------------------------------------------------------------------
// summary and saved
// ---------------------------------------------------------------------------

function renderSummary(v) {
  if (!v.rows.length) {
    return h("div", { class: "card" }, h("h2", {}, "Session summary"),
      h("p", {}, "No words were graded, so there is nothing to save."),
      h("button", { class: "btn primary", onclick: discard }, "Back to start"));
  }
  const pair = v.languages;
  const table = h("table", {},
    h("thead", {}, h("tr", {}, ["Topic", pair.term, pair.native, "Grades", "Confidence", "Next review"].map((c) => h("th", {}, c)))),
    h("tbody", {}, v.rows.map((r) => h("tr", {},
      h("td", {}, r.topic), txt("td", r.term, r.term_rtl), txt("td", r.meaning, r.meaning_rtl),
      h("td", {}, r.grades.join(", ")),
      h("td", { class: "num" }, r.old === r.new ? r.new : `${r.old} → ${r.new}`),
      h("td", {}, r.next)))));
  const save = h("button", { class: "btn primary", onclick: async () => {
    save.disabled = true;
    await safely(async () => setView(await api("POST", "/api/session/save")));
    save.disabled = false;
  } }, "Save to workbook");
  return h("div", { class: "card" },
    h("h2", {}, "Session summary"),
    h("p", { class: "muted" }, `${v.rows.length} word${v.rows.length === 1 ? "" : "s"} graded; ${v.cells} cell${v.cells === 1 ? "" : "s"} will change in `, h("code", {}, v.path),
      ". A backup is made first."),
    h("div", { class: "table-wrap", style: { margin: ".8rem 0" } }, table),
    h("div", { class: "row" }, save,
      h("button", { class: "btn danger", onclick: () => { if (confirm("Throw away this session's grades?")) discard(); } }, "Discard")));
}

async function discard() {
  await safely(async () => {
    await api("DELETE", "/api/session");
    await load();
  });
}

function renderSaved(v) {
  return h("div", { class: "card" },
    h("h2", {}, "Saved"),
    h("p", {}, `Wrote ${v.words} word${v.words === 1 ? "" : "s"} (${v.cells} cells) to ${v.sheets.join(", ")}.`),
    v.warning ? h("div", { class: "msg warn" }, v.warning) : null,
    h("p", { class: "muted" }, "Backup: ", h("code", {}, v.backup)),
    h("div", { class: "row", style: { marginTop: ".8rem" } },
      h("button", { class: "btn primary", onclick: discard }, "Back to start"),
      state.info?.can_open_folder ? h("button", { class: "btn", onclick: () => safely(() => api("POST", "/api/open-backups")) }, "Open backups folder") : null));
}

// ---------------------------------------------------------------------------
// start-up
// ---------------------------------------------------------------------------

// The theme picker in the top bar; theme.js (loaded in <head>) owns the setting.
const themePicker = document.getElementById("theme");
themePicker.value = window.fcTheme.get();
themePicker.addEventListener("change", (e) => window.fcTheme.set(e.target.value));

document.getElementById("quit").addEventListener("click", async () => {
  if (state.view?.stage === "review" || state.view?.stage === "summary") {
    if (!confirm("This session's grades are not saved yet. Quit anyway?")) return;
  }
  await safely(() => api("POST", "/api/quit"));
  showStopped();
});

setInterval(() => { fetch("/api/ping").catch(showStopped); }, HEARTBEAT_MS);

(async () => {
  await safely(async () => {
    state.info = await api("GET", "/api/info");
    document.getElementById("footer").textContent = `Flashcards ${state.info.version} · settings and backups in ${state.info.data_folder}`;
    if (state.info.notice) flash(state.info.notice, "info", true);
    await load();
  });
})();
