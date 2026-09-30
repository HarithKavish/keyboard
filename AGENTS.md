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
has no dependencies at all, and the release APK is about 157 KB, of which the
word list is 73 KB.

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

**A deleted completion is a refusal too, and shares the same blocked set.**
The space bar expands a single letter into a word and inserts the expected next
word on a second press. Deleting either used to be an ordinary backspace that
taught nothing, so the same unwanted word came back every time. Both now arm the
undo through `rememberCompletion()`, and `space()` checks `isRefused()` before
offering again.

Two details that are easy to get wrong:

- The key a refusal is recorded against is not always what gets put back.
  Completing "k" into "keyboard" keys on "k"; inserting a word after "the" keys
  on "the", because nothing was replaced and the restore text is empty. Hence
  `correctionKey` alongside `correctionTyped`.
- `rejectCompletion()` deliberately does NOT bump the key's word count, which is
  the one thing it must not share with `rejectCorrection()`. A completion's key
  may be a single letter or the preceding word, and neither is evidence that it
  is a word someone typed. There is a test pinning exactly that difference.

**Glyphs and icons are capped, keys are not.** `MAX_GLYPH_DP` (14dp) and
`MAX_ICON_DP` (9dp) match the emoji picker's bottom bar, which is where those
sizes came from. The keys still scale with the row; what is drawn inside them
stops. Before this, a letter was 16dp against the picker's 14dp label and the
keyboard's backspace was half again as wide as the picker's, which read as two
different apps sharing a window.

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

**Which way a pane tints depends on the backdrop, and it has to.** Over a dark
backdrop the keys tint dark with light glyphs; over a light one they tint light
with dark glyphs. This is not a style choice: at the opacity needed to hide the
text behind the keys, a pale pane leaves pale glyphs sitting on near-white and
unreadable. Raising opacity and keeping one pane colour does not work — the two
decisions are bound together.

The panes are deliberately solid enough to pass the colour behind them but not
its detail. That is as close to frosting as this gets without a blur, and Android
will not blur per-key.

**The two emoji callbacks are named apart on purpose.**
`GlassKeyboardView.Listener.onEmojiSuggestion()` replaces the word being typed;
`EmojiPanelView.Listener.onEmojiPicked()` inserts at the cursor. They used to
share one name, which meant the service could not tell them apart and both
behaved the same. Do not merge them again: they are different gestures with
different answers.

**The word list is the one large thing in here, and it was a decision.**
`res/raw/words.txt` is 20,000 words in frequency order, 73 KB compressed, and it
roughly doubled the APK. It earns that: the 345 hand-written seeds in
`Vocabulary` could not complete "art" into anything, and a keyboard whose
prediction stops at three letters has prediction in name only. It also stops
autocorrect mangling real-but-uncommon words, which was the quieter problem.

Rebuild it with `tools/gen_wordlist.py`. Trimming to 10,000 words would save
about 35 KB and still cover ordinary prediction; the second ten thousand is
mostly there so autocorrect knows those words exist.

Three things keep it cheap at runtime, and all three matter:

- It loads on a background thread, once per process, and is published by
  assigning one `Vocab` to a volatile field. Half a dictionary is not a state
  anything should reason about.
- Prefix search walks it in frequency order and stops after `CANDIDATE_LIMIT`
  hits, because the first matches are the best ones.
- Membership is a sorted array and a binary search, not a HashSet, which saves
  roughly a megabyte of heap for the one question ever asked of it.

Autocorrect reaches only `AUTOCORRECT_REACH` deep. Correcting a typo into a word
nobody uses is worse than not correcting it, and it bounds the edit-distance
work done at every word boundary.

**The source word list has no apostrophes in it at all.** It spells contractions
"dont", "im" and "thats", and is missing "aren't", "you're" and "couldn't"
entirely, so `tools/gen_wordlist.py` puts them back. Bare forms that are not
words are replaced in place; bare forms that ARE words — "its", "were", "well",
"ill", "id", "lets" — are kept and the apostrophised form added beside them,
because both spellings are real and losing either is worse than the misspelling.

Three things follow from that, and each is load-bearing:

- `matchesPrefix()` steps over apostrophes, so typing "dont" still finds
  "don't". Nobody reaches for the apostrophe key mid-word, and without this the
  suggestion disappears exactly when it is wanted.
- Restoring a missing apostrophe beats any other single edit. "cant" was being
  corrected to "can" — both one edit away, and "can" is commoner so it won on
  score. Deleting a letter someone typed is a worse guess than adding the
  punctuation they skipped.
