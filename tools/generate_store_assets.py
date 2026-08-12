#!/usr/bin/env python3
"""Generate Play Store listing graphics from raw device captures.

Produces, in Breadcrumb's icon design language (home-glow gradient, Google Sans):
  docs/store-listing/feature-graphic.png        1024x500  feature graphic
  docs/store-listing/screenshots/shot-N.png     1080x1920 screenshots (9:16), every slot

Output lands beside the listing text because that folder is what gets uploaded to the
Play Console.

Source captures are tools/demo-data/screenshots/ — the full-resolution raws
shoot_screenshots.py writes (the README's docs/screenshots/ are the same shots compressed,
too small and too dithered to composite from). One shoot feeds both, but the raws are
gitignored, so regenerating store assets starts with a shoot. Each capture is composited
onto a captioned branded frame. CAPTIONS below picks which of them ship and in what
order, so the folder may hold shots the store does not use.

Requires headless chromium for rendering (the SVG/HTML renderer). Run:
  python3 tools/generate_store_assets.py
Note: chromium can't read from some temp dirs; this writes intermediate HTML
next to the outputs (in the repo) and cleans it up.
"""

import base64
import shutil
import subprocess
from pathlib import Path
from typing import NamedTuple

from shoot_screenshots import RAW

REPO = Path(__file__).resolve().parent.parent
FONT = REPO / "app/src/main/res/font/google_sans.ttf"
LISTING = REPO / "docs/store-listing"
OUT = LISTING / "screenshots"

BG_INNER, BG_MID, BG_OUTER = "#26805F", "#124434", "#0B3526"
SUBTITLE = "#A8E6C8"


class Frame(NamedTuple):
    """The composited frame's geometry. `dev_top` is why the device does not move between shots.

    A store listing is swiped through, so a phone that sits lower wherever a title happens to wrap
    reads as a rendering fault. Positioning the device at a fixed offset rather than after the
    caption keeps it still by construction — nothing here predicts how the text will lay out.
    """

    w: int
    h: int
    dev_top: int
    dev_h: int
    title_px: int
    sub_px: int
    pad_top: int
    pad_x: int


# One set fills the phone, 7-inch and 10-inch tablet slots alike: all three take 9:16, and the
# only floor that bites is the 10-inch one, which requires 1080 on the short side. The width is
# therefore exactly at that floor and must not be lowered — a tablet-sized second set would carry
# the same capture and the same caption, so it would buy resolution and nothing else.
FRAME = Frame(1080, 1920, 381, 1400, 66, 34, 96, 90)



# (capture filename in RAW, title, subtitle) — one per screenshot, in store order.
CAPTIONS = [
    ("record.png", "Recording starts on its own",
     "Walk, ride or drive — Breadcrumb detects it and records."),
    ("timeline.png", "Your days as a timeline",
     "Trips and the stays between them, named and in order."),
    ("track-detail.png", "Every trip on a rich map",
     "Coloured by speed, elevation, accuracy or satellites."),
    ("places.png", "Name the places you revisit",
     "Home, work, the gym — your own map of your life."),
    ("journeys.png", "Journeys away from home",
     "Nights spent away, named after where the time went."),
    ("statistics.png", "A month against the year",
     "Where your movement and your hours actually go."),
]


def chromium() -> str:
    exe = shutil.which("chromium") or shutil.which("google-chrome")
    if not exe:
        raise SystemExit("chromium/google-chrome not found; cannot render assets")
    return exe


def render(body: str, out: Path, w: int, h: int, style: str = "") -> None:
    """Screenshot `body` as a w×h PNG. Callers pass markup and their own CSS, never a whole page.

    The skeleton lives here because chromium loads the temp file as HTML whatever it holds: a
    caller handing over bare markup would otherwise pick up the default body margin, which insets
    the art and pushes it past the viewport — scrollbars and an offset, baked into the PNG.
    """
    html = f"""<!doctype html><html><head><meta charset="utf8"><style>
    *{{margin:0;padding:0;box-sizing:border-box}}
    html,body{{width:{w}px;height:{h}px;overflow:hidden}}
    {style}
    </style></head><body>{body}</body></html>"""
    out.parent.mkdir(parents=True, exist_ok=True)
    tmp = out.with_suffix(".html")
    tmp.write_text(html)
    subprocess.run(
        [chromium(), "--headless", "--disable-gpu", "--force-device-scale-factor=1",
         f"--screenshot={out}", f"--window-size={w},{h}", f"file://{tmp}"],
        check=True, capture_output=True)
    tmp.unlink()
    print(f"wrote {out.relative_to(REPO)}")


