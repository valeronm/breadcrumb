#!/usr/bin/env python3
"""Generate Breadcrumb's launcher/notification icon resources from parameters.

The mark: a location pin at bottom centre, with nine crumbs looping a full
circle out of the pin and back into it, shrinking and fading with distance
("the round trip"). Pin sides are G2-continuous cubics (curvature at the
equator join equals the head circle's), the pin hole is punched transparent,
and a ground-coloured keyline gap separates the pin from the crumbs.

Outputs (paths relative to the repo root):
  app/src/main/res/drawable/ic_launcher_background.xml  - radial "home glow"
  app/src/main/res/drawable/ic_launcher_foreground.xml  - crumbs + gap + pin
  app/src/main/res/drawable/ic_notification.xml         - 24dp tinted silhouette
  tools/play-icon.svg                                    - 512px Play listing source

Run from anywhere:  python3 tools/generate_icon.py
Then rebuild; the adaptive icon XML in mipmap-anydpi-v26 references the
generated drawables (and reuses the foreground as the monochrome layer).
"""

import math
from pathlib import Path

# ---------------------------------------------------------------- parameters

CANVAS = 108.0          # adaptive-icon canvas (66dp safe zone circle at r=33)

# Crumb loop
RING_CX, RING_CY = 54.0, 54.0   # loop centre (canvas-centred)
RING_R = 24.0                   # loop radius
CRUMBS = 9                      # number of crumbs
ANGLE_START, ANGLE_END = 118.0, 418.0  # degrees, y-down; pin occupies the gap
DOT_R_MAX, DOT_R_MIN = 5.2, 2.4        # crumb radius taper (perspective)
ALPHA_MAX, ALPHA_MIN = 1.0, 0.28       # crumb opacity taper (depth)

# Pin (G2 teardrop)
PIN_CX = 54.0
PIN_HY = 58.0           # head centre y
PIN_R = 12.5            # head radius
PIN_TIP_Y = 83.0        # sharp tip y (below the lowest crumb pixel)
PIN_TIP_ANGLE = 20.0    # tip half-angle, degrees
PIN_CTRL = 0.48         # tip control length as a fraction of tip distance
HOLE_R_FRAC = 0.4       # hole radius as a fraction of head radius

# Keyline gap between pin and crumbs (Z2: "medium")
GAP_R = 2.5             # extra head radius
GAP_TIP = 3.5           # extra tip length

# Notification (24dp status-bar silhouette): unlike the launcher, the mark
# should fill the viewport, so its bounding box is scaled up to fit.
NOTIF_CENTER = (54.0, 56.0)   # mark's visual centre on the 108 canvas
NOTIF_HALF = 31.0             # mark's half-extent on the 108 canvas
NOTIF_MARGIN = 1.0            # dp of padding inside the 24dp viewport

# Colours
BG_INNER, BG_OUTER = "#26805F", "#0B3526"  # home-glow radial gradient
BG_CX, BG_CY, BG_RADIUS = 54.0, 78.0, 108.0  # glow centred on the pin
CRUMB_COLOR = "#A8E6C8"
PIN_COLOR = "#FFFFFF"

# Debug launcher background: a blueprint grid, so the debug install is obvious in
# the launcher. Overrides ic_launcher_background.xml in the debug source set only;
# the foreground/monochrome stay shared with release.
DBG_INNER, DBG_OUTER = "#12324A", "#0A1622"  # diagonal blueprint gradient
DBG_GRID = "#2E5F86"
DBG_GRID_STEP = 12  # units between grid lines on the 108 canvas

REPO = Path(__file__).resolve().parent.parent

# ---------------------------------------------------------------- geometry


def fmt(v: float) -> str:
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s == "-0" else s


def crumbs(scale: float = 1.0, dx: float = 0.0, dy: float = 0.0):
    """Yield (path, alpha) per crumb, transformed by scale/offset."""
    for i in range(CRUMBS):
        t = i / (CRUMBS - 1)
        a = math.radians(ANGLE_START + (ANGLE_END - ANGLE_START) * t)
        x = (RING_CX + RING_R * math.cos(a)) * scale + dx
        y = (RING_CY + RING_R * math.sin(a)) * scale + dy
        r = (DOT_R_MAX + (DOT_R_MIN - DOT_R_MAX) * t) * scale
        alpha = ALPHA_MAX + (ALPHA_MIN - ALPHA_MAX) * t
        path = (f"M{fmt(x - r)},{fmt(y)} a{fmt(r)},{fmt(r)} 0 1 0 {fmt(2 * r)},0 "
                f"a{fmt(r)},{fmt(r)} 0 1 0 {fmt(-2 * r)},0 Z")
        yield path, alpha


