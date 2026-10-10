#!/usr/bin/env python3
"""
Graphics for the Psie Parki listing in Google Play.

    NODE_PATH=$(npm root -g) node play/map/render.mjs          # the map picture
    ./gradlew testDebugUnitTest -Pscreenshots --tests '*StoreScreenshots*'
    python play/make_store_assets.py

In:   play/screenshots/raw/raw-*/      raw screens rendered by Robolectric (StoreScreenshots.kt)
Out:  play/screenshots/phone/, tablet-7/, tablet-10/   with a caption band
      play/graphics/icon-512.png                       the launcher icon, drawn from the same
                                                       geometry as ic_launcher_foreground.xml
      play/graphics/feature-graphic-1024x500.png

Google Play rules the script checks:
  - exactly 9:16, sides 320–3840 px (phone) and 1080–7680 px (tablets), at most 8 MB
  - the caption takes at most 20% of the height; no device frames, no "download now", "#1", prices
  - PNG without transparency
"""

import math
import re
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

PLAY = Path(__file__).parent
SHOTS = PLAY / "screenshots"
RAW = SHOTS / "raw"
GRAPHICS = PLAY / "graphics"
FOREGROUND = PLAY.parent / "app/src/main/res/drawable/ic_launcher_foreground.xml"

GREEN, GREEN_2 = (46, 107, 63), (24, 70, 38)      # icon background and its darker shade
WHITE, LEAF, BALL = (255, 255, 255), (190, 236, 199), (224, 138, 30)

CAPTIONS = {
    "01-najblizsze": ("Najbliższe wybiegi dla psa", "od najbliższego, w całej Polsce"),
    "02-mapa": ("Wszystkie wybiegi na mapie", "dotknij i prowadź"),
    "03-szczegoly": ("Co jest na wybiegu", "ogrodzenie, światło, woda, agility"),
    "04-filtry": ("Tylko ogrodzone?", "filtruj po tym, co ważne dla Twojego psa"),
    "05-miejscowosci": ("Bez udostępniania lokalizacji", "wystarczy wybrać miejscowość"),
    "04-miejscowosci": ("Bez udostępniania lokalizacji", "wystarczy wybrać miejscowość"),
    "06-ciemny-motyw": ("Jasny i ciemny motyw", "bez reklam i bez zakładania konta"),
}

SETS = {  # raw dir → (output dir, canvas, allowed side range)
    "raw-phone": ("phone", (1080, 1920), (320, 3840)),
    "raw-tablet7": ("tablet-7", (1080, 1920), (1080, 7680)),
    "raw-tablet10": ("tablet-10", (1440, 2560), (1080, 7680)),
}

BAND = 0.1875      # caption band: 18.75% of the height (Google's limit is 20%)
MAX_BYTES = 8 * 1024 * 1024

# First font that exists: Inter on Linux (CI, the cloud container), Segoe UI on Windows.
BOLD = ["/usr/share/fonts/opentype/inter/Inter-Bold.otf", "C:/Windows/Fonts/segoeuib.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"]
REGULAR = ["/usr/share/fonts/opentype/inter/Inter-Regular.otf", "C:/Windows/Fonts/segoeui.ttf",
           "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"]


def font(candidates, size):
    for path in candidates:
        if Path(path).exists():
            return ImageFont.truetype(path, size)
    sys.exit(f"Brak czcionki, sprawdź ścieżki: {candidates}")


def fit(d, text, candidates, size, max_w):
    while size > 20:
        f = font(candidates, size)
        if d.textlength(text, font=f) <= max_w:
            return f
        size -= 2
    return font(candidates, size)


def gradient(w, h, top=GREEN, bottom=GREEN_2):
    mask = Image.linear_gradient("L").resize((w, h))
    return Image.composite(Image.new("RGB", (w, h), bottom), Image.new("RGB", (w, h), top), mask)


# --------------------------------------------------------------------------- #
# the paw, read from the vector drawable so the store icon cannot drift from the app's
# --------------------------------------------------------------------------- #