def font_face() -> str:
    b64 = base64.b64encode(FONT.read_bytes()).decode()
    return f"@font-face{{font-family:GS;src:url(data:font/ttf;base64,{b64});}}"


def data_uri(img: Path) -> str:
    return "data:image/png;base64," + base64.b64encode(img.read_bytes()).decode()


def screenshot_frame(img: Path, title: str, sub: str, out: Path, f: Frame) -> None:
    style = f"""
    {font_face()}
    body{{font-family:GS;position:relative;
      background:radial-gradient(120% 90% at 50% 8%, {BG_INNER} 0%, {BG_MID} 55%, {BG_OUTER} 100%);}}
    .cap{{padding:{f.pad_top}px {f.pad_x}px 0;text-align:center}}
    h1{{color:#fff;font-size:{f.title_px}px;font-weight:700;line-height:1.12;
      letter-spacing:-.5px;text-wrap:balance}}
    p{{color:{SUBTITLE};font-size:{f.sub_px}px;font-weight:400;margin-top:22px;
      line-height:1.3;text-wrap:balance}}
    .dev{{position:absolute;top:{f.dev_top}px;left:0;right:0;
      display:flex;justify-content:center}}
    .dev img{{height:{f.dev_h}px;border-radius:44px;box-shadow:0 30px 80px rgba(0,0,0,.45);
      border:1px solid rgba(255,255,255,.10)}}"""
    body = f"""
    <div class="cap"><h1>{title}</h1><p>{sub}</p></div>
    <div class="dev"><img src="{data_uri(img)}"></div>"""
    render(body, out, f.w, f.h, style)


def feature_graphic() -> None:
    # Reuses the launcher mark; imported lazily so this file stands alone.
    import sys
    sys.path.insert(0, str(REPO / "tools"))
    from generate_icon import (pin_path, crumbs, PIN_CX, PIN_HY, PIN_R,
                               PIN_TIP_Y, GAP_R, GAP_TIP, CRUMB_COLOR, PIN_COLOR)
    s = 500 / 108 * 0.72
    dx, dy = 60, 250 - 56 * s
    dots = "\n".join(
        f'<path fill="{CRUMB_COLOR}" fill-opacity="{a:.2f}" d="{p}"/>'
        for p, a in crumbs(scale=s, dx=dx, dy=dy))
    svg = f"""<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="500" viewBox="0 0 1024 500">
  <defs><style>{font_face()}</style>
    <radialGradient id="g" gradientUnits="userSpaceOnUse" cx="{dx+54*s:.0f}" cy="{dy+78*s:.0f}" r="900">
      <stop offset="0" stop-color="{BG_INNER}"/><stop offset="1" stop-color="{BG_OUTER}"/>
    </radialGradient></defs>
  <rect width="1024" height="500" fill="url(#g)"/>
  {dots}
  <path fill="url(#g)" d="{pin_path(PIN_CX,PIN_HY,PIN_R+GAP_R,PIN_TIP_Y+GAP_TIP,False,s,dx,dy)}"/>
  <path fill="{PIN_COLOR}" fill-rule="evenodd" d="{pin_path(PIN_CX,PIN_HY,PIN_R,PIN_TIP_Y,True,s,dx,dy)}"/>
  <text x="460" y="222" font-family="GS" font-size="72" font-weight="700" fill="#FFFFFF">Breadcrumb</text>
  <text x="462" y="288" font-family="GS" font-size="29" fill="{SUBTITLE}">Everywhere you go, recorded by itself.</text>
  <text x="462" y="334" font-family="GS" font-size="29" fill="{SUBTITLE}">All data stays on your device.</text>
</svg>"""
    render(svg, LISTING / "feature-graphic.png", 1024, 500)


def main() -> None:
    feature_graphic()
    for i, (name, title, sub) in enumerate(CAPTIONS, 1):
        img = RAW / name
        screenshot_frame(img, title, sub, OUT / f"shot-{i}.png", FRAME)


if __name__ == "__main__":
    main()
