#!/usr/bin/env python3
"""Generate the CameraGate launcher/splash/notification icon set.

Design: white camera glyph with cyan WiFi arcs on a dark navy rounded
square ("camera + wifi waves").

Renders PNGs into the Android res tree:
  mipmap-*   ic_launcher.png / ic_launcher_round.png / ic_launcher_foreground.png
  mipmap-*   ic_splash.png
  drawable-nodpi/  ic_notification.png (24dp alpha mask)
"""

import math
import os
import sys

from PIL import Image, ImageDraw

BG_TOP = (0, 0, 0, 255)      # #000000
BG_BOTTOM = (0, 0, 0, 255)   # #000000
SURFACE = (0, 0, 0, 255)     # #000000 (lens punch-out)
WHITE = (255, 255, 255, 255) # #FFFFFF
ACCENT = (255, 255, 255, 255) # #FFFFFF (monochrome-safe mark)

RES = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
LAUNCHER_BASE = 48
FOREGROUND = 108  # adaptive-icon canvas in dp (108x108)
SPLASH_DP = 260  # glyph target ~1/3 of a typical portrait short side
NOTIF_BASE = 24


def lerp(a, b, t):
    return a + (b - a) * t


def ri(v):
    """PIL's drawing primitives silently break on float coordinates."""
    return int(round(v))


def background(img, size, round_frac):
    """Vertical gradient with rounded corners (round_frac of size)."""
    d = ImageDraw.Draw(img)
    r = ri(round_frac * size)
    mask = Image.new("L", (size, size), 0)
    md = ImageDraw.Draw(mask)
    md.rounded_rectangle([0, 0, size - 1, size - 1], radius=r, fill=255)
    for y in range(size):
        t = y / (size - 1)
        color = tuple(int(lerp(a, b, t)) for a, b in zip(BG_TOP, BG_BOTTOM))
        d.line([(0, y), (size, y)], fill=color)
    img.putalpha(mask)
    return img


def wifi(gd, cx, cy, r_outer, stroke, color, span=120.0):
    """Three concentric arcs + dot.

    PIL measures angles clockwise from 3 o'clock, so an upward-facing
    band is centered on 270 degrees.
    """
    radii = [r_outer, r_outer * 0.68, r_outer * 0.36]
    spans = [span, span - 10, span - 30]
    for r, sp in zip(radii, spans):
        a0 = 270 - sp / 2
        a1 = 270 + sp / 2
        gd.arc([ri(cx - r), ri(cy - r), ri(cx + r), ri(cy + r)],
               start=ri(a0), end=ri(a1), fill=color,
               width=max(1, ri(stroke)))
    gd.ellipse([ri(cx - stroke), ri(cy - r_outer - stroke),
                ri(cx + stroke), ri(cy - r_outer + stroke)], fill=color)


def camera(gd, cx, cy, size, unit, corner, stroke=None):
    """White rounded camera body + lens (punch-out + glass)."""
    w = 0.56 * unit
    h = 0.36 * unit
    x0, y0 = cx - w / 2, cy - h / 2
    if stroke is None:
        gd.rounded_rectangle([ri(x0), ri(y0), ri(x0 + w), ri(y0 + h)],
                             radius=ri(corner), fill=WHITE)
    else:
        for i in range(max(1, ri(stroke))):
            o = stroke / 2 - i
            gd.rounded_rectangle([ri(x0 - o), ri(y0 - o),
                                  ri(x0 + w + o), ri(y0 + h + o)],
                                 radius=ri(corner + o), outline=WHITE)
    lens_r = 0.125 * unit
    gd.ellipse([ri(cx - lens_r), ri(cy - lens_r),
                ri(cx + lens_r), ri(cy + lens_r)], fill=SURFACE)
    glass_r = 0.055 * unit
    gd.ellipse([ri(cx - glass_r), ri(cy - glass_r),
                ri(cx + glass_r), ri(cy + glass_r)], fill=WHITE)


def glyph(canvas, unit, cx, cy, wifi_color=ACCENT, round_bg=True):
    """Full glyph composition; unit = glyph box size, centered on cx, cy."""
    d = ImageDraw.Draw(canvas)
    wifi(d, cx, cy - 0.31 * unit, 0.155 * unit, 0.030 * unit, wifi_color)
    camera(d, cx, cy + 0.12 * unit, unit, unit, 0.07 * unit)


def render_legacy(size, round_bg=True):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    background(img, size, 0.20 if round_bg else 0.50)
    d = ImageDraw.Draw(img)
    unit = 0.92 * size
    wifi(d, size / 2, size / 2 - 0.31 * unit, 0.155 * unit,
         0.030 * unit, ACCENT)
    camera(d, size / 2, size / 2 + 0.12 * unit, unit, unit, 0.07 * unit)
    return img


def render_foreground(size):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    unit = 0.72 * size  # adaptive-icon safe zone (72 of 108 dp)
    glyph(img, unit, size / 2, size / 2)
    return img


def render_splash(size):
    """Splash mark: white camera+wifi glyph on a TRANSPARENT canvas so the
    pure-black window background shows through - a clean centered logo with
    no box, no clipping, no stretch, on every Android version."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    unit = 0.60 * size
    # the glyph is top-heavy (wifi arcs reach far above the camera), so
    # shift the composition down to balance it visually around the center
    cy = size / 2 + 0.25 * unit
    wifi(d, size / 2, cy - 0.31 * unit, 0.155 * unit,
         0.030 * unit, WHITE)
    camera(d, size / 2, cy + 0.12 * unit, unit, unit, 0.07 * unit)
    return img


def render_notification(size):
    """White silhouette (system tints it) - camera body with transparent lens
    hole and wifi arcs above. Android renders small icons as alpha masks."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    wifi(d, size / 2, 0.40 * size, 0.28 * size, 0.09 * size, WHITE, span=130)
    w = 0.62 * size
    h = 0.34 * size
    x0, y0 = 0.19 * size, 0.62 * size
    d.rounded_rectangle([ri(x0), ri(y0), ri(x0 + w), ri(y0 + h)],
                        radius=ri(0.08 * size), fill=WHITE)
    lens = 0.13 * size
    lcy = y0 + h / 2
    d.ellipse([ri(size / 2 - lens), ri(lcy - lens),
               ri(size / 2 + lens), ri(lcy + lens)], fill=(0, 0, 0, 0))
    return img


def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)
    print("wrote", os.path.relpath(path, os.path.join(RES, "..", "..")))


def main():
    for name, scale in DENSITIES.items():
        base = os.path.join(RES, "mipmap-" + name)
        base_px = int(LAUNCHER_BASE * scale)
        save(render_legacy(base_px), os.path.join(base, "ic_launcher.png"))
        save(render_legacy(base_px, round_bg=False),
             os.path.join(base, "ic_launcher_round.png"))
        fg_px = int(FOREGROUND * scale)
        save(render_foreground(fg_px),
             os.path.join(base, "ic_launcher_foreground.png"))
        splash_px = int(SPLASH_DP * scale)
        save(render_splash(splash_px), os.path.join(base, "ic_splash.png"))
    # notification small icon: 24dp alpha mask, density-independent
    save(render_notification(NOTIF_BASE),
         os.path.join(RES, "drawable-nodpi", "ic_notification.png"))
    print("done")


if __name__ == "__main__":
    try:
        import PIL  # noqa: F401
    except ImportError:
        print("Pillow is required: pip3 install --user pillow", file=sys.stderr)
        sys.exit(1)
    main()