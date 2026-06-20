#!/usr/bin/env python
"""Generate the Play Store feature graphic (1024x500) from logo.png."""
import os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = os.path.dirname(os.path.abspath(__file__))
os.makedirs(os.path.join(ROOT, "store"), exist_ok=True)
logo = Image.open(os.path.join(ROOT, "logo.png")).convert("RGBA")

W, H = 1024, 500
BG = (6, 5, 10, 255)
ACCENT = (234, 24, 224)
WHITE = (244, 244, 248)
GRAY = (158, 158, 170)

canvas = Image.new("RGBA", (W, H), BG)

# Soft magenta glow behind the logo for depth
glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
gd = ImageDraw.Draw(glow)
gd.ellipse((20, 70, 470, 520), fill=(150, 20, 150, 90))
glow = glow.filter(ImageFilter.GaussianBlur(70))
canvas.alpha_composite(glow)

# Logo on the left (its own black blends into the canvas)
lh = 440
logo_s = logo.resize((lh, lh), Image.LANCZOS)
canvas.alpha_composite(logo_s, (15, (H - lh) // 2))

draw = ImageDraw.Draw(canvas)

def font(path, size):
    try:
        return ImageFont.truetype(path, size)
    except Exception:
        return ImageFont.load_default()

SEGOE_B = "C:/Windows/Fonts/segoeuib.ttf"
SEGOE = "C:/Windows/Fonts/segoeui.ttf"

TEXT_X = 500
MAX_W = W - TEXT_X - 40

def wrap(text, fnt, max_w):
    words, lines, cur = text.split(), [], ""
    for w in words:
        trial = (cur + " " + w).strip()
        if draw.textlength(trial, font=fnt) <= max_w:
            cur = trial
        else:
            if cur:
                lines.append(cur)
            cur = w
    if cur:
        lines.append(cur)
    return lines

# Auto-fit the title to the available width
title = "JuneProcess"
size = 92
while size > 40:
    tf = font(SEGOE_B, size)
    if draw.textlength(title, font=tf) <= MAX_W:
        break
    size -= 2
title_f = font(SEGOE_B, size)
tag_f = font(SEGOE, 40)
sub_f = font(SEGOE, 26)

draw.text((TEXT_X, 110), title, font=title_f, fill=WHITE)

# The tagline (your phrase), word-wrapped, in accent magenta
tagline = "A camera that shoots photos like you see them"
y = 222
for line in wrap(tagline, tag_f, MAX_W):
    draw.text((TEXT_X, y), line, font=tag_f, fill=ACCENT)
    y += 50

draw.text((TEXT_X, y + 14), "Unprocessed RAW · full manual control", font=sub_f, fill=GRAY)

out = os.path.join(ROOT, "store", "feature-graphic.png")
canvas.convert("RGB").save(out, "PNG")
print("feature graphic ->", out, Image.open(out).size)
