# -*- coding: utf-8 -*-
"""Generates Emoji.java. Emoji go in as backslash-u escapes rather than literals so
the source stays pure ASCII and cannot be mangled by a build whose file encoding
differs from the editor's."""
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
DIR = ROOT / "app/src/main/java/com/harithkavish/keyboard"


def esc(s):
    b = s.encode("utf-16-be")
    return "".join("\\u%04x" % int.from_bytes(b[i:i + 2], "big") for i in range(0, len(b), 2))


def jarray(items, indent=8, per_line=6):
    out, line = [], []
    for it in items:
        line.append('"%s"' % esc(it))
        if len(line) == per_line:
            out.append(" " * indent + ", ".join(line) + ",")
            line = []
    if line:
        out.append(" " * indent + ", ".join(line) + ",")
    return "\n".join(out)


CATEGORIES = [
    ("Smileys", "\U0001F642", [
        "\U0001F600", "\U0001F603", "\U0001F604", "\U0001F601", "\U0001F606", "\U0001F605",
        "\U0001F923", "\U0001F602", "\U0001F642", "\U0001F643", "\U0001F609", "\U0001F60A",
        "\U0001F607", "\U0001F970", "\U0001F60D", "\U0001F929", "\U0001F618", "\U0001F617",
        "\U0001F61A", "\U0001F619", "\U0001F60B", "\U0001F61B", "\U0001F61C", "\U0001F92A",
        "\U0001F60E", "\U0001F973", "\U0001F60F", "\U0001F612", "\U0001F61E", "\U0001F614",
        "\U0001F61F", "\U0001F625", "\U0001F622", "\U0001F62D", "\U0001F631", "\U0001F633",
        "\U0001F97A", "\U0001F624", "\U0001F620", "\U0001F621", "\U0001F914", "\U0001F92D",
        "\U0001F92B", "\U0001F910", "\U0001F60C", "\U0001F644", "\U0001F62A", "\U0001F634",
        "\U0001F927", "\U0001F912", "\U0001F915", "\U0001F911", "\U0001F917", "\U0001F636",
        "\U0001F44D", "\U0001F44E", "\U0001F44C", "✌️", "\U0001F91E", "\U0001F44F",
        "\U0001F64F", "\U0001F4AA", "\U0001F44B", "\U0001F91D", "✍️", "\U0001F64C",
    ]),
    ("Nature", "\U0001F33F", [
        "\U0001F436", "\U0001F431", "\U0001F42D", "\U0001F439", "\U0001F430", "\U0001F98A",
        "\U0001F43B", "\U0001F43C", "\U0001F428", "\U0001F42F", "\U0001F981", "\U0001F42E",
        "\U0001F437", "\U0001F438", "\U0001F412", "\U0001F414", "\U0001F427", "\U0001F426",
        "\U0001F41D", "\U0001F98B", "\U0001F41E", "\U0001F422", "\U0001F419", "\U0001F420",
        "\U0001F40B", "\U0001F984", "\U0001F332", "\U0001F333", "\U0001F334", "\U0001F335",
        "\U0001F33F", "☘️", "\U0001F340", "\U0001F341", "\U0001F342", "\U0001F337",
        "\U0001F338", "\U0001F339", "\U0001F33B", "\U0001F33C", "\U0001F490", "\U0001F31E",
        "\U0001F31D", "\U0001F31A", "⭐", "\U0001F31F", "✨", "⚡",
        "\U0001F525", "\U0001F308", "☁️", "❄️", "\U0001F30A", "\U0001F30D",
    ]),
    ("Food", "\U0001F375", [
        "\U0001F34F", "\U0001F34E", "\U0001F350", "\U0001F34A", "\U0001F34B", "\U0001F34C",
        "\U0001F349", "\U0001F347", "\U0001F353", "\U0001F352", "\U0001F351", "\U0001F96D",
        "\U0001F345", "\U0001F955", "\U0001F33D", "\U0001F954", "\U0001F35E", "\U0001F950",
        "\U0001F956", "\U0001F9C0", "\U0001F95A", "\U0001F373", "\U0001F953", "\U0001F969",
        "\U0001F357", "\U0001F354", "\U0001F35F", "\U0001F355", "\U0001F32D", "\U0001F32E",
        "\U0001F32F", "\U0001F959", "\U0001F958", "\U0001F35D", "\U0001F35C", "\U0001F363",
        "\U0001F371", "\U0001F359", "\U0001F358", "\U0001F365", "\U0001F368", "\U0001F366",
        "\U0001F370", "\U0001F382", "\U0001F36B", "\U0001F36C", "\U0001F36F", "☕",
        "\U0001F375", "\U0001F376", "\U0001F37A", "\U0001F37B", "\U0001F377", "\U0001F964",
    ]),
    ("Activity", "⚽", [
        "⚽", "\U0001F3C0", "\U0001F3C8", "⚾", "\U0001F3BE", "\U0001F3D0",
        "\U0001F3C9", "\U0001F3B1", "\U0001F3D3", "\U0001F3F8", "\U0001F94A", "\U0001F3AF",
        "⛳", "\U0001F3BF", "\U0001F3C2", "\U0001F93C", "\U0001F93A", "\U0001F6B4",
        "\U0001F3CA", "\U0001F9D8", "\U0001F3C6", "\U0001F947", "\U0001F948", "\U0001F949",
        "\U0001F3C5", "\U0001F3AA", "\U0001F3AD", "\U0001F3A8", "\U0001F3AC", "\U0001F3A4",
        "\U0001F3A7", "\U0001F3B5", "\U0001F3B6", "\U0001F3B9", "\U0001F3B7", "\U0001F3BA",
        "\U0001F3B8", "\U0001F3BB", "\U0001F3B2", "\U0001F3AE", "\U0001F945", "\U0001F3AF",
    ]),
    ("Travel", "\U0001F697", [
        "\U0001F697", "\U0001F695", "\U0001F699", "\U0001F68C", "\U0001F68E", "\U0001F693",
        "\U0001F691", "\U0001F692", "\U0001F69A", "\U0001F69C", "\U0001F6F5", "\U0001F6B2",
        "\U0001F6F4", "\U0001F68F", "\U0001F6A8", "✈️", "\U0001F6EB", "\U0001F6EC",
        "\U0001F681", "⛵", "\U0001F6A4", "\U0001F6A2", "\U0001F680", "\U0001F6F8",
        "\U0001F5FC", "\U0001F5FD", "\U0001F3F0", "\U0001F3EF", "\U0001F3E0", "\U0001F3E1",
        "\U0001F3E2", "\U0001F3EC", "\U0001F3E5", "\U0001F3EB", "⛲", "⛺",
        "\U0001F305", "\U0001F306", "\U0001F307", "\U0001F309", "\U0001F9ED", "\U0001F5FF",
    ]),
    ("Objects", "\U0001F4A1", [
        "⌚", "\U0001F4F1", "\U0001F4BB", "⌨️", "\U0001F4BD", "\U0001F4BE",
        "\U0001F4BF", "\U0001F4F7", "\U0001F4F9", "\U0001F4FA", "\U0001F4FB", "⏰",
        "⌛", "\U0001F4A1", "\U0001F526", "\U0001F4D4", "\U0001F4D5", "\U0001F4D7",
        "\U0001F4DA", "\U0001F4D6", "\U0001F516", "\U0001F4B0", "\U0001F4B5", "\U0001F4B3",
        "\U0001F48E", "\U0001F527", "\U0001F528", "⚙️", "\U0001F512", "\U0001F511",
        "\U0001F6AA", "\U0001F6C1", "\U0001F9F9", "\U0001F9FA", "\U0001F9FD", "\U0001F4E6",
        "\U0001F4EB", "✉️", "\U0001F4DD", "✏️", "\U0001F4CE", "\U0001F4CC",
        "✂️", "\U0001F4CA", "\U0001F4C8", "\U0001F4C5", "\U0001F4BC", "\U0001F381",
    ]),
    ("Symbols", "\U0001F523", [
        "❤️", "\U0001F9E1", "\U0001F49B", "\U0001F49A", "\U0001F499", "\U0001F49C",
        "\U0001F5A4", "\U0001F90D", "\U0001F494", "\U0001F495", "\U0001F49E", "\U0001F493",
        "\U0001F497", "\U0001F496", "\U0001F498", "\U0001F49D", "❣️", "\U0001F4AF",
        "✅", "❌", "❗", "❓", "⚠️", "\U0001F6AB",
        "♻️", "\U0001F531", "⚛️", "✴️", "\U0001F195", "\U0001F193",
        "\U0001F51D", "\U0001F51A", "▶️", "⏸️", "⏹️", "\U0001F500",
        "➕", "➖", "➗", "♾️", "©️", "®️",
        "™️", "\U0001F4AC", "\U0001F4AD", "\U0001F4A4", "\U0001F4A5", "\U0001F389",
    ]),
    ("Flags", "\U0001F3F3️", [
        "\U0001F3C1", "\U0001F6A9", "\U0001F3F4", "\U0001F3F3️", "\U0001F1EE\U0001F1F3",
        "\U0001F1FA\U0001F1F8", "\U0001F1EC\U0001F1E7", "\U0001F1E8\U0001F1E6", "\U0001F1E6\U0001F1FA",
        "\U0001F1E9\U0001F1EA", "\U0001F1EB\U0001F1F7", "\U0001F1EF\U0001F1F5", "\U0001F1E8\U0001F1F3",
        "\U0001F1F0\U0001F1F7", "\U0001F1E7\U0001F1F7", "\U0001F1EE\U0001F1F9", "\U0001F1EA\U0001F1F8",
        "\U0001F1F7\U0001F1FA", "\U0001F1F2\U0001F1FE", "\U0001F1F8\U0001F1EC", "\U0001F1E6\U0001F1EA",
        "\U0001F1F8\U0001F1E6", "\U0001F1FF\U0001F1E6", "\U0001F1F3\U0001F1F5",
    ]),
]

