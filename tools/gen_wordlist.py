# -*- coding: utf-8 -*-
"""Builds app/src/main/res/raw/words.txt from a frequency-ordered word list.

The keyboard shipped with 345 hand-written seed words, which is enough to test
prediction and nowhere near enough to use it: typing "art" offered nothing
beyond "art" itself, because "article", "artist" and "artificial" were simply
not known.

Source: github.com/first20hours/google-10000-english (20k list), ordered by
frequency in the Google Web Trillion Word Corpus. Order IS the ranking, so the
file is used as-is and the app scores by line number.

    python tools/gen_wordlist.py          # expects words20k.txt beside it

Three things are done to it, and each is here for a reason.

1. Anything that is not plain lowercase ASCII, apostrophes aside, goes. The
   source has a little noise and a suggestion strip is not the place to find out.

2. Contractions get their apostrophes back. The source has NONE AT ALL -- it
   lists "dont", "im" and "thats", and is simply missing "aren't", "you're" and
   "couldn't" -- so a keyboard built on it offers "dont" and "cant" as if they
   were words. Bare forms that are not words are replaced in place, keeping
   their rank. Bare forms that ARE words ("its", "were", "well", "ill", "id",
   "lets") are kept and the apostrophised form added beside them, because
   "its" and "it's" are both real and the keyboard must not lose either.

3. A blocklist of slurs and explicit terms goes. That list lives here, at build
   time, rather than in the shipped app: the keyboard should not volunteer these
   words as someone types an innocent prefix, but nor does the repository need to
   carry them around. Nothing stops anyone typing them by hand.
"""
import pathlib
import sys

HERE = pathlib.Path(__file__).parent
ROOT = HERE.parent
OUT = ROOT / "app/src/main/res/raw/words.txt"

# Words the strip should never volunteer. Build-time only; see the note above.
BLOCKED = set("""
fuck fucking fucked fucker fuckers shit shitty bullshit bitch bitches
cunt cock cocks dick dicks pussy pussies whore slut sluts fag fags faggot
nigger niggers nigga niggas spic chink kike wetback tranny retard retarded
porn porno pornography xxx sex sexy sexual nude nudes naked tits boobs
anal oral penis vagina orgasm masturbation erotic fetish hentai milf
rape raped rapist incest bestiality pedophile
asshole assholes bastard damn goddamn hell crap piss pissed wanker
""".split())

# Bare form -> real form. Only where the bare form is not itself a word, so the
# replacement can take its rank and the bare spelling disappears entirely. That
# is what lets autocorrect turn "dont" into "don't" at the end of the word.
REPLACE = {
    "dont": "don't", "cant": "can't", "wont": "won't", "isnt": "isn't",
    "arent": "aren't", "wasnt": "wasn't", "werent": "weren't",
    "didnt": "didn't", "doesnt": "doesn't", "havent": "haven't",
    "hasnt": "hasn't", "hadnt": "hadn't", "couldnt": "couldn't",
    "wouldnt": "wouldn't", "shouldnt": "shouldn't", "mustnt": "mustn't",
    "aint": "ain't", "im": "i'm", "ive": "i've",
    "youre": "you're", "youve": "you've", "youll": "you'll", "youd": "you'd",
    "theyre": "they're", "theyve": "they've", "theyll": "they'll",
    "theyd": "they'd", "weve": "we've", "hes": "he's", "shes": "she's",
    "thats": "that's", "theres": "there's", "heres": "here's",
    "whats": "what's", "whos": "who's", "wheres": "where's", "hows": "how's",
}

# Real form -> the word it should sit next to in the ranking. Used where the
# bare spelling is a word in its own right and must be kept.
ADD_AFTER = {
    "it's": "it", "we're": "we", "we'll": "we", "we'd": "we",
    "i'll": "is", "i'd": "is", "let's": "let", "he'll": "he", "she'll": "she",
    "that'll": "that", "there's": "there", "who'd": "who",
}


def main():
    source = HERE / "words20k.txt"
    if not source.exists():
        sys.exit("expected %s -- download the 20k list first" % source)

    seen = set()
    out = []
    for line in source.read_text(encoding="utf-8").splitlines():
        word = line.strip().lower()
        if not word or len(word) < 2 or not word.isascii():
            continue
        if not all(c.isalpha() or c == "'" for c in word):
            continue
        if word in BLOCKED:
            continue
        word = REPLACE.get(word, word)
        if word in seen:
            continue
        seen.add(word)
        out.append(word)

    added = 0
    for word, anchor in ADD_AFTER.items():
        if word in seen:
            continue
        try:
            at = out.index(anchor) + 1
        except ValueError:
            at = min(len(out), 2000)
        out.insert(at, word)
        seen.add(word)
        added += 1

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(out) + "\n", encoding="ascii")
    print("wrote %s: %d words (%d contractions restored, %d added), %.0f KB raw"
          % (OUT, len(out), len(REPLACE), added, OUT.stat().st_size / 1024))

    for probe in ("art", "artificial", "don't", "can't", "i'm", "it's", "we're",
                  "its", "were", "dont", "cant"):
        where = out.index(probe) + 1 if probe in seen else "GONE"
        print("  %-12s %s" % (probe, where))


if __name__ == "__main__":
    main()