def bezier(p0, p1, p2, p3, steps=24):
    for i in range(1, steps + 1):
        t = i / steps
        a = (1 - t) ** 3, 3 * (1 - t) ** 2 * t, 3 * (1 - t) * t ** 2, t ** 3
        yield (a[0] * p0[0] + a[1] * p1[0] + a[2] * p2[0] + a[3] * p3[0],
               a[0] * p0[1] + a[1] * p1[1] + a[2] * p2[1] + a[3] * p3[1])


def paw_shapes():
    """Polygons, in the drawable's 108×108 viewport, of every path in the foreground."""
    xml = FOREGROUND.read_text(encoding="utf-8")
    shapes = []
    pad = re.search(r'pathData="(M[\d.,]+ C[^"]+)"', xml).group(1)
    nums = [tuple(map(float, p.split(","))) for p in re.findall(r"[\d.]+,[\d.]+", pad)]
    pts, cur = [nums[0]], nums[0]
    for i in range(1, len(nums) - 2, 3):
        pts += list(bezier(cur, nums[i], nums[i + 1], nums[i + 2]))
        cur = nums[i + 2]
    shapes.append(pts)
    for rot, cx, cy, rx, ry in re.findall(
        r'rotation="(-?[\d.]+)"[^>]*>\s*<path[^>]*pathData="M([\d.]+),([\d.]+) a([\d.]+),([\d.]+)', xml
    ):
        rot, rx, ry = math.radians(float(rot)), float(rx), float(ry)
        cx, cy = float(cx) + rx, float(cy)  # the path starts at the ellipse's left edge
        shapes.append([
            (cx + rx * math.cos(t) * math.cos(rot) - ry * math.sin(t) * math.sin(rot),
             cy + rx * math.cos(t) * math.sin(rot) + ry * math.sin(t) * math.cos(rot))
            for t in (2 * math.pi * k / 96 for k in range(96))
        ])
    return shapes