HEADER = '''package com.harithkavish.keyboard;

/**
 * The emoji the picker offers, and the tab row across the bottom of it.
 *
 * <p>Written as backslash-u escapes rather than literals on purpose: the source
 * pure ASCII, so nothing here depends on the build agreeing with the editor
 * about file encoding. Each entry is one grapheme, which for a flag or a
 * variation-selector sequence is several chars -- never index into one.
 *
 * <p>Generated by tools/gen_emoji.py. Edit that, not this.
 */
final class Emoji {

    private Emoji() {
    }

'''

blocks, names, tabs = [], [], []
for name, tab, items in CATEGORIES:
    names.append(name)
    tabs.append(tab)
    blocks.append("    private static final String[] %s = {\n%s\n    };\n"
                  % (name.upper(), jarray(items)))

from gen_keywords import KEYWORDS

pairs = []
for glyph, words in KEYWORDS.items():
    for word in words:
        pairs.append((word, glyph))

KEYWORD_BLOCK = ("""
    /**
     * Flat pairs: keyword, emoji. Modelled on CLDR emoji annotations, where each
     * emoji carries a short name plus synonyms and a search matches any of them.
     *
     * <p>That is the whole reason "smile", "smiley" and "grin" all reach the same
     * face, and why "tick", "tickmark" and "check" all reach the same tick. An
     * earlier version stored one word per emoji, so anything but that exact word
     * found nothing at all.
     *
     * <p>A keyword may appear against several emoji; earlier pairs rank higher.
     */
    static final String[] KEYWORDS = {
%s
    };
""" % "\n".join('        "%s", "%s",' % (w, esc(g)) for w, g in pairs))

body = HEADER + "\n".join(blocks) + KEYWORD_BLOCK + """
    /** Tab glyphs, in the order the picker shows them. Recents is prepended by the view. */
    static final String[] TAB_LABELS = {
%s
    };

    static final String[] CATEGORY_NAMES = {
%s
    };

    static final String[][] CATEGORIES = {
        %s,
    };
}
""" % (jarray(tabs), jarray(names), ", ".join(n.upper() for n, _, _ in CATEGORIES))

out = DIR / "Emoji.java"
out.write_text(body, encoding="ascii")
print("wrote", out, len(body.splitlines()), "lines,",
      sum(len(i) for _, _, i in CATEGORIES), "emoji,",
      len(pairs), "keywords across", len(KEYWORDS), "emoji")
