"""Renders the same geometry and glass treatment as GlassKeyboardView, so the
design can be eyeballed without an emulator.

This is NOT a test. It is a hand port of the drawing arithmetic in
GlassKeyboardView, and it only tells the truth while someone keeps the two in
step -- change one, change the other. It exists because the look is the one
thing lint, the unit tests and a green build say nothing about, and the first
build of this keyboard shipped as a black slab with all three passing.

    python tools/preview.py     # writes tools/preview-<theme>-<page>.png

Needs Pillow. Two traps already caught here, worth not reintroducing: drawing a
translucent shape straight onto an RGBA canvas REPLACES its alpha instead of
blending it, and the rim is a vertical gradient on the device, not a flat
outline. Both quietly made the glass impossible to judge."""
import pathlib

from PIL import Image, ImageChops, ImageDraw, ImageFont
import sys

W, DENSITY = 1080, 2.75
SCREEN_H = 2400
ROW_UNITS, ROWS = 10.0, 4

SHIFT, BACKSPACE, ENTER, PAGE_SYMBOLS, PAGE_LETTERS, PAGE_MORE = -1, -2, -3, -4, -5, -6


def row(chars):
    return [(ord(c), c, 1.0) for c in chars]


def command_row(left_code, left_label, chars):
    return [(left_code, left_label, 1.5)] + row(chars) + [(BACKSPACE, "\u232B", 1.5)]


def bottom_row(page_code, page_label):
    return [(page_code, page_label, 1.5), (ord(","), ",", 1.0),
            (ord(" "), "", 5.0), (ord("."), ".", 1.0), (ENTER, "\u23CE", 1.5)]


PAGES = {
    "letters": [row("qwertyuiop"), row("asdfghjkl"),
                command_row(SHIFT, "\u21E7", "zxcvbnm"), bottom_row(PAGE_SYMBOLS, "?123")],
    "symbols": [row("1234567890"), row("@#$%&-+()"),
                command_row(PAGE_MORE, "=\\<", "*\"':;!?"), bottom_row(PAGE_LETTERS, "ABC")],
}



def draw_icon(d, code, x, y, w, h, colour, density, caps=False):
    """Ports drawIcon() from GlassKeyboardView."""
    s = min(w, h) * 0.42
    cx, cy = x + w / 2, y + h / 2
    lw = max(density, s * 0.14)

    if code == SHIFT:
        pts = [(cx, cy - s * 0.62), (cx - s * 0.60, cy + s * 0.02),
               (cx - s * 0.26, cy + s * 0.02), (cx - s * 0.26, cy + s * 0.52),
               (cx + s * 0.26, cy + s * 0.52), (cx + s * 0.26, cy + s * 0.02),
               (cx + s * 0.60, cy + s * 0.02)]
        if caps:
            d.polygon(pts, fill=colour)
        else:
            d.line(pts + [pts[0]], fill=colour, width=int(lw), joint="curve")
    elif code == BACKSPACE:
        pts = [(cx - s * 0.75, cy), (cx - s * 0.30, cy - s * 0.50),
               (cx + s * 0.75, cy - s * 0.50), (cx + s * 0.75, cy + s * 0.50),
               (cx - s * 0.30, cy + s * 0.50)]
        d.line(pts + [pts[0]], fill=colour, width=int(lw), joint="curve")
        d.line([(cx + s * 0.02, cy - s * 0.22), (cx + s * 0.46, cy + s * 0.22)],
               fill=colour, width=int(lw))
        d.line([(cx + s * 0.46, cy - s * 0.22), (cx + s * 0.02, cy + s * 0.22)],
               fill=colour, width=int(lw))
    elif code == ENTER:
        d.line([(cx + s * 0.62, cy - s * 0.58), (cx + s * 0.62, cy + s * 0.22),
                (cx - s * 0.52, cy + s * 0.22)], fill=colour, width=int(lw), joint="curve")
        d.line([(cx - s * 0.18, cy - s * 0.14), (cx - s * 0.62, cy + s * 0.22),
                (cx - s * 0.18, cy + s * 0.58)], fill=colour, width=int(lw), joint="curve")