def pin_path(cx: float, hy: float, r: float, ty: float, hole: bool,
             scale: float = 1.0, dx: float = 0.0, dy: float = 0.0) -> str:
    """G2 teardrop: circular head, cubic sides curvature-matched at the equator."""
    cx, hy, r, ty = cx * scale + dx, hy * scale + dy, r * scale, ty * scale + dy
    d = ty - hy
    alpha = math.radians(PIN_TIP_ANGLE)
    ctrl = PIN_CTRL * d
    b1x = math.sin(alpha) * ctrl
    b1y = d - math.cos(alpha) * ctrl
    m = math.sqrt((2 / 3) * r * (r - b1x))  # curvature match: k(join) = 1/r
    p = (f"M{fmt(cx)},{fmt(hy + d)} "
         f"C{fmt(cx - b1x)},{fmt(hy + b1y)} {fmt(cx - r)},{fmt(hy + m)} {fmt(cx - r)},{fmt(hy)} "
         f"A{fmt(r)},{fmt(r)} 0 1 1 {fmt(cx + r)},{fmt(hy)} "
         f"C{fmt(cx + r)},{fmt(hy + m)} {fmt(cx + b1x)},{fmt(hy + b1y)} {fmt(cx)},{fmt(hy + d)} Z")
    if hole:
        hr = r * HOLE_R_FRAC
        p += (f" M{fmt(cx - hr)},{fmt(hy)} a{fmt(hr)},{fmt(hr)} 0 1 0 {fmt(2 * hr)},0 "
              f"a{fmt(hr)},{fmt(hr)} 0 1 0 {fmt(-2 * hr)},0 Z")
    return p


def gap_clip(scale: float = 1.0, dx: float = 0.0, dy: float = 0.0) -> str:
    """Inverse clip (even-odd): full canvas minus the enlarged 'gap' pin.

    Crumbs drawn inside this clip get a transparent keyline gap around the pin.
    """
    c = CANVAS * scale
    rect = f"M{fmt(dx)},{fmt(dy)} h{fmt(c)} v{fmt(c)} h{fmt(-c)} Z"
    gap = pin_path(PIN_CX, PIN_HY, PIN_R + GAP_R, PIN_TIP_Y + GAP_TIP, False,
                   scale, dx, dy)
    return f"{rect} {gap}"


# ---------------------------------------------------------------- writers

HEADER = "<!-- Generated by tools/generate_icon.py - edit parameters there, not here. -->\n"


def write(path: Path, content: str) -> None:
    path.write_text(content)
    print(f"wrote {path.relative_to(REPO)}")


def launcher_background() -> str:
    return f"""{HEADER}<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="radial"
                android:centerX="{fmt(BG_CX)}"
                android:centerY="{fmt(BG_CY)}"
                android:gradientRadius="{fmt(BG_RADIUS)}"
                android:startColor="{BG_INNER}"
                android:endColor="{BG_OUTER}" />
        </aapt:attr>
    </path>
</vector>
"""


def debug_launcher_background() -> str:
    grid = " ".join(
        f"M{i},0 V108 M0,{i} H108" for i in range(0, 109, DBG_GRID_STEP))
    return f"""{HEADER}<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="0" android:startY="0"
                android:endX="108" android:endY="108"
                android:startColor="{DBG_INNER}"
                android:endColor="{DBG_OUTER}" />
        </aapt:attr>
    </path>
    <path
        android:strokeColor="{DBG_GRID}"
        android:strokeWidth="0.6"
        android:strokeAlpha="0.6"
        android:pathData="{grid}" />
</vector>
"""


def launcher_foreground() -> str:
    dots = "\n".join(
        f'        <path\n'
        f'            android:fillColor="{CRUMB_COLOR}"\n'
        f'            android:fillAlpha="{alpha:.2f}"\n'
        f'            android:pathData="{path}" />'
        for path, alpha in crumbs())
    return f"""{HEADER}<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <!-- Crumb loop, clipped so a transparent keyline gap surrounds the pin -->
    <group>
        <clip-path
            android:fillType="evenOdd"
            android:pathData="{gap_clip()}" />
{dots}
    </group>
    <!-- Pin: G2 teardrop, hole punched transparent -->
    <path
        android:fillColor="{PIN_COLOR}"
        android:fillType="evenOdd"
        android:pathData="{pin_path(PIN_CX, PIN_HY, PIN_R, PIN_TIP_Y, hole=True)}" />
</vector>
"""