- That check runs *after* the known-word test, never before. Reversed, it turns
  the possessive "its" into "it's" and the past tense "were" into "we're".

**The suggestion row is a sibling of the pages, not a band inside one.**
`SuggestionStripView` sits above a container holding the keyboard and the emoji
picker, and the service feeds it once. It used to live inside
`GlassKeyboardView`, which caused both halves of the same bug: the picker is a
sibling view, so it had no suggestions at all, and a page change returned inside
the keyboard without the service ever hearing, so the symbol page showed whatever
the letters page had left behind. The row belongs to the input, not to a page.

Two things follow, and both are easy to break:

- `GlassKeyboardView.preferredHeight()` is now the keys alone. The row adds its
  own height on top. Anything that measures the keyboard has to account for both.
- The row capitalises from two sources: the letters already typed, which arrive
  with the suggestions, and the shift key, which lives in the keyboard. The
  keyboard therefore calls `onKeyboardStateChanged()` whenever the page or the
  shift state moves — including when a one-shot shift is spent. Miss one of those
  call sites and the strip disagrees with the key that is lit.

The space bar swipe goes through the service for the same reason: the best word
is in the row, so the keyboard asks rather than reads. `setSwipeWord()` pushes it
back for drawing on the bar mid-gesture.

**One glass, one place: `Glass.java`.** The keys, the suggestion strip and every
emoji cell and picker button draw the same panes, so the colour decisions and the
drawing live there once. Each view still builds its own shaders, because a
vertical gradient is made for one specific height and a key, a suggestion and an
emoji cell are three different heights — but none of them decides what the glass
looks like. Two copies of that would drift the moment either was tuned, which is
exactly how the strip and then the picker ended up as the only things on screen
the opacity setting did not reach.

`GlassKeyboardService.pushAppearance()` pushes the setting to both views for the
same reason. Reaching only one of them is a bug waiting to be reported.

**The picker's heading is pinned, and the grid scrolls under it.** `gridTop()`
is where the scrolling area starts and `contentHeight` counts only the rows, so
the heading is not part of what scrolls. Anything that converts a touch to a cell
has to go through `gridTop()` too, or taps land a row out once the grid is
scrolled.

Both the picker and the keys use a 14dp side margin. It is written down twice,
once in `layoutKeys()` and once in `onSizeChanged()`; if one moves, move the
other, because the two pages sitting in the same window at different widths is
immediately visible.

**The emoji key's face is drawn at 0.78 of the shared icon basis.** Every other
icon uses the basis directly. A closed circle reads larger than an arrow or a
bracket drawn to the same bounding box, so matching by number left that one
looking oversized beside the letters. `tools/preview.py` carries the same factor
and has to be changed with it.

**The emoji picker shows one category at a time.** The tabs are the only way
between them: a flick cannot drift out of the category being browsed, and
scrolling stops at the end of it rather than running on into the next. `page`
holds the index and `pageName` holds the name, because picking a first emoji
inserts a Recents section at the front and shifts every index along by one — the
name does not move, so `restorePage()` finds the category again after any
rebuild.

**There is no launcher icon, and that is deliberate.** `SetupActivity` keeps
`exported="true"` and its MAIN intent-filter but drops the LAUNCHER category, so
it does not take a slot in the app drawer. It stays reachable because
`res/xml/method.xml` names it as the input method's `settingsActivity`, which
Android links from the keyboard's own entry under Languages and input. Removing
that attribute would strand the settings screen with no way in at all.

Watch the XML: a literal `--` anywhere inside an XML comment is a parse error,
and the manifest merger reports it only as "Error parsing AndroidManifest.xml".

**The space bar swipe is claimed before the finger can leave the bar.**
`SWIPE_MIN_DP` is 40dp, comfortably inside it, and once `spaceSwiped` is set
ACTION_MOVE stops retargeting entirely -- otherwise sliding past the end of the
bar would quietly turn the gesture back into a key press on whatever is beyond
it. The pending word is drawn on the bar while swiping, because a blank bar
gives no clue which word is about to be committed.

**The setup screen is pitch black and fixed dark, not DayNight.** The window
background is pure black, so a light theme's dark text would be unreadable on it;
the dark `DeviceDefault` parent supplies the light text and switch colours that
go with it. The `values-v29` DayNight override was removed for that reason — put
it back and half the screen becomes unreadable in light mode.

**Publishing needs the signing secrets.** `publish-store.yml` refuses to run
without them, on purpose: Android identifies an app by its signature, so a build
signed with a different key is a different app and cannot update an installed
one. See [BUILD.md](BUILD.md).