def ramped_outline(size, x, y, w, h, radius, stroke, rgb, a_top, a_bot):
    """A rounded-rect outline whose alpha ramps vertically across the key."""
    layer = Image.new("RGBA", size, (0, 0, 0, 0))
    ImageDraw.Draw(layer).rounded_rectangle(
        [x, y, x + w, y + h], radius=radius, outline=rgb + (255,), width=stroke)
    ramp = Image.new("L", size, 0)
    rd = ImageDraw.Draw(ramp)
    span = max(1, int(h))
    for i in range(span):
        t = i / max(1, span - 1)
        rd.line([(0, int(y) + i), (size[0], int(y) + i)],
                fill=int(a_top + (a_bot - a_top) * t))
    layer.putalpha(ImageChops.multiply(layer.getchannel("A"), ramp))
    return layer


def layout(keys_rows, width, height):
    pad_h, pad_v = 14 * DENSITY, 7 * DENSITY
    gap = 6 * DENSITY
    available = width - 2 * pad_h
    row_height = (height - 2 * pad_v) / len(keys_rows)
    key_height = row_height - gap
    reference_unit = (available - gap * (ROW_UNITS - 1)) / ROW_UNITS

    placed, y = [], pad_v
    for r in keys_rows:
        total_weight = sum(k[2] for k in r)
        if total_weight >= ROW_UNITS:
            unit = (available - gap * (len(r) - 1)) / total_weight
        else:
            unit = reference_unit
        row_width = gap * (len(r) - 1) + unit * total_weight
        x = pad_h + (available - row_width) / 2
        out = []
        for code, label, weight in r:
            w = unit * weight
            out.append((code, label, x, y, w, key_height))
            x += w + gap
        placed.append(out)
        y += row_height
    return placed, key_height, gap


def blend(base, layer):
    return Image.alpha_composite(base, layer)


def draw_keyboard(bg, page, night):
    height = round(min(46 * DENSITY, SCREEN_H * 0.10) * ROWS + 14 * DENSITY)
    rows_placed, key_h, gap = layout(PAGES[page], W, height)
    radius = 10 * DENSITY

    top, bottom = (0x3D, 0x21) if night else (0x96, 0x63)
    rim_top, rim_bottom = (0x99, 0x26) if night else (0xB3, 0x2B)
    dark_rim_bottom = 0
    text_rgb = (255, 255, 255) if night else (0x14, 0x16, 0x1C)

    board = Image.new("RGBA", (W, height), (0, 0, 0, 0))

    try:
        font_big = ImageFont.truetype("C:/Windows/Fonts/segoeui.ttf", int(key_h * 0.40))
        font_small = ImageFont.truetype("C:/Windows/Fonts/segoeui.ttf", int(key_h * 0.29))
    except OSError:
        font_big = font_small = ImageFont.load_default()

    for r in rows_placed:
        for code, label, x, y, w, h in r:
            command = code < 0
            a_top, a_bot = top, bottom
            if command or code == ord(" "):
                a_top, a_bot = int(top * 0.6), int(bottom * 0.6)

            layer = Image.new("RGBA", (W, height), (0, 0, 0, 0))
            d = ImageDraw.Draw(layer)
            # Fake soft shadow: three offset rounded rects with falling alpha.
            # Android cannot blur a shape cheaply under hardware acceleration, so
            # the falloff is stacked by hand rather than by a BlurMaskFilter.
            for off, sa in ((3, 0x16), (2, 0x12), (1, 0x0E)):
                sl = Image.new("RGBA", (W, height), (0, 0, 0, 0))
                ImageDraw.Draw(sl).rounded_rectangle(
                    [x + off * 0.4, y + off, x + w - off * 0.4, y + h + off],
                    radius=radius, fill=(0, 0, 0, sa))
                layer = blend(layer, sl)
            # Body: approximate the vertical gradient with horizontal bands.
            steps = 24
            for i in range(steps):
                t = i / (steps - 1)
                alpha = int(a_top + (a_bot - a_top) * t)
                y0 = y + h * i / steps
                y1 = y + h * (i + 1) / steps + 1
                band = Image.new("RGBA", (W, height), (0, 0, 0, 0))
                bd = ImageDraw.Draw(band)
                bd.rounded_rectangle([x, y, x + w, y + h], radius=radius,
                                     fill=(255, 255, 255, alpha))
                crop = band.crop((0, int(y0), W, int(min(y1, y + h))))
                layer.paste(crop, (0, int(y0)))
            # Rim light: a hairline outline whose alpha ramps top to bottom, the
            # same as the LinearGradient on the rim paint on the device.
            stroke = max(1, int(DENSITY * 0.75))
            layer = blend(layer, ramped_outline(
                (W, height), x, y, w, h, radius, stroke,
                (255, 255, 255), rim_top, rim_bottom))
            if dark_rim_bottom:
                layer = blend(layer, ramped_outline(
                    (W, height), x, y, w, h, radius, stroke,
                    (0, 0, 0), 0, dark_rim_bottom))
            board = blend(board, layer)

            if code in (SHIFT, BACKSPACE, ENTER):
                draw_icon(ImageDraw.Draw(board), code, x, y, w, h,
                          text_rgb + (255,), DENSITY, caps=False)
            elif label:
                td = ImageDraw.Draw(board)
                font = font_small if command and len(label) > 1 else font_big
                td.text((x + w / 2, y + h / 2), label, font=font, anchor="mm",
                        fill=text_rgb + (255,))

    out = bg.copy()
    out.paste(board, (0, bg.height - height), board)
    return out