def notification_icon() -> str:
    # Same mark scaled into a 24dp viewport; the status bar tints it to a
    # flat silhouette, alpha taper survives tinting. The mark (not the padded
    # canvas) is fitted to the viewport so the glyph reads at status-bar size.
    s = (12.0 - NOTIF_MARGIN) / NOTIF_HALF
    dx = 12.0 - NOTIF_CENTER[0] * s
    dy = 12.0 - NOTIF_CENTER[1] * s
    dots = "\n".join(
        f'        <path\n'
        f'            android:fillColor="#FFFFFF"\n'
        f'            android:fillAlpha="{alpha:.2f}"\n'
        f'            android:pathData="{path}" />'
        for path, alpha in crumbs(scale=s, dx=dx, dy=dy))
    return f"""{HEADER}<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="#FFFFFF">
    <group>
        <clip-path
            android:fillType="evenOdd"
            android:pathData="{gap_clip(scale=s, dx=dx, dy=dy)}" />
{dots}
    </group>
    <path
        android:fillColor="#FFFFFF"
        android:fillType="evenOdd"
        android:pathData="{pin_path(PIN_CX, PIN_HY, PIN_R, PIN_TIP_Y, hole=True, scale=s, dx=dx, dy=dy)}" />
</vector>
"""


def play_icon_svg() -> str:
    # 512px Play listing icon: full-bleed square, Play rounds the corners.
    s = 512.0 / CANVAS
    dots = "\n".join(
        f'    <path fill="{CRUMB_COLOR}" fill-opacity="{alpha:.2f}" d="{path}"/>'
        for path, alpha in crumbs(scale=s))
    return f"""<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 512 512">
  <!-- Generated by tools/generate_icon.py -->
  <defs>
    <radialGradient id="glow" gradientUnits="userSpaceOnUse"
        cx="{fmt(BG_CX * s)}" cy="{fmt(BG_CY * s)}" r="{fmt(BG_RADIUS * s)}">
      <stop offset="0" stop-color="{BG_INNER}"/>
      <stop offset="1" stop-color="{BG_OUTER}"/>
    </radialGradient>
  </defs>
  <rect width="512" height="512" fill="url(#glow)"/>
{dots}
  <!-- keyline gap: enlarged pin filled with the same user-space gradient -->
  <path fill="url(#glow)" d="{pin_path(PIN_CX, PIN_HY, PIN_R + GAP_R, PIN_TIP_Y + GAP_TIP, False, scale=s)}"/>
  <path fill="{PIN_COLOR}" fill-rule="evenodd" d="{pin_path(PIN_CX, PIN_HY, PIN_R, PIN_TIP_Y, hole=True, scale=s)}"/>
</svg>
"""


def rasterize_play_icon() -> None:
    """Render the 512px Play-listing PNG from the SVG, if a renderer exists."""
    import shutil
    import subprocess
    svg = REPO / "tools/play-icon.svg"
    png = REPO / "tools/play-icon.png"
    chromium = shutil.which("chromium") or shutil.which("google-chrome")
    if not chromium:
        print("no chromium found - render tools/play-icon.svg to a 512px PNG manually")
        return
    subprocess.run(
        [chromium, "--headless", "--disable-gpu", f"--screenshot={png}",
         "--window-size=512,512", f"file://{svg}"],
        check=True, capture_output=True)
    print(f"wrote {png.relative_to(REPO)}")


def main() -> None:
    res = REPO / "app/src/main/res"
    write(res / "drawable/ic_launcher_background.xml", launcher_background())
    write(res / "drawable/ic_launcher_foreground.xml", launcher_foreground())
    write(res / "drawable/ic_notification.xml", notification_icon())
    # Debug-only background override (blueprint grid); foreground/monochrome shared.
    dbg = REPO / "app/src/debug/res/drawable"
    dbg.mkdir(parents=True, exist_ok=True)
    write(dbg / "ic_launcher_background.xml", debug_launcher_background())
    write(REPO / "tools/play-icon.svg", play_icon_svg())
    rasterize_play_icon()


if __name__ == "__main__":
    main()
