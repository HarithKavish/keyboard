# -*- coding: utf-8 -*-
"""Generates Vocabulary.java: the seed word list, seed bigrams and seed
word-to-emoji associations the Predictor starts from before it has learnt
anything. Emoji go in as backslash-u escapes so the source stays pure ASCII."""
import pathlib

DIR = pathlib.Path("keyboard/app/src/main/java/com/harithkavish/keyboard")


def esc(s):
    b = s.encode("utf-16-be")
    return "".join("\\u%04x" % int.from_bytes(b[i:i + 2], "big") for i in range(0, len(b), 2))


# Roughly frequency ordered: earlier words win ties in prediction and are the
# stronger pull in autocorrect.
WORDS = """
the be to of and a in that have i it for not on with he as you do at this but his
by from they we say her she or an will my one all would there their what so up out
if about who get which go me when make can like time no just him know take people
into year your good some could them see other than then now look only come its over
think also back after use two how our work first well way even new want because any
these give day most us is are was were been has had did does am being
hello hi hey thanks thank please sorry yes yeah no okay ok sure right left
today tomorrow yesterday morning evening night week month weekend
here there where why home house car food water coffee tea lunch dinner breakfast
love like hate happy sad tired great awesome cool nice bad better best worse
call text message send reply email phone number address name
meeting call project team work office boss client deadline update report
going coming leaving arriving waiting looking talking working running walking
need want try keep let put ask tell feel seem leave move turn start stop
much many more less little big small long short high low old young
really very quite pretty almost always never sometimes often usually maybe
something anything nothing everything someone anyone everyone nobody
should could would might must shall may can cant dont didnt wont isnt arent
finish finished done ready sure maybe problem question answer idea plan
family friend friends mother father brother sister son daughter baby
money price cost cheap free buy sell pay order delivery shipping
school college class exam test study learn teach student teacher
game play watch movie music song book read write draw photo picture
train bus flight ticket hotel trip travel holiday vacation beach
birthday party wedding gift congratulations welcome goodbye goodnight
sleep wake eat drink cook clean wash open close start finish
hospital doctor medicine health sick fever cold pain rest
code build test deploy release bug fix issue branch commit merge review
""".split()

# Deduplicate, keep first-seen order (that order IS the frequency ranking).
seen, ordered = set(), []
for w in WORDS:
    if w not in seen:
        seen.add(w)
        ordered.append(w)

BIGRAMS = [
    ("i", "am"), ("i", "will"), ("i", "have"), ("i", "was"), ("i", "think"),
    ("i", "want"), ("i", "need"), ("i", "dont"), ("i", "just"), ("i", "know"),
    ("thank", "you"), ("thanks", "for"), ("how", "are"), ("are", "you"),
    ("how", "is"), ("what", "is"), ("what", "are"), ("where", "are"),
    ("see", "you"), ("talk", "to"), ("to", "you"), ("let", "me"),
    ("let", "us"), ("me", "know"), ("on", "my"), ("my", "way"),
    ("in", "the"), ("on", "the"), ("at", "the"), ("to", "the"), ("of", "the"),
    ("for", "the"), ("with", "the"), ("from", "the"), ("this", "is"),
    ("that", "is"), ("it", "is"), ("there", "is"), ("here", "is"),
    ("good", "morning"), ("good", "night"), ("good", "evening"), ("good", "luck"),
    ("happy", "birthday"), ("see", "you"), ("you", "later"), ("you", "soon"),
    ("we", "can"), ("we", "will"), ("we", "should"), ("you", "can"),
    ("you", "should"), ("you", "are"), ("you", "have"), ("do", "you"),
    ("did", "you"), ("can", "you"), ("will", "you"), ("would", "you"),
    ("going", "to"), ("want", "to"), ("need", "to"), ("have", "to"),
    ("trying", "to"), ("about", "the"), ("because", "of"), ("as", "well"),
    ("a", "lot"), ("lot", "of"), ("kind", "of"), ("sort", "of"),
    ("right", "now"), ("just", "now"), ("last", "week"), ("next", "week"),
    ("the", "meeting"), ("the", "project"), ("the", "report"), ("the", "code"),
    ("call", "you"), ("send", "the"), ("send", "you"), ("check", "the"),
    ("no", "problem"), ("no", "worries"), ("of", "course"), ("by", "the"),
    ("the", "way"), ("take", "care"), ("have", "a"), ("a", "good"),
    ("looking", "forward"), ("forward", "to"), ("sounds", "good"),
]

def wrap(items, indent=12, width=88):
    out, line = [], " " * indent
    for i, it in enumerate(items):
        piece = it + (" " if i < len(items) - 1 else "")
        if len(line) + len(piece) > width:
            out.append(line.rstrip())
            line = " " * indent
        line += piece
    if line.strip():
        out.append(line.rstrip())
    return "\n".join('"%s "' % l.strip() + " +" for l in out)[:-2].rstrip()


words_block = "\n".join(
    '            + "%s "' % " ".join(ordered[i:i + 9]) for i in range(0, len(ordered), 9))
bigram_block = "\n".join(
    '        "%s %s",' % b for b in BIGRAMS)

body = '''package com.harithkavish.keyboard;

/**
 * What the keyboard knows before it has learnt anything from the person using
 * it. Everything here is a starting point that learning then moves: a seed word
 * carries a small weight so a word typed twice by hand outranks it.
 *
 * <p>The word list is ordered, and that order IS the frequency ranking -- index
 * 0 is the commonest word. It is deliberately small. A real dictionary would be
 * megabytes and would need a binary format and a loader; a few hundred words
 * covers the common case for prediction and gives autocorrect enough to check
 * against, which is as far as this keyboard tries to go.
 *
 * <p>Generated by tools/gen_vocab.py. Edit that, not this.
 */
final class Vocabulary {

    private Vocabulary() {
    }

    /** Space separated, commonest first. */
    static final String WORDS =
            ""
%s;

    /** Each entry is "first second": seeds for next-word prediction. */
    static final String[] BIGRAMS = {
%s
    };

}
''' % (words_block, bigram_block)

out = DIR / "Vocabulary.java"
out.write_text(body, encoding="ascii")
print("wrote", out, len(ordered), "words,", len(BIGRAMS), "bigrams")
