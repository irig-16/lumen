"""Shrinks the rendered illustrations: the hero (soft glow) as WebP, the flat cards as 256-colour PNGs.
Run after render.js:  python3 docs/illustrations/optimize.py"""
import os
from PIL import Image

IMG = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "images")
hero = os.path.join(IMG, "hero.png")
Image.open(hero).save(os.path.join(IMG, "hero.webp"), "WEBP", quality=90, method=6)
os.remove(hero)
for n in ("speed", "safety", "secure"):
    p = os.path.join(IMG, n + ".png")
    Image.open(p).quantize(colors=256, method=Image.Quantize.FASTOCTREE,
                           dither=Image.Dither.FLOYDSTEINBERG).save(p, optimize=True)
for f in sorted(os.listdir(IMG)):
    print(f, os.path.getsize(os.path.join(IMG, f)) // 1024, "KB")
