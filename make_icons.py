#!/usr/bin/env python3
"""Generate launcher icons (legacy + adaptive foreground) in all densities."""
import glob
import os

import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter

RES = "/workspace/huandou-app/res"
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
FG_SIZES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}


def find_font():
    patterns = [
        "/usr/share/fonts/opentype/noto/*Sans*CJK*Bold*.ttc",
        "/usr/share/fonts/opentype/noto/*Sans*CJK*Black*.ttc",
        "/usr/share/fonts/opentype/noto/*Sans*CJK*Medium*.ttc",
        "/usr/share/fonts/opentype/noto/*Sans*CJK*.ttc",
        "/usr/share/fonts/**/*CJK*Bold*.ttc",
        "/usr/share/fonts/**/*CJK*.ttc",
        "/usr/share/fonts/**/*.ttf",
        "/usr/share/fonts/**/*.otf",
    ]
    for p in patterns:
        found = sorted(glob.glob(p, recursive=True))
        if found:
            return found[0]
    raise RuntimeError("No CJK font found")


FONT_PATH = find_font()


def get_font(px):
    for idx in (2, 0, 1, 3, 4):
        try:
            return ImageFont.truetype(FONT_PATH, px, index=idx)
        except Exception:
            continue
    return ImageFont.load_default()


def draw_glyph(img, ratio, color=(255, 255, 255, 255)):
    S = img.size[0]
    draw = ImageDraw.Draw(img)
    f = get_font(int(S * ratio))
    bbox = draw.textbbox((0, 0), "\u5e7b", font=f)
    w, h = bbox[2] - bbox[0], bbox[3] - bbox[1]
    x = (S - w) / 2.0 - bbox[0]
    y = (S - h) / 2.0 - bbox[1] - S * 0.012
    draw.text((x, y), "\u5e7b", font=f, fill=color)


def make_legacy(size):
    ss = 4
    S = size * ss
    yy, xx = np.mgrid[0:S, 0:S]
    t = ((xx + yy) / (2.0 * S)).astype(np.float32)
    c0 = np.array([42, 19, 64], dtype=np.float32)
    c1 = np.array([10, 7, 13], dtype=np.float32)
    col = c0 + (c1 - c0) * t[:, :, None]
    r = np.sqrt((xx - S * 0.5) ** 2 + (yy - S * 0.42) ** 2) / (S * 0.72)
    glow = np.clip(1.0 - r, 0.0, 1.0) ** 2 * 0.62
    col = col + np.array([120, 66, 205], dtype=np.float32) * glow[:, :, None]
    arr = np.clip(col, 0, 255).astype(np.uint8)
    bg = Image.fromarray(
        np.dstack([arr, np.full((S, S), 255, dtype=np.uint8)]), "RGBA"
    )

    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, S - 1, S - 1], radius=int(S * 0.22), fill=255
    )
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    img.paste(bg, (0, 0), mask)

    draw_glyph(img, 0.52)

    d = ImageDraw.Draw(img)
    lw, lh = S * 0.20, max(2, int(S * 0.016))
    lx, ly = (S - lw) / 2.0, S * 0.755
    d.rounded_rectangle([lx, ly, lx + lw, ly + lh], radius=lh / 2.0,
                        fill=(155, 123, 255, 235))

    return img.resize((size, size), Image.LANCZOS)


def make_foreground(size):
    ss = 3
    S = size * ss
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))

    glow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse(
        [S * 0.22, S * 0.22, S * 0.78, S * 0.78], fill=(155, 123, 255, 70)
    )
    img = Image.alpha_composite(img, glow.filter(ImageFilter.GaussianBlur(S * 0.075)))

    draw_glyph(img, 0.40)
    return img.resize((size, size), Image.LANCZOS)


def main():
    print("font:", FONT_PATH)
    for dpi, size in SIZES.items():
        d = os.path.join(RES, "mipmap-" + dpi)
        os.makedirs(d, exist_ok=True)
        make_legacy(size).save(os.path.join(d, "ic_launcher.png"))
        make_foreground(FG_SIZES[dpi]).save(
            os.path.join(d, "ic_launcher_foreground.png")
        )
        print("  mipmap-%s: ic_launcher.png %dpx, foreground %dpx"
              % (dpi, size, FG_SIZES[dpi]))


if __name__ == "__main__":
    main()
