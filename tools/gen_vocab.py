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

# Seed emoji associations: the word most likely to precede or be the trigger.
EMOJI_SEED = [
    ("love", "❤️"), ("heart", "❤️"), ("happy", "\U0001F60A"),
    ("sad", "\U0001F622"), ("cry", "\U0001F62D"), ("laugh", "\U0001F602"),
    ("funny", "\U0001F602"), ("fire", "\U0001F525"), ("hot", "\U0001F525"),
    ("birthday", "\U0001F382"), ("party", "\U0001F389"), ("congratulations", "\U0001F389"),
    ("congrats", "\U0001F389"), ("thanks", "\U0001F64F"), ("thank", "\U0001F64F"),
    ("please", "\U0001F64F"), ("sorry", "\U0001F614"), ("ok", "\U0001F44D"),
    ("okay", "\U0001F44D"), ("good", "\U0001F44D"), ("great", "\U0001F525"),
    ("awesome", "\U0001F929"), ("cool", "\U0001F60E"), ("nice", "\U0001F44C"),
    ("food", "\U0001F355"), ("lunch", "\U0001F35D"), ("dinner", "\U0001F37D"),
    ("coffee", "☕"), ("tea", "\U0001F375"), ("beer", "\U0001F37A"),
    ("pizza", "\U0001F355"), ("cake", "\U0001F370"), ("sleep", "\U0001F634"),
    ("tired", "\U0001F634"), ("sun", "☀️"), ("rain", "\U0001F327"),
    ("dog", "\U0001F436"), ("cat", "\U0001F431"), ("money", "\U0001F4B0"),
    ("work", "\U0001F4BC"), ("home", "\U0001F3E0"), ("car", "\U0001F697"),
    ("music", "\U0001F3B5"), ("song", "\U0001F3B5"), ("star", "⭐"),
    ("angry", "\U0001F620"), ("hug", "\U0001F917"), ("kiss", "\U0001F618"),
    ("wow", "\U0001F62E"), ("yes", "✅"), ("no", "❌"),
    ("hello", "\U0001F44B"), ("hi", "\U0001F44B"), ("hey", "\U0001F44B"),
    ("bye", "\U0001F44B"), ("goodbye", "\U0001F44B"), ("night", "\U0001F319"),
    ("goodnight", "\U0001F319"), ("morning", "\U0001F305"), ("miss", "\U0001F97A"),
    ("beautiful", "\U0001F60D"), ("wedding", "\U0001F48D"), ("baby", "\U0001F476"),
    ("gift", "\U0001F381"), ("travel", "✈️"), ("flight", "✈️"),
    ("book", "\U0001F4DA"), ("game", "\U0001F3AE"), ("win", "\U0001F3C6"),
    ("football", "⚽"), ("movie", "\U0001F3AC"), ("photo", "\U0001F4F7"),
    ("idea", "\U0001F4A1"), ("bug", "\U0001F41B"), ("done", "✅"),
    ("ready", "\U0001F680"), ("deploy", "\U0001F680"), ("hundred", "\U0001F4AF"),
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
emoji_block = "\n".join(
    '        "%s", "%s",' % (w, esc(e)) for w, e in EMOJI_SEED)

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

    /** Flat pairs: word, emoji, word, emoji. */
    static final String[] EMOJI_SEED = {
%s
    };
}
''' % (words_block, bigram_block, emoji_block)

out = DIR / "Vocabulary.java"
out.write_text(body, encoding="ascii")
print("wrote", out, len(ordered), "words,", len(BIGRAMS), "bigrams,",
      len(EMOJI_SEED), "emoji seeds")
