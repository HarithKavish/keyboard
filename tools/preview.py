"""Renders the same geometry and glass treatment as GlassKeyboardView, so the
design can be eyeballed without an emulator.

This is NOT a test. It is a hand port of the drawing arithmetic in
GlassKeyboardView, and it only tells the truth while someone keeps the two in
step -- change one, change the other. It exists because the look is the one
thing lint, the unit tests and a green build say nothing about, and the first
build of this keyboard shipped as a black slab with all three passing.

    python tools/preview.py     # writes tools/preview-<theme>-<page>.png

Needs Pillow. Three traps already caught here, worth not reintroducing: drawing
a translucent shape straight onto an RGBA canvas REPLACES its alpha instead of
blending it, the rim is a vertical gradient on the device rather than a flat
outline, and rows are no longer all the same height.
"""
import pathlib

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

W, DENSITY = 1080, 2.75
SCREEN_H = 2400
ROW_UNITS = 10.0

SHIFT, BACKSPACE, ENTER = -1, -2, -3
PAGE_SYMBOLS, PAGE_LETTERS, PAGE_MORE, EMOJI = -4, -5, -6, -7

EMOJI_FONT = "C:/Windows/Fonts/seguiemj.ttf"
TEXT_FONT = "C:/Windows/Fonts/segoeui.ttf"


def row(chars):
    return [(ord(c), c, 1.0) for c in chars]


def command_row(left_code, left_label, chars):
    return [(left_code, left_label, 1.5)] + row(chars) + [(BACKSPACE, "", 1.5)]


def bottom_row(cycle_code, cycle_label):
    """One cycling key bottom left: letters -> symbols -> emoji -> letters."""
    return [(cycle_code, cycle_label, 1.5), (ord(","), ",", 1.0),
            (ord("@"), "@", 1.0), (ord(" "), "", 4.0), (ord("."), ".", 1.0),
            (ENTER, "", 1.5)]


PAGES = {
    "letters": [row("1234567890"), row("qwertyuiop"), row("asdfghjkl"),
                command_row(SHIFT, "", "zxcvbnm"), bottom_row(PAGE_SYMBOLS, "?123")],
    "symbols": [row("1234567890"), row("@#$%&-+()"),
                command_row(PAGE_MORE, "=\\<", "*\"':;!?"),
                bottom_row(EMOJI, "")],
}
ROW_HEIGHTS = {"letters": [0.66, 1.0, 1.0, 1.0, 1.0], "symbols": [1.0, 1.0, 1.0, 1.0]}

# What the strip would be offering part way through a word.
SUGGEST_WORDS = ["keyboard", "keep", "key"]
SUGGEST_EMOJI = ["\U0001F680", "\u2728"]
# The symbols render deliberately has none, to show the words spreading out.
SUGGEST_EMOJI_BY_PAGE = {"letters": SUGGEST_EMOJI, "symbols": []}


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
    elif code == EMOJI:
        r = s * 0.62
        d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=colour, width=int(lw))
        eye_r = max(density * 0.8, s * 0.08)
        for ex in (-s * 0.24, s * 0.24):
            d.ellipse([cx + ex - eye_r, cy - s * 0.18 - eye_r,
                       cx + ex + eye_r, cy - s * 0.18 + eye_r], fill=colour)
        # Quadratic smile, sampled -- PIL has no quadTo.
        pts = []
        for i in range(13):
            t = i / 12
            px = (1 - t) ** 2 * (cx - s * 0.28) + 2 * (1 - t) * t * cx + t ** 2 * (cx + s * 0.28)
            py = ((1 - t) ** 2 * (cy + s * 0.14) + 2 * (1 - t) * t * (cy + s * 0.46)
                  + t ** 2 * (cy + s * 0.14))
            pts.append((px, py))
        d.line(pts, fill=colour, width=int(lw), joint="curve")
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


def layout(keys_rows, heights, width, height, strip_height):
    """Ports layoutKeys(): no vertical padding, per-row heights."""
    pad_h = 14 * DENSITY
    gap = 6 * DENSITY
    available = width - 2 * pad_h
    letter_row = (height - strip_height) / sum(heights)
    reference_unit = (available - gap * (ROW_UNITS - 1)) / ROW_UNITS

    placed, y = [], strip_height
    for r, keys in enumerate(keys_rows):
        row_height = letter_row * heights[r]
        total_weight = sum(k[2] for k in keys)
        if total_weight >= ROW_UNITS:
            unit = (available - gap * (len(keys) - 1)) / total_weight
        else:
            unit = reference_unit
        row_width = gap * (len(keys) - 1) + unit * total_weight
        x = pad_h + (available - row_width) / 2
        out = []
        for code, label, weight in keys:
            w = unit * weight
            out.append((code, label, x, y, w, row_height - gap))
            x += w + gap
        placed.append(out)
        y += row_height
    return placed, letter_row, gap, pad_h, available


def blend(base, layer):
    return Image.alpha_composite(base, layer)


def glass_key(size, x, y, w, h, radius, a_top, a_bot, rim_top, rim_bot, stroke):
    layer = Image.new("RGBA", size, (0, 0, 0, 0))
    # Fake soft shadow: three offset rounded rects with falling alpha.
    for off, sa in ((3, 0x16), (2, 0x12), (1, 0x0E)):
        sl = Image.new("RGBA", size, (0, 0, 0, 0))
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
        band = Image.new("RGBA", size, (0, 0, 0, 0))
        ImageDraw.Draw(band).rounded_rectangle(
            [x, y, x + w, y + h], radius=radius, fill=(255, 255, 255, alpha))
        layer.paste(band.crop((0, int(y0), size[0], int(min(y1, y + h)))), (0, int(y0)))
    layer = blend(layer, ramped_outline(size, x, y, w, h, radius, stroke,
                                        (255, 255, 255), rim_top, rim_bot))
    return layer


