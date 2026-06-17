#!/usr/bin/env python
"""Generate the full Android adaptive-icon set (incl. Material You monochrome) from logo.png."""
import os
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(ROOT, "app", "src", "main", "res")
src = Image.open(os.path.join(ROOT, "logo.png")).convert("RGBA")

# Adaptive layers are 108dp; legacy launcher icons are 48dp.
FG = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
IC = {"mdpi": 48,  "hdpi": 72,  "xhdpi": 96,  "xxhdpi": 144, "xxxhdpi": 192}

def webp(img, path):
    img.save(path, "WEBP", lossless=True, quality=100)

# 1) Foreground: the design is already inset ~10% in black, so full-bleed keeps the
#    aperture inside the adaptive safe zone while the built-in margin reads as padding.
for d, px in FG.items():
    webp(src.resize((px, px), Image.LANCZOS), f"{RES}/mipmap-{d}/ic_launcher_foreground.webp")

# 2) Monochrome (Material You): tint-able silhouette. Only ALPHA matters — the system
#    recolors it with the wallpaper theme. Bright magenta -> opaque; dark (bg/blades/J) -> cut out.
THR = 50
alpha = src.convert("L").point(lambda p: 255 if p > THR else 0)
white = Image.new("RGBA", src.size, (255, 255, 255, 255))
mono = Image.composite(white, Image.new("RGBA", src.size, (255, 255, 255, 0)), alpha)
mono = mono.filter(ImageFilter.GaussianBlur(0.6))  # soften the pixel-edges a touch
for d, px in FG.items():
    webp(mono.resize((px, px), Image.LANCZOS), f"{RES}/mipmap-{d}/ic_launcher_monochrome.webp")

# 3) Legacy launcher icons (square + circle-cropped) for pre-API-26 / non-adaptive launchers.
def circle(img):
    m = Image.new("L", img.size, 0)
    ImageDraw.Draw(m).ellipse((0, 0, img.size[0] - 1, img.size[1] - 1), fill=255)
    out = img.copy(); out.putalpha(m); return out

for d, px in IC.items():
    sq = src.resize((px, px), Image.LANCZOS)
    webp(sq, f"{RES}/mipmap-{d}/ic_launcher.webp")
    webp(circle(sq), f"{RES}/mipmap-{d}/ic_launcher_round.webp")

# 4) Play Store / store-listing icon (512x512 PNG).
src.resize((512, 512), Image.LANCZOS).save(
    os.path.join(ROOT, "app", "src", "main", "ic_launcher-playstore.png"), "PNG")

print("Icon set generated:")
print(f"  foreground + monochrome: {len(FG)} densities each")
print(f"  launcher (square+round): {len(IC)} densities each")
print(f"  play store: 512x512")
