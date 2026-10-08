// Colour theme: System (follows the computer's light/dark setting), Light, Dark or Sepia.
//
// Loaded in <head>, before the stylesheet and without defer, so data-theme is
// set before the first paint: otherwise a dark page flashes white on every
// load. An external file, not an inline <script>: the page's
// Content-Security-Policy (app.py) allows scripts from 'self' only.
//
// style.css holds the palettes (:root, and :root[data-theme="dark" | "sepia"]);
// this file only decides which one applies. The choice is per browser
// (localStorage), like the pronunciation settings in app.js: it describes
// this screen, not your vocabulary, so it stays out of config.json.
"use strict";

(function () {
  const KEY = "fc.theme";
  const THEMES = ["system", "light", "dark", "sepia"];
  const media = window.matchMedia("(prefers-color-scheme: dark)");

  function stored() {
    try {
      const v = localStorage.getItem(KEY);
      return THEMES.includes(v) ? v : "system";
    } catch (e) { return "system"; }  // private mode / storage blocked
  }

  function apply(choice) {
    // Only "system" needs resolving; the others name their palette directly.
    const resolved = choice === "system" ? (media.matches ? "dark" : "light") : choice;
    document.documentElement.dataset.theme = resolved;
  }

  // Used by the picker in app.js.
  window.fcTheme = {
    get: stored,
    set(choice) {
      if (!THEMES.includes(choice)) return;
      try { localStorage.setItem(KEY, choice); } catch (e) { /* not remembered, still applied */ }
      apply(choice);
    },
  };

  // "System" follows the OS live, e.g. when Windows switches to dark in the evening.
  media.addEventListener("change", () => apply(stored()));
  apply(stored());
})();
