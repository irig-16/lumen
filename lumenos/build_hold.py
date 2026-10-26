#!/usr/bin/env python3
"""Builds the "Lumen hold" boot animations for the HY300.

Why two layouts: the boot animation surface is the display's physical size (1920x1080).
Part-way through boot, Android applies LumenOS's 1280x720 render size; from then on only
the top-left 1280x720 of that surface is shown, zoomed 1.5x (that is what cut MAGCUBIC
to "MAGCU"). So:
  pre  frame = the splash scaled to 1920x1080        (correct BEFORE the switch)
  post frame = the splash 1:1 in the top-left corner (correct AFTER the switch)
Both look identical to the u-boot splash. Around the switch moment T (measured once with
the timing-check animation) the picture dips softly to black, so the switch is invisible.
"""
import io, os, zipfile
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
SPLASH = os.path.join(HERE, "files/logo/advert.bmp")
OUT = os.path.join(HERE, "files/logo/hold")
BG = (6, 6, 8)
FPS = 10
M = 0.8              # seconds of black either side of the switch
FADE = [0.62, 0.3, 0.1]   # fade-out levels (fade-in is the reverse)
TS = [x / 2 for x in range(1, 31)]   # 0.5 .. 15.0 in 0.5 s steps


def png(im):
    b = io.BytesIO()
    im.save(b, "PNG", optimize=True)
    return b.getvalue()


def dim(im, k):
    return im.point(lambda v: int(round(v * k)))


splash = Image.open(SPLASH).convert("RGB")           # 1280x720
pre = splash.resize((1920, 1080), Image.LANCZOS)
post = Image.new("RGB", (1920, 1080), (0, 0, 0))
post.paste(splash, (0, 0))
black = Image.new("RGB", (1920, 1080), (0, 0, 0))

P_PRE, P_POST, P_BLACK = png(pre), png(post), png(black)
P_PRE_F = [png(dim(pre, k)) for k in FADE]
P_POST_F = [png(dim(post, k)) for k in reversed(FADE)]


def write_zip(path, desc, parts):
    """parts: {dirname: [png bytes, ...]}. Stored (no compression), desc.txt first."""
    with zipfile.ZipFile(path, "w", zipfile.ZIP_STORED) as z:
        z.writestr("desc.txt", desc)
        for d, frames in parts.items():
            z.writestr(zipfile.ZipInfo(d + "/"), b"")
            for i, f in enumerate(frames):
                z.writestr(f"{d}/{i:03d}.png", f)


os.makedirs(OUT, exist_ok=True)
for f in os.listdir(OUT):
    os.remove(os.path.join(OUT, f))

nf = len(FADE)
for T in TS:
    # Timeline (seconds from the first frame):
    #   pre splash ... fade out ... black [T-M, T+M] ... fade in ... post splash (loops)
    black_start = T - M
    lines = ["1920 1080 %d" % FPS]
    parts = {}
    pre_len = black_start - nf / FPS           # time the pre splash is shown
    if pre_len >= 0.1:
        pause = int(round(pre_len * FPS)) - 1   # 1 frame shown + pause frames
        lines.append("p 1 %d pre #060608" % pause)
        lines.append("p 1 0 fo #000000")
        parts["pre"] = [P_PRE]
        parts["fo"] = P_PRE_F
        blk = 2 * M
    else:
        blk = T + M                             # switch too early: start in black
    lines.append("p 1 %d blk #000000" % max(0, int(round(blk * FPS)) - 1))
    lines.append("p 1 0 fi #000000")
    lines.append("p 0 0 post #000000")
    parts["blk"] = [P_BLACK]
    parts["fi"] = P_POST_F
    parts["post"] = [P_POST]
    write_zip(os.path.join(OUT, "hold-%.1f.zip" % T), "\n".join(lines) + "\n", parts)

# No switch seen: whole boot shows the full-surface layout.
write_zip(os.path.join(OUT, "hold-full.zip"), "1920 1080 %d\np 0 0 pre #060608\n" % FPS,
          {"pre": [P_PRE]})
# Physical display already 1280x720: the splash 1:1.
write_zip(os.path.join(OUT, "hold-flat.zip"), "1280 720 %d\np 0 0 flat #060608\n" % FPS,
          {"flat": [png(splash)]})

# ---- Timing-check animation: a counter every 0.5 s, placed so the "jump" is obvious.
def find_font():
    for f in ("/usr/share/fonts/opentype/inter/Inter-Regular.otf", "/usr/share/fonts/truetype/inter/Inter-Regular.ttf",
              "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", "C:/Windows/Fonts/segoeui.ttf"):
        if os.path.exists(f):
            return f
    raise SystemExit("No font found: install fonts-inter (or DejaVu)")


FONT = find_font()
font = ImageFont.truetype(FONT, 150)
small = ImageFont.truetype(FONT, 26)
frames = []
for i in range(0, 61):                      # 0.0 .. 30.0
    im = Image.new("RGB", (1920, 1080), (0, 0, 0))
    d = ImageDraw.Draw(im)
    t = "%d" % (i // 2)
    if i % 2:
        t = "%.1f" % (i / 2)
    d.text((640, 320), t, font=font, fill=(255, 190, 62), anchor="mm")
    d.text((640, 450), "L U M E N   T I M I N G   C H E C K", font=small,
           fill=(200, 200, 205), anchor="mm")
    frames.append(png(im))
im = Image.new("RGB", (1920, 1080), (0, 0, 0))
d = ImageDraw.Draw(im)
d.text((640, 320), "30+", font=font, fill=(255, 190, 62), anchor="mm")
write_zip(os.path.join(OUT, "timing.zip"), "1920 1080 2\np 1 0 count #000000\np 0 0 over #000000\n",
          {"count": frames, "over": [png(im)]})

tot = sum(os.path.getsize(os.path.join(OUT, f)) for f in os.listdir(OUT))
print(len(os.listdir(OUT)), "files,", tot // 1024, "KB")