def draw_paw(size, scale=1.0, colour=WHITE, background=None):
    """The paw on a square of `size` px; `scale` > 1 crops into the 108-unit viewport."""
    ss = 4  # supersample, then shrink: PIL polygons have no antialiasing
    big = size * ss
    im = Image.new("RGBA", (big, big), background or (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    k = big / 108 * scale
    off = (big - 108 * k) / 2
    for shape in paw_shapes():
        d.polygon([(off + x * k, off + y * k) for x, y in shape], fill=colour)
    return im.resize((size, size), Image.LANCZOS)


def icon():
    # Play shows the 512 px icon with its own rounded mask, the way a launcher shows the
    # adaptive one, so: full-bleed background and the paw a little larger than in the
    # 108-unit drawable, about as big as a launcher's crop shows it.
    im = gradient(512, 512)
    paw = draw_paw(512, scale=108 / 86)
    im.paste(paw, (0, 0), paw)
    GRAPHICS.mkdir(exist_ok=True)
    im.save(GRAPHICS / "icon-512.png", optimize=True)
    return im


def feature_graphic():
    W, H = 1024, 500
    im = gradient(W, H)
    d = ImageDraw.Draw(im)
    # A faint big paw on the right, a phone screenshot over it.
    ghost = draw_paw(620, colour=(255, 255, 255, 34))
    im.paste(ghost, (W - 520, -40), ghost)
    paw = draw_paw(150)
    im.paste(paw, (52, 70), paw)
    d.text((64, 230), "Psie Parki", font=font(BOLD, 78), fill=WHITE)
    d.text((66, 330), "Wybiegi dla psów w całej Polsce", font=font(REGULAR, 34), fill=LEAF)
    d.text((66, 382), "najbliższe · na mapie · co na nich jest", font=font(REGULAR, 26), fill=(214, 236, 218))

    shot_path = RAW / "raw-phone" / "01-najblizsze.png"
    if shot_path.exists():
        shot = Image.open(shot_path).convert("RGB")
        h = 560
        w = round(h * shot.width / shot.height)
        shot = shot.resize((w, h), Image.LANCZOS)
        x, y = W - w - 60, 64
        mask = Image.new("L", (w, h), 0)
        ImageDraw.Draw(mask).rounded_rectangle([0, 0, w - 1, h - 1], radius=26, fill=255)
        shadow = Image.new("L", (W, H), 0)
        shadow.paste(mask, (x, y + 10))
        shadow = shadow.filter(ImageFilter.GaussianBlur(18)).point(lambda v: int(v * .5))
        im.paste(Image.new("RGB", (W, H), (0, 20, 8)), (0, 0), shadow)
        im.paste(shot, (x, y), mask)
    im.save(GRAPHICS / "feature-graphic-1024x500.png", optimize=True)
    return im


# --------------------------------------------------------------------------- #
# screenshots
# --------------------------------------------------------------------------- #

def compose(raw: Path, size):
    W, H = size
    s = W / 1080
    im = gradient(W, H)
    d = ImageDraw.Draw(im)
    title, sub = CAPTIONS[raw.stem]
    band = int(H * BAND)

    ft = fit(d, title, BOLD, int(64 * s), W - int(100 * s))
    fs = fit(d, sub, REGULAR, int(40 * s), W - int(100 * s))
    th = ft.getbbox(title)[3]
    sh = fs.getbbox(sub)[3]
    gap = int(18 * s)
    y0 = (band - (th + gap + sh)) // 2 + int(10 * s)
    d.text((W / 2, y0), title, font=ft, fill=WHITE, anchor="mt")
    d.text((W / 2, y0 + th + gap), sub, font=fs, fill=LEAF, anchor="mt")

    shot = Image.open(raw).convert("RGB")
    top, bottom = band + int(24 * s), H - int(44 * s)
    sh_h = bottom - top
    sh_w = round(sh_h * shot.width / shot.height)
    shot = shot.resize((sh_w, sh_h), Image.LANCZOS)
    x = (W - sh_w) // 2
    r = int(34 * s)

    mask = Image.new("L", (sh_w, sh_h), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, sh_w - 1, sh_h - 1], radius=r, fill=255)
    shadow = Image.new("L", (W, H), 0)
    shadow.paste(mask, (x, top + int(16 * s)))
    shadow = shadow.filter(ImageFilter.GaussianBlur(int(28 * s))).point(lambda v: int(v * .55))
    im.paste(Image.new("RGB", (W, H), (0, 24, 8)), (0, 0), shadow)
    im.paste(shot, (x, top), mask)
    return im


def main():
    problems = []
    for src_dir, (dst_dir, size, (lo, hi)) in SETS.items():
        src, dst = RAW / src_dir, SHOTS / dst_dir
        dst.mkdir(parents=True, exist_ok=True)
        raws = sorted(src.glob("*.png"))
        for raw in raws:
            out = dst / raw.name
            compose(raw, size).save(out, optimize=True)
            w, h = Image.open(out).size
            ok = w * 16 == h * 9 and lo <= min(w, h) and max(w, h) <= hi and out.stat().st_size <= MAX_BYTES
            if not ok:
                problems.append(out)
            print(f"  {dst_dir}/{out.name:<24} {w}×{h} {out.stat().st_size // 1024:>5} KB {'OK' if ok else 'BŁĄD'}")
        if len(raws) < (2 if dst_dir == "phone" else 4):
            problems.append(f"{dst_dir}: za mało zrzutów ({len(raws)})")

    ic = icon()
    print(f"  graphics/icon-512.png {ic.size} {ic.mode}")
    fg = feature_graphic()
    print(f"  graphics/feature-graphic-1024x500.png {fg.size} {fg.mode}")
    if problems:
        sys.exit(f"Problemy: {problems}")
    print("Wszystko w limitach Google Play.")


if __name__ == "__main__":
    main()
