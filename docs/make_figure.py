"""Generate docs/architecture.svg, the overview figure shown in README.md.

Plain SVG written by hand (standard library only), same approach and layout as
time_tracker/docs/make_figure.py, so it renders on GitHub and needs no
plotting package. Re-run after changing what it shows (screens, modules,
port, data folder):

    python docs/make_figure.py

Facts it mirrors, kept in sync by hand: the three stages of the page
(flashcards/web/static/app.js render()), the port 8767
(flashcards/web/launcher.py DEFAULT_PORT), the data folder ~/.flashcards
(flashcards/paths.py), the written columns L-R (srs.py SRS_COLUMNS) and the
module names in flashcards/.
"""
from pathlib import Path
from xml.sax.saxutils import escape

W, H = 1280, 720
# The app's palette (flashcards/web/static/style.css).
PAGE, PAGE_SOFT = "#0F766E", "#CCFBF1"
SERVER, SERVER_SOFT = "#2563EB", "#DBEAFE"
DATA, DATA_SOFT = "#6b7785", "#f1f4f8"
INK, MUTED, LINE, EDGE = "#042F2E", "#5f6b7a", "#c9d1db", "#8a94a3"
out = []
a = out.append


def text(x, y, s, size=12, weight=400, fill=MUTED, anchor="start", style=""):
    a(f'<text x="{x}" y="{y}" font-size="{size}" font-weight="{weight}" fill="{fill}" text-anchor="{anchor}"{style}>{escape(s)}</text>')


