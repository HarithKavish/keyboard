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

Two filters are applied, and both matter:

- Anything that is not plain lowercase a-z goes. The source has a little noise,
  and a suggestion strip is not the place to find out.
- A blocklist of slurs and explicit terms goes. This list lives here, at build
  time, rather than in the shipped app: the keyboard should not volunteer these
  words as you type an innocent prefix, but nor does the repository need to
  carry them around. Nothing stops anyone typing them by hand.
"""
import pathlib
import sys

HERE = pathlib.Path(__file__).parent
OUT = pathlib.Path("keyboard/app/src/main/res/raw/words.txt")

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


def main():
    source = HERE / "words20k.txt"
    if not source.exists():
        sys.exit("expected %s -- download the 20k list first" % source)

    seen = set()
    out = []
    for line in source.read_text(encoding="utf-8").splitlines():
        word = line.strip().lower()
        if not word or len(word) < 2:
            continue
        if not word.isalpha() or not word.isascii():
            continue
        if word in BLOCKED or word in seen:
            continue
        seen.add(word)
        out.append(word)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(out) + "\n", encoding="ascii")
    print("wrote %s: %d words, %.0f KB raw"
          % (OUT, len(out), OUT.stat().st_size / 1024))

    # A sanity check on the thing that prompted this.
    for probe in ("art", "article", "artist", "artificial"):
        print("  %-11s rank %s" % (probe, out.index(probe) + 1 if probe in seen else "MISSING"))


if __name__ == "__main__":
    main()
