#!/usr/bin/env python3
"""Generate Play Store listing graphics from raw device captures.

Produces, in Breadcrumb's icon design language (home-glow gradient, Google Sans):
  tools/feature-graphic.png                 1024x500  feature graphic
  tools/store-screenshots/phone-N.png       1080x1920 phone screenshots (9:16)
  tools/store-screenshots/tablet-N.png      1440x2560 tablet screenshots (9:16)

Raw source captures live in tools/store-screenshots/raw/ (adb screencap output,
any size); each is composited onto a captioned branded frame. Captions are the
CAPTIONS list below — edit there to change wording.

Requires headless chromium for rendering (the SVG/HTML renderer). Run:
  python3 tools/generate_store_assets.py
Note: chromium can't read from some temp dirs; this writes intermediate HTML
next to the outputs (in the repo) and cleans it up.
"""

import base64
import shutil
import subprocess
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
FONT = REPO / "app/src/main/res/font/google_sans.ttf"
RAW = REPO / "tools/store-screenshots/raw"
OUT = REPO / "tools/store-screenshots"

BG_INNER, BG_MID, BG_OUTER = "#26805F", "#124434", "#0B3526"
SUBTITLE = "#A8E6C8"

# (raw filename, title, subtitle) — one per screenshot, in store order.
CAPTIONS = [
    ("1-record.png",  "Recording starts on its own",
     "Walk, ride or drive — Breadcrumb detects it and records."),
    ("2-timeline.png", "Your days as a timeline",
     "Trips and the stays between them, named and in order."),
    ("3-detail.png",  "Every route on a rich map",
     "Coloured by speed, elevation or GPS accuracy."),
    ("4-place.png",   "Name the places you return to",
     "Home, work, the gym — your own map of your life."),
    ("5-places.png",  "All your places, one map",
     "Everywhere you go, kept privately on your device."),
]


def chromium() -> str:
    exe = shutil.which("chromium") or shutil.which("google-chrome")
    if not exe:
        raise SystemExit("chromium/google-chrome not found; cannot render assets")
    return exe


def render(html: str, out: Path, w: int, h: int) -> None:
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


def screenshot_frame(img: Path, title: str, sub: str, out: Path,
                     w: int, h: int, dev_h: int, title_px: int, sub_px: int,
                     pad_top: int, pad_x: int) -> None:
    html = f"""<!doctype html><html><head><meta charset="utf8"><style>
    {font_face()}
    *{{margin:0;box-sizing:border-box}}
    body{{width:{w}px;height:{h}px;overflow:hidden;font-family:GS;
      background:radial-gradient(120% 90% at 50% 8%, {BG_INNER} 0%, {BG_MID} 55%, {BG_OUTER} 100%);}}
    .cap{{padding:{pad_top}px {pad_x}px 0;text-align:center}}
    h1{{color:#fff;font-size:{title_px}px;font-weight:700;line-height:1.12;
      letter-spacing:-.5px;text-wrap:balance}}
    p{{color:{SUBTITLE};font-size:{sub_px}px;font-weight:400;margin-top:22px;
      line-height:1.3;text-wrap:balance}}
    .dev{{display:flex;justify-content:center;margin-top:{int(dev_h*0.05)}px}}
    .dev img{{height:{dev_h}px;border-radius:44px;box-shadow:0 30px 80px rgba(0,0,0,.45);
      border:1px solid rgba(255,255,255,.10)}}
    </style></head><body>
    <div class="cap"><h1>{title}</h1><p>{sub}</p></div>
    <div class="dev"><img src="{data_uri(img)}"></div>
    </body></html>"""
    render(html, out, w, h)


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
  <text x="462" y="288" font-family="GS" font-size="29" fill="{SUBTITLE}">Your trips, recorded automatically.</text>
  <text x="462" y="334" font-family="GS" font-size="29" fill="{SUBTITLE}">All data stays on your device.</text>
</svg>"""
    render(svg, REPO / "tools/feature-graphic.png", 1024, 500)


def main() -> None:
    feature_graphic()
    for i, (name, title, sub) in enumerate(CAPTIONS, 1):
        img = RAW / name
        # Phone: 1080x1920
        screenshot_frame(img, title, sub, OUT / f"phone-{i}.png",
                         1080, 1920, 1400, 66, 34, 96, 90)
        # Tablet: 1440x2560 (same 9:16, larger device + type)
        screenshot_frame(img, title, sub, OUT / f"tablet-{i}.png",
                         1440, 2560, 1880, 84, 42, 130, 120)


if __name__ == "__main__":
    main()
