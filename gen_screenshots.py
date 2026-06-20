#!/usr/bin/env python
"""Frame raw device screenshots into captioned Play Store screenshots on the brand background.

Usage: edit the SHOTS list (source png + caption) and run. Outputs 1080x2160 (max 2:1) PNGs.
Drop raw device captures into store/raw/ and point each entry at one.
"""
import os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(ROOT, "store")
os.makedirs(OUT, exist_ok=True)

W, H = 1080, 2160
BG = (8, 6, 12)
ACCENT = (234, 24, 224)
WHITE = (244, 244, 248)

SEGOE_B = "C:/Windows/Fonts/segoeuib.ttf"
SEGOE = "C:/Windows/Fonts/segoeui.ttf"

def font(path, size):
    try:
        return ImageFont.truetype(path, size)
    except Exception:
        return ImageFont.load_default()

# (source screenshot path, headline, accent subtitle)
SHOTS = [
    ("store/raw/verify_camera.png", "Full manual control", "ISO · shutter · white balance · focus"),
]

def make(src, headline, subtitle, out_name):
    canvas = Image.new("RGB", (W, H), BG)
    # subtle magenta glow top
    glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse((-200, -300, W + 200, 400), fill=(150, 20, 150, 70))
    canvas.paste(Image.alpha_composite(canvas.convert("RGBA"), glow.filter(ImageFilter.GaussianBlur(90))).convert("RGB"), (0, 0))

    draw = ImageDraw.Draw(canvas)
    hf = font(SEGOE_B, 64)
    sf = font(SEGOE, 36)
    # centered caption band at top
    hw = draw.textlength(headline, font=hf)
    draw.text(((W - hw) / 2, 90), headline, font=hf, fill=WHITE)
    sw = draw.textlength(subtitle, font=sf)
    draw.text(((W - sw) / 2, 175), subtitle, font=sf, fill=ACCENT)

    # screenshot below the caption, fit into remaining height with rounded corners
    shot = Image.open(os.path.join(ROOT, src)).convert("RGBA")
    top = 260
    avail_h = H - top - 40
    scale = min((W - 120) / shot.width, avail_h / shot.height)
    sw2, sh2 = int(shot.width * scale), int(shot.height * scale)
    shot = shot.resize((sw2, sh2), Image.LANCZOS)
    # rounded corners
    r = 36
    mask = Image.new("L", (sw2, sh2), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, sw2, sh2), radius=r, fill=255)
    px = (W - sw2) // 2
    canvas.paste(shot.convert("RGB"), (px, top), mask)
    canvas.save(os.path.join(OUT, out_name), "PNG")
    return out_name

for i, (src, h, s) in enumerate(SHOTS, 1):
    if os.path.exists(os.path.join(ROOT, src)):
        name = make(src, h, s, f"screenshot-{i}.png")
        print("made", name)
    else:
        print("skip (missing):", src)