def backdrop(kind):
    """A wallpaper-like image. Drawing translucent shapes straight onto an RGBA
    canvas REPLACES alpha instead of blending it, which silently rendered the
    mock content as solid white before -- every overlay goes through a layer."""
    from PIL import ImageFilter
    img = Image.new("RGBA", (W, 1400))
    d = ImageDraw.Draw(img)
    if kind == "dark":
        a, b = (14, 18, 34), (52, 26, 64)
        blobs = [((250, 320), 420, (96, 40, 140)), ((880, 700), 520, (20, 70, 130)),
                 ((420, 1180), 460, (150, 48, 96))]
    else:
        a, b = (252, 250, 248), (206, 222, 240)
        blobs = [((260, 300), 440, (255, 214, 170)), ((900, 680), 520, (168, 206, 248)),
                 ((400, 1150), 460, (222, 196, 246))]
    for y in range(img.height):
        t = y / (img.height - 1)
        d.line([(0, y), (W, y)],
               fill=tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3)) + (255,))
    # Soft colour blobs, blurred, so the glass has real variation behind it.
    for (cx, cy), r, rgb in blobs:
        layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
        ImageDraw.Draw(layer).ellipse([cx - r, cy - r, cx + r, cy + r],
                                      fill=rgb + (150,))
        img = Image.alpha_composite(img, layer.filter(ImageFilter.GaussianBlur(120)))
    # App-like content, blended properly and kept clear of the keyboard strip.
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ld = ImageDraw.Draw(layer)
    for i, ty in enumerate(range(110, 760, 105)):
        w = W - 220 if i % 2 else W - 460
        ld.rounded_rectangle([90, ty, 90 + w, ty + 64], radius=32,
                             fill=(255, 255, 255, 48) if kind == "dark" else (24, 34, 54, 38))
    return Image.alpha_composite(img, layer)


for kind, night in (("dark", True), ("light", False)):
    for page in ("letters", "symbols"):
        img = draw_keyboard(backdrop(kind), page, night)
        name = f"preview-{kind}-{page}.png"
        img.convert("RGB").save(pathlib.Path(__file__).parent / name, quality=92)
        print("wrote", name)
