"""Generate the Flashcards logo: flashcards/web/static/logo.svg and flashcards.ico.

The logo is two white cards, one tilted behind the other, on a teal rounded
square; the front card carries two lines (a word and its meaning). logo.svg
is the browser-tab icon and the logo in the page header; flashcards.ico is
the icon of the desktop shortcut made by `flashcards shortcut`.

Both are drawn from the one set of shapes below (on a 256 x 256 grid), so
they can't drift apart. The .ico is drawn with Pillow at 4x size and scaled
down, which gives smooth edges at every size Windows asks for (16 px in
lists up to 256 px on the desktop). Same approach as telegram_qa/docs/make_icon.py.

Only needed after changing the logo; the generated files are committed, so
installing the app doesn't need Pillow:

    python -m pip install pillow
    python docs/make_icon.py
"""

from pathlib import Path

from PIL import Image, ImageDraw

STATIC = Path(__file__).resolve().parents[1] / "flashcards" / "web" / "static"

TOP, BOTTOM = (20, 184, 166), (15, 118, 110)   # background gradient, top to bottom (style.css --primary)
INK = (15, 118, 110)                            # lines on the front card
RADIUS = 56                                     # corner radius of the square
CARD = (52, 76, 204, 188, 14)                   # front card: x0, y0, x1, y1, corner radius
BACK_ANGLE = -14                                # back card: the front card rotated about the centre
BACK_ALPHA = 0.55
# Two lines on the front card: the word (thick, short) and its meaning (thin, long).
LINES = [((80, 116), (150, 116), 16), ((80, 152), (176, 152), 10)]
ICO_SIZES = [16, 20, 24, 32, 40, 48, 64, 128, 256]


def hexc(rgb):
    return "#%02x%02x%02x" % rgb


def svg() -> str:
    x0, y0, x1, y1, r = CARD
    lines = "".join(
        f'<line x1="{a[0]}" y1="{a[1]}" x2="{b[0]}" y2="{b[1]}" stroke="{hexc(INK)}" stroke-width="{w}" stroke-linecap="round"/>'
        for a, b, w in LINES
    )
    return f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256" role="img" aria-label="Flashcards">
<defs><linearGradient id="g" x1="0" y1="0" x2="0" y2="1">
<stop offset="0" stop-color="{hexc(TOP)}"/><stop offset="1" stop-color="{hexc(BOTTOM)}"/>
</linearGradient></defs>
<rect width="256" height="256" rx="{RADIUS}" fill="url(#g)"/>
<rect x="{x0}" y="{y0}" width="{x1 - x0}" height="{y1 - y0}" rx="{r}" fill="#fff" fill-opacity="{BACK_ALPHA}" transform="rotate({BACK_ANGLE} 128 128) translate(10 -6)"/>
<rect x="{x0}" y="{y0}" width="{x1 - x0}" height="{y1 - y0}" rx="{r}" fill="#fff"/>
{lines}
</svg>
"""


def raster(scale: int = 4) -> Image.Image:
    n = 256 * scale
    s = lambda v: v * scale  # noqa: E731

    gradient = Image.new("RGB", (n, n))
    gd = ImageDraw.Draw(gradient)
    for y in range(n):
        t = y / (n - 1)
        gd.line([(0, y), (n, y)], fill=tuple(round(a + (b - a) * t) for a, b in zip(TOP, BOTTOM)))
    mask = Image.new("L", (n, n), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, n - 1, n - 1], radius=s(RADIUS), fill=255)
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    img.paste(gradient, (0, 0), mask)

    x0, y0, x1, y1, r = CARD
    # Back card: drawn on its own layer, shifted like the SVG's translate, then
    # rotated about the centre. PIL rotates counter-clockwise for positive
    # angles, SVG clockwise, hence the minus sign.
    back = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    ImageDraw.Draw(back).rounded_rectangle(
        [s(x0 + 10), s(y0 - 6), s(x1 + 10), s(y1 - 6)], radius=s(r),
        fill=(255, 255, 255, round(255 * BACK_ALPHA)))
    back = back.rotate(-BACK_ANGLE, resample=Image.BICUBIC, center=(n / 2, n / 2))
    img = Image.alpha_composite(img, back)

    d = ImageDraw.Draw(img)
    d.rounded_rectangle([s(x0), s(y0), s(x1), s(y1)], radius=s(r), fill="white")
    for a, b, w in LINES:
        d.line([(s(a[0]), s(a[1])), (s(b[0]), s(b[1]))], fill=INK, width=s(w))
        # Round caps (stroke-linecap in the SVG).
        for px, py in (a, b):
            d.ellipse([s(px) - s(w) / 2, s(py) - s(w) / 2, s(px) + s(w) / 2, s(py) + s(w) / 2], fill=INK)
    return img


def main() -> None:
    STATIC.mkdir(parents=True, exist_ok=True)
    (STATIC / "logo.svg").write_text(svg(), encoding="utf-8")
    big = raster().resize((256, 256), Image.LANCZOS)
    big.save(STATIC / "flashcards.ico", format="ICO", sizes=[(n, n) for n in ICO_SIZES])
    print(f"wrote {STATIC / 'logo.svg'} and {STATIC / 'flashcards.ico'}")


if __name__ == "__main__":
    main()
