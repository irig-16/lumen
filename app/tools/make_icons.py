import sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont
out = sys.argv[1]
S = 4  # supersample

def lens(d, cx, cy, r):
    # concentric "lens" rings with a glowing core
    for i, (rr, col) in enumerate([(1.0, (138,180,255,70)), (0.74, (138,180,255,140)), (0.48, (190,214,255,255))]):
        w = int(r*0.09)
        d.ellipse([cx-r*rr, cy-r*rr, cx+r*rr, cy+r*rr], outline=col, width=max(2, w))
    d.ellipse([cx-r*0.2, cy-r*0.2, cx+r*0.2, cy+r*0.2], fill=(255,255,255,255))

# --- app icon 96px (xhdpi 48dp)
n = 96*S
img = Image.new("RGBA", (n, n), (0,0,0,0))
d = ImageDraw.Draw(img)
d.rounded_rectangle([0,0,n-1,n-1], radius=int(n*0.24), fill=(14,20,38,255))
glow = Image.new("RGBA", (n, n), (0,0,0,0))
ImageDraw.Draw(glow).ellipse([n*0.2,n*0.2,n*0.8,n*0.8], fill=(90,130,255,110))
glow = glow.filter(ImageFilter.GaussianBlur(n*0.08))
img = Image.alpha_composite(img, glow)
lens(ImageDraw.Draw(img), n/2, n/2, n*0.32)
img.resize((96,96), Image.LANCZOS).save(f"{out}/ic_launcher.png")

# --- leanback banner 320x180 (xhdpi 160x90dp)
W, H = 320*S, 180*S
b = Image.new("RGBA", (W, H), (10,14,26,255))
g = Image.new("RGBA", (W, H), (0,0,0,0))
ImageDraw.Draw(g).ellipse([W*0.04,H*0.12,W*0.4,H*0.88], fill=(90,130,255,90))
g = g.filter(ImageFilter.GaussianBlur(H*0.12))
b = Image.alpha_composite(b, g)
bd = ImageDraw.Draw(b)
lens(bd, W*0.22, H/2, H*0.27)
try:
    f = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", int(H*0.24))
except OSError:
    f = ImageFont.load_default()
bd.text((W*0.42, H/2), "Lumen", font=f, fill=(242,245,250,255), anchor="lm")
b.convert("RGB").resize((320,180), Image.LANCZOS).save(f"{out}/banner.png")
print("ok")
