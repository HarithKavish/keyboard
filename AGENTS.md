# Agent Instructions

This repository is part of the **HarithKavish ecosystem**.

**Before changing anything**, read
[AGENT_BOOTSTRAP.md](https://github.com/HarithKavish/harithkavish-governance/blob/main/AGENT_BOOTSTRAP.md)
and follow it. See [GOVERNANCE.md](GOVERNANCE.md) for what governs this
repository.

Do not begin implementation work before discovery is complete.

## Hard stops

A reminder, not the rule. These restate doctrine articles so an agent that reads
nothing else still has the guardrails. Governance is authoritative; if these ever
disagree with it, governance wins.

- Do not commit to the production branch (Article 6).
- Do not commit secrets or credentials (Article 5, SECURITY).
- Do not redefine design foundations locally (Article 4).
- Do not copy governance or the design system into this repository (Article 3).
- Do not act outside the scope you were given (Article 9).

## About this repository

Glass Keyboard is an Android input method: a QWERTY keyboard with translucent
keys drawn over a fully transparent window, so the app behind shows through. It
has no dependencies at all, and the release APK is about 73 KB.

Beyond the keys it carries a suggestion strip (three words, best in the centre,
plus two emoji or a punctuation mark), autocorrect that learns from being
overruled, automatic capitalisation, an emoji picker, and switches in the app for
what it may learn.

## Working here

**Size is a feature, not an accident.** The reason this keyboard is kilobytes
rather than megabytes is that it has no dependencies — no AndroidX, no Kotlin, no
support library, no Compose. Adding one costs more than it looks like: the Kotlin
standard library alone is larger than this entire app. `reportApkSize` runs on
every CI build so a regression shows up in the log rather than at publish time.
If a dependency is genuinely needed, say what it buys and what it costs in the
pull request.

**The transparency is fragile, and it already broke once.** The first build
shipped with `android:theme` on the `<service>` element, which the manifest
parser accepts and the framework ignores —
`InputMethodService.onCreate()` calls `super.setTheme()` with its own field and
overwrites whatever the manifest said, and `setTheme()` throws once the window
exists. The theme was never applied, the window used the opaque platform IME
theme, and the keyboard rendered as a black slab. **The theme is set in
`GlassKeyboardService`'s constructor. Do not move it, and do not "restore" the
manifest attribute.**

Beyond that it needs `windowBackground` transparent, `windowIsTranslucent` true,
`colorBackgroundCacheHint` null and `backgroundDimEnabled` false in the theme,
`clearInheritedBackgrounds()` clearing the platform decor the input view is
wrapped in, and `onEvaluateFullscreenMode()` returning false so landscape does
not swap the app for an opaque extracted-text editor.

None of this is unit-testable — the failure is a window attribute, not a value a
test can read — so it has to be looked at on a device. Lint, the unit tests and a
green build all passed on the version that rendered a black slab.

**`onDraw` allocates nothing.** The gradients are built once per key height in
`buildShaders()` and each key is drawn translated to the origin so one shader
serves all of them. Creating a `LinearGradient` or a `RectF` inside `onDraw`
would allocate on every frame of every keypress.

**The layout tables carry invariants.** A row in `Keys.java` either totals ten
weight units and spans the full width, or totals less and is centred at letter
width. `KeysTest` enforces that. A new symbol row that quietly totals eleven
still renders — just narrower than every other row — so the test is the only
thing that catches it.

`Keys.rowHeights(page)` is a parallel array to `Keys.page(page)` and must stay
the same length: the view multiplies row *i* by height *i*, so a short array
throws during layout, on a device, inside whatever app happened to be open.
There is a test for it.

**`Emoji.java` and `Vocabulary.java` are generated.** Edit `tools/gen_emoji.py`
or `tools/gen_vocab.py` and re-run them. Both emit pure-ASCII `\u` escapes
rather than emoji literals, so the source cannot be mangled by a build whose file
encoding differs from the editor's. Mind that Java processes `\u` escapes
*inside comments* as well as in code — a comment that mentions one can fail the
build, which is a trap worth knowing before writing about it.

**The learning is behind a seam, and that is deliberate.** `Predictor` talks to
`Predictor.Store`, not to `SharedPreferences`, so `PredictorTest` can exercise
the rules without an Android runtime. This is the part of the keyboard most
likely to be quietly wrong: it changes its own behaviour over time, so a bug in
it reads as "it just feels worse now" rather than as a crash. Anything added to
what it learns should be testable the same way.

**A rejected correction has to stay rejected.** Backspace straight after an
autocorrection puts back what was typed and records a refusal; the word the
person then settles on is learnt as what they actually meant. That undo is the
only unambiguous signal a keyboard ever gets that it was wrong.

The guard in `backspace()` therefore checks that the corrected word really is
the text sitting behind the cursor, rather than trusting a cursor delta.
`onUpdateSelection` fires for the keyboard's own edits too, and a correction
moves the cursor by more than one character, so anything keyed off that delta
throws the undo away at the exact moment it becomes possible. It was written
that way once.

**Backspace deletes a grapheme, never a char.** `Graphemes.lastClusterLength()`
exists because `deleteSurroundingText(1, 0)` splits a surrogate pair: an emoji
became a replacement box on the first press and only cleared on the second. A
flag is two regional indicators, a skin tone is a base plus a modifier, a keycap
is a digit plus two marks, and a family is several emoji joined by zero-width
joiners — all of them look like one character and must delete like one. It is a
plain class with no Android in it so the rules are directly testable.

**There is no dedicated emoji key.** The bottom-left key cycles: letters →
symbols → emoji → letters. A consequence worth knowing is that the symbols page
has no direct key back to the letters — you reach them through the picker's ABC
key, which is why that key sits at the picker's bottom left, under the same
thumb. `KeysTest` pins the cycle, because without it the picker is unreachable.

**The emoji picker is exactly the keyboard's height.** Both views measure through
`GlassKeyboardView.preferredHeight()`. They share a window, so a picker that
measured itself against the space available would push the app off the screen.

**Emoji are found by keyword, CLDR-style.** `Emoji.KEYWORDS` gives each emoji a
short name plus synonyms, and matching is loose in three directions: the whole
word, a keyword the word starts with ("tickmark" → "tick"), and a keyword
starting with what has been typed ("smi" → "smile"). One word per emoji was the
original design and it found almost nothing. The looseness has a cost — "zzzqqq"
reaches the sleep emoji via "zzz" — and that is an accepted trade, with a test
saying so.

**Shift is a three-state cycle**, not a double-tap: off → shift → caps lock. A
timed gesture is invisible, and nobody should have to discover how fast to tap.
Automatic capitalisation sets shift at the start of a sentence but never
overrides a person who has touched the key themselves, which is what
`manualShift` guards.

**Capitalisation goes through `Casing`, all of it.** Three things need the same
answer and must not disagree: what the strip draws, what a tapped suggestion
commits, and what the space bar inserts when it completes a word. Two sources
feed it — the shift key, and the letters already typed, because "H" asks for
"Hi" even though shift was consumed by the H — and `stronger()` takes whichever
asks for more. A null mode means NONE; an early version let null fall through to
TITLE and quietly capitalised everything, which a test caught.

**The space bar has three jobs and two guards.** It completes a single letter,
inserts the expected word when pressed twice, or types a space. The guards are
what keep it usable:

- `standsAlone()` exempts "a" and "i", which are words. Without it, typing "a "
  gives "and" and there is no way to type "a" at all.
- `endsWithWordThenSpace()` checks the character *before* the space, so the space
  this keyboard adds after a full stop is not mistaken for one the person typed.
  Without that check, every sentence would end by inserting a random word.

**Punctuation spacing is decided from the text, not remembered.** `punctuate()`
removes a space sitting where the mark belongs and adds one after. Whether
capitals come back is not tracked anywhere: `readContext()` re-reads the field
and sees the sentence restart, which is why a comma behaves differently from a
full stop without a single flag saying so.

**The keyboard cannot see what is behind it, and never will.** Android gives an
input method no access to the pixels of the window below — a security boundary,
not a gap — and the ways around it (MediaProjection, an accessibility service)
want consent a keyboard has no business asking for. Do not accept a request to
"sample the background"; it cannot be built. `Appearance` adapts to the
*wallpaper* instead, via `getWallpaperColors`, which is the only backdrop the
platform will describe and is what actually shows wherever the app does not draw
under the keyboard. The rest is a slider.

That read goes over IPC and can block, so it runs on its own thread and is
cached for the process. The SDK_INT guard is repeated inside `readWallpaper()`
on purpose: lint reads each method alone and cannot see a guard one frame up.

`Appearance.isLight()` unpacks colour channels by hand rather than through
`android.graphics.Color`, whose methods are unmocked stubs that throw under unit
tests. That is what makes the luminance rule testable, and it is worth keeping
that way.

**Publishing needs the signing secrets.** `publish-store.yml` refuses to run
without them, on purpose: Android identifies an app by its signature, so a build
signed with a different key is a different app and cannot update an installed
one. See [BUILD.md](BUILD.md).