def box(x, y, w, h, title, lines, color, soft):
    a(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="10" fill="{soft}" stroke="{color}" stroke-width="1.5"/>')
    cx = x + w / 2
    text(cx, y + 26, title, 15, 700, INK, "middle")
    for i, ln in enumerate(lines):
        text(cx, y + 46 + i * 17, ln, 12, 400, MUTED, "middle")


def layer(x, y, name, note, color):
    # One text element with tspans: the note follows the name at whatever
    # width the viewer's font gives it, instead of a guessed x position.
    a(f'<text x="{x}" y="{y}" font-size="13"><tspan font-weight="800" fill="{color}" letter-spacing="1">{name}</tspan>'
      f'<tspan dx="10" fill="{MUTED}">{escape(note)}</tspan></text>')


def arrow(d, label=None, lx=0, ly=0, anchor="start"):
    a(f'<path d="{d}" fill="none" stroke="{MUTED}" stroke-width="2" marker-end="url(#arrow)"/>')
    if label:
        text(lx, ly, label, 11, 600, MUTED, anchor)



a(f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" '
  'font-family="system-ui, -apple-system, \'Segoe UI\', Roboto, Helvetica, Arial, sans-serif">')
a('<title>How Flashcards works</title>')
a('<desc>The desktop icon starts a small web server on this computer and opens its page in the browser. '
  'The page has three screens: Start, Review and Summary. Grades stay in the server until you press Save; '
  'then the confidence and the FSRS schedule are written into columns L to R of your own Excel workbook, '
  'after a backup to the ~/.flashcards folder. Code updates come from GitHub with git pull and never touch '
  'the workbook or that folder.</desc>')
a('<defs><marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">'
  f'<path d="M0,0 L10,5 L0,10 z" fill="{MUTED}"/></marker></defs>')
# Own background, so the figure reads the same on GitHub's light and dark themes.
a(f'<rect x="0.5" y="0.5" width="{W-1}" height="{H-1}" rx="16" fill="#ffffff" stroke="#e3e7ec"/>')

text(40, 52, "How Flashcards works", 26, 800, INK)
text(40, 78, "Review the vocabulary in your Excel workbook with spaced repetition (FSRS). "
     "A small web app that runs in your browser, entirely on your computer.", 14, 400, MUTED)

a('<circle cx="105" cy="222" r="42" fill="#1f2328"/>')
a('<path transform="translate(105,222) scale(1.6) translate(-8,-8)" fill="#fff" d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z"/>')
text(105, 288, "GitHub", 15, 700, INK, "middle")
text(105, 306, "the code only", 12, 400, MUTED, "middle")

# Edge of the computer
a(f'<rect x="210" y="112" width="1040" height="538" rx="18" fill="none" stroke="{EDGE}" stroke-width="1.5" stroke-dasharray="7 6"/>')
text(232, 138, "YOUR COMPUTER", 12, 800, EDGE, style=' letter-spacing="1.5"')

# --- left column: code and launcher
box(250, 168, 180, 110, "Code", ["the git clone,", "installed with", "pip install -e ."], DATA, "#ffffff")
arrow("M150,222 H246", "git pull", 178, 212, "middle")
arrow("M340,278 V326")
box(250, 330, 180, 96, "Desktop icon", ["flashcards-gui", "starts the server,", "opens the browser"], DATA, "#ffffff")
arrow("M430,378 H466")

# --- layer 1: the page
layer(470, 154, "BROWSER PAGE", "http://127.0.0.1:8767 · three screens", PAGE)
# Three boxes of 240 with gaps of 14 fill x = 470…1218, the width of the layers below.
screens = [("Start", ["due / new counts,", "build a deck"]),
           ("Review", ["show answer,", "grade 1–5 (keys work)"]),
           ("Summary", ["check the changes,", "then Save or Discard"])]
for i, (title, lines) in enumerate(screens):
    box(470 + i * 254, 168, 240, 86, title, lines, PAGE, PAGE_SOFT)
arrow("M585,256 V326", "every click, sent with a key", 596, 284)
text(596, 299, "that changes at each start", 11, 400, MUTED)

# --- layer 2: the local server
layer(740, 316, "LOCAL SERVER", "on this computer; stops 10 min after you close the tab", SERVER)
box(470, 330, 230, 96, "web/app.py", ["Flask, on this computer only;", "checks host and key"], SERVER, SERVER_SOFT)
arrow("M700,378 H736")
box(740, 330, 230, 96, "service.py", ["what each button does;", "holds the session until Save"], SERVER, SERVER_SOFT)
arrow("M970,378 H1006")
box(1010, 330, 208, 96, "session.py · srs.py", ["the deck, repeats,", "FSRS due dates"], SERVER, SERVER_SOFT)

# --- layer 3: the files
layer(750, 630, "YOUR FILES", "updates never touch them", DATA)
arrow("M855,426 V496", "writeback.py, on Save", 866, 466)
# the workbook: a sheet with a folded corner
x, y, w, h = 750, 500, 210, 104
a(f'<path d="M{x},{y} h{w-22} l22,22 v{h-22} h-{w} z" fill="{DATA_SOFT}" stroke="{DATA}" stroke-width="1.5" stroke-linejoin="round"/>')
text(x + w / 2, y + 42, "your workbook .xlsx", 15, 700, INK, "middle")
text(x + w / 2, y + 62, "wherever you keep it;", 12, 400, MUTED, "middle")
text(x + w / 2, y + 78, "only columns L–R are written", 12, 400, MUTED, "middle")
arrow("M1114,426 V496", "backup + review log", 1124, 466)
# folder
x, y, w, h = 1010, 500, 208, 104
a(f'<path d="M{x},{y+10} h70 l10,-10 h{w-80} v{h} h-{w} z" fill="{DATA_SOFT}" stroke="{DATA}" stroke-width="1.5" stroke-linejoin="round"/>')
text(x + w / 2, y + 40, "~/.flashcards", 15, 700, INK, "middle")
text(x + w / 2, y + 60, "config.json, backups/,", 12, 400, MUTED, "middle")
text(x + w / 2, y + 76, "reviews.csv", 12, 400, MUTED, "middle")

# What crosses the edge
x, y, w, h = 250, 500, 450, 104
a(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="10" fill="#ffffff" stroke="{LINE}" stroke-width="1.5"/>')
text(x + 18, y + 24, "WHAT CROSSES THE DASHED LINE", 11, 800, EDGE, style=' letter-spacing="1"')
rows = [("IN", "Code, when you run git pull."),
        ("OUT", "Nothing. The page loads nothing from the internet,"),
        ("", "and other computers can't reach the server.")]
for i, (label, s) in enumerate(rows):
    yy = y + 48 + i * 17
    if label:
        text(x + 18, yy, label, 11.5, 800, PAGE if label == "IN" else "#c0362c")
    text(x + 66, yy, s, 12, 400, INK)

text(40, 690, "Update: git pull in the clone. Your workbook and ~/.flashcards stay as they are. "
     "Commands: flashcards, flashcards shortcut, flashcards due.", 12.5, 400, MUTED)
a('</svg>')
Path(__file__).with_name("architecture.svg").write_text("\n".join(out) + "\n", encoding="utf-8")