def draw_keyboard(bg, page, night):
    heights = ROW_HEIGHTS[page]
    strip_height = 40 * DENSITY
    height = round(min(46 * DENSITY, SCREEN_H * 0.075) * sum(ROW_HEIGHTS["letters"])
                   + strip_height)
    rows_placed, letter_row, gap, pad_h, available = layout(
        PAGES[page], heights, W, height, strip_height)
    radius = 10 * DENSITY
    stroke = max(1, int(DENSITY * 0.75))

    top, bottom = (0x3D, 0x21) if night else (0x96, 0x63)
    rim_top, rim_bottom = (0x99, 0x26) if night else (0xB3, 0x2B)
    text_rgb = (255, 255, 255) if night else (0x14, 0x16, 0x1C)

    board = Image.new("RGBA", (W, height), (0, 0, 0, 0))

    def font(path, size):
        try:
            return ImageFont.truetype(path, int(size))
        except OSError:
            return ImageFont.load_default()

    for r, keys in enumerate(rows_placed):
        row_height = letter_row * heights[r] - gap
        font_big = font(TEXT_FONT, row_height * 0.40)
        font_small = font(TEXT_FONT, row_height * 0.29)
        for code, label, x, y, w, h in keys:
            a_top, a_bot = top, bottom
            if code < 0 or code == ord(" "):
                a_top, a_bot = int(top * 0.6), int(bottom * 0.6)
            board = blend(board, glass_key((W, height), x, y, w, h, radius,
                                           a_top, a_bot, rim_top, rim_bottom, stroke))
            if code in (SHIFT, BACKSPACE, ENTER, EMOJI):
                draw_icon(ImageDraw.Draw(board), code, x, y, w, h,
                          text_rgb + (255,), DENSITY)
            elif label:
                ImageDraw.Draw(board).text(
                    (x + w / 2, y + h / 2), label,
                    font=font_small if len(label) > 1 else font_big,
                    anchor="mm", fill=text_rgb + (255,))

    draw_strip(board, strip_height, pad_h, available, text_rgb,
               SUGGEST_EMOJI_BY_PAGE.get(page, SUGGEST_EMOJI))

    out = bg.copy()
    out.paste(board, (0, bg.height - height), board)
    return out


def draw_strip(board, strip_height, pad_h, available, text_rgb, emoji):
    """Three words, best in the centre, then two emoji on the right.

    With no emoji to show, the words take the whole strip rather than leaving a
    third of it empty."""
    d = ImageDraw.Draw(board)
    word_font = ImageFont.truetype(TEXT_FONT, int(strip_height * 0.34))
    words_width = available * 0.72 if emoji else available
    slot = words_width / 3
    for i in range(3):
        rank = 0 if i == 1 else (1 if i == 0 else 2)
        if rank >= len(SUGGEST_WORDS):
            continue
        cx = pad_h + slot * (i + 0.5)
        d.text((cx, strip_height / 2), SUGGEST_WORDS[rank], font=word_font,
               anchor="mm", fill=text_rgb + (255,))

    try:
        emoji_font = ImageFont.truetype(EMOJI_FONT, int(strip_height * 0.46))
        colour = True
    except OSError:
        emoji_font = word_font
        colour = False
    emoji_left = pad_h + words_width
    emoji_slot = (available - words_width) / 2
    for i, glyph in enumerate(emoji[:2]):
        cx = emoji_left + emoji_slot * (i + 0.5)
        try:
            d.text((cx, strip_height / 2), glyph, font=emoji_font, anchor="mm",
                   embedded_color=colour, fill=text_rgb + (255,))
        except (TypeError, OSError):
            d.text((cx, strip_height / 2), glyph, font=word_font, anchor="mm",
                   fill=text_rgb + (255,))


def backdrop(kind):
    """A wallpaper-like image. Drawing translucent shapes straight onto an RGBA
    canvas REPLACES alpha instead of blending it, which silently rendered the
    mock content as solid white before -- every overlay goes through a layer."""
    img = Image.new("RGBA", (W, 1500))
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
    for (cx, cy), r, rgb in blobs:
        layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
        ImageDraw.Draw(layer).ellipse([cx - r, cy - r, cx + r, cy + r], fill=rgb + (150,))
        img = Image.alpha_composite(img, layer.filter(ImageFilter.GaussianBlur(120)))
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ld = ImageDraw.Draw(layer)
    for i, ty in enumerate(range(110, 700, 105)):
        w = W - 220 if i % 2 else W - 460
        ld.rounded_rectangle([90, ty, 90 + w, ty + 64], radius=32,
                             fill=(255, 255, 255, 48) if kind == "dark" else (24, 34, 54, 38))
    return Image.alpha_composite(img, layer)


for kind, night in (("dark", True), ("light", False)):
    for page in ("letters", "symbols"):
        img = draw_keyboard(backdrop(kind), page, night)
        name = "preview-%s-%s.png" % (kind, page)
        img.convert("RGB").save(pathlib.Path(__file__).parent / name, quality=92)
        print("wrote", name)
