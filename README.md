# Glass Keyboard

A QWERTY keyboard for Android with translucent keys and no background of its
own. The app behind it shows straight through the gaps, and the keys read as
panes of glass laid over it.

It is deliberately small. No libraries, no AndroidX, no Kotlin — the release APK
is around **73 KB**, roughly a thousandth of what a mainstream keyboard installs.

## What it does

- A three-page QWERTY layout — letters, symbols, a second symbol page — with a
  short number row above the letters.
- One key, bottom left, that cycles: letters → symbols → emoji → letters.
- Shift as a three-state cycle: off, shift, caps lock. No double-tap timing to
  discover.
- Capitals at the start of a sentence, and again if you delete back to nothing.
  A full stop takes its own space and starts the next sentence capitalised.
- Backspace, which repeats when held, and which deletes a whole emoji — flags,
  skin tones and all — rather than half of one.
- A lone "i" becomes "I", as do "i'm", "i've" and "i'll".

### What the space bar does

Three things, depending on where the cursor is:

- **After a single letter**, it completes the word: "h" then space gives the best
  word starting with h. Except after "a" and "i", which are words already —
  completing those would leave no way to type them.
- **Pressed twice in a row**, it types the word the keyboard expects next.
- **Otherwise**, it ends the word and types a space.

### Punctuation

A mark hugs the word before it and takes a space after, so "hello ." becomes
"hello. " however you got there. A full stop, question mark or exclamation mark
starts a new sentence and brings capitals back; a comma does not.
- Enter, which runs the field's own action — search in a search box, newline in
  a message box — rather than always sending one or the other.
- Light and dark treatments, chosen from the system theme.

### Suggestions

A strip above the keys offers three words and two emoji. The **best guess sits in
the centre**, second on the left and third on the right, because the centre is
where a thumb resting under the space bar already is.

The words come from what is being typed (completions) or from the word before it
(what usually follows). The typed word itself always stays reachable, so a word
the keyboard has never seen can still be kept. With no emoji to offer, the words
spread across the whole strip instead of leaving a third of it empty.

Suggestions follow both the shift key and the letters already typed: capitalised
under shift, in capitals under caps lock, and capitalised after a typed capital —
type "H" and the suggestion is "Hi", not "hi". Tap a capitalised word to keep it and the keyboard remembers the name that
way — type "Harith" once mid-sentence and it is offered as "Harith" from then on.

Once a sentence is long enough to be worth ending, the third slot offers the
punctuation to end it with: a question mark if the sentence opened with a
question word, an exclamation mark after "congratulations", a full stop
otherwise — and whatever you actually use after a given word, once it has seen
you do it.

### Autocorrect, and being told it is wrong

A word is corrected as it is finished. **Press backspace straight afterwards and
what you actually typed comes back** — and the keyboard records that correction
as refused, so it will not make it again. Whatever you settle on instead is
learnt as what you meant, so the same typing lands on the right word next time.

That undo is the only moment a keyboard can be certain it was wrong, which is
why it is treated as the signal rather than as an ordinary delete.

### Emoji

Two taps of the bottom-left key open a picker with a scrolling grid, category
tabs and recents — transparent, like the rest of the keyboard, and exactly as
tall as the keyboard rather than the screen. ABC sits at its bottom left, under
the same thumb, so the third tap returns to the letters. No search, GIFs or
stickers; those are three separate products.

Tapping an emoji in the strip **replaces the word being typed** — the strip
offered it because of those letters, so "fire" becomes the flame rather than
sitting next to it. An emoji chosen in the picker is inserted where the cursor
is and replaces nothing, because getting there takes two deliberate taps away
from the letters.

The two emoji in the strip are found by keyword rather than by whole word, the
way CLDR annotates them: "smile", "smiley" and "grin" all reach the same face,
and "tick", "tickmark" and "check" all reach the same tick. Half a word is
enough — "smi" already finds it.

### How solid the keys are

The keys are solid enough to carry the colour behind them without carrying its
detail — a red wallpaper gives red keys, but text behind them does not show
through. Over a dark backdrop they tint dark with light glyphs; over a light one
they tint light with dark glyphs. That pairing is forced: at the opacity needed
to hide what is behind, a pale pane would swallow pale glyphs.

A slider in the app moves it either way, with the middle leaving the design as
drawn.

The keyboard **cannot see the app behind it** — Android gives an input method no
way to read the pixels of the window below, and the ways around that need
permissions a keyboard should never ask for. What it can read is your wallpaper,
which is what actually shows through wherever the app does not draw under the
keyboard. On a light wallpaper the keys are made more solid, and the light
treatment is chosen regardless of the system theme, because pale keys on a pale
backdrop disappear no matter how opaque they are. That can be turned off.

### What it learns, and turning it off

Words, emoji habits and corrections are learnt on the device and go nowhere else.
The app has a switch for word learning and a separate one for emoji learning, a
switch for autocorrect, and a button that forgets everything learnt. The built-in
word list and the switches survive a reset; everything learnt does not.

It still does not do swipe input or themes.

## Where it lives

Installable from the store at <https://store.harithkavish.com>.

## How it looks

The keyboard window is fully transparent, and each key is a rounded rectangle
built from three cheap draws: a stacked shadow for lift, a shallow vertical
gradient for the pane, and a hairline rim light brightest along the top edge.

There is deliberately **no gloss band** across the upper half. A bright highlight
over the top half of a rounded rect is the signature of moulded plastic, and it
is what made the first version read as a toy rather than as glass. What sells
glass is restraint — low contrast, a hairline rim, a little depth, and whatever
is behind showing through.

There is no blur either: Android cannot blur what is behind another window before
API 31, and where it can, the blur covers the whole window rectangle rather than
each key — which would replace the transparent background with an opaque frosted
panel and defeat the point.

Getting the window transparent takes more than writing a transparent theme.
**`android:theme` on a `<service>` element does nothing for an input method.**
The manifest parser accepts it, and then `InputMethodService.onCreate()` calls
`super.setTheme()` with its own field and overwrites it; `setTheme()` throws once
the window exists. The only place a theme can win is the service's constructor,
which is where `GlassKeyboardService` sets it. Miss that and the window falls
back to the opaque platform IME theme — an opaque surface with only keys drawn on
it, which looks like a black slab.

The theme itself then needs all of:

- `android:windowBackground` set to transparent
- `android:windowIsTranslucent` set to true
- `android:colorBackgroundCacheHint` set to `@null`
- `android:backgroundDimEnabled` set to false, or a dim scrim washes everything
  behind the window

And the platform's own decor around the input view carries backgrounds of its
own, so `clearInheritedBackgrounds()` walks from the keyboard up to the decor
view and clears them. A transparent window does not help if something between
the keys and the app is still painting.

### What you see through it

The keyboard is transparent, but what is *behind* it is the app's business. An
app that resizes itself to sit above the keyboard draws nothing underneath, and
a transparent keyboard over nothing is black. An app that stays full-screen and
only insets its content — which is what edge-to-edge apps do — is visible
through the keys.

The keyboard could force the second case by not reserving any space in
`onComputeInsets`, at the cost of covering the field being typed into. It does
not: the app decides.

Shift, backspace and enter are drawn as paths rather than typed as characters.
No font is guaranteed to carry the arrow glyphs, and a tofu box on the backspace
key is not worth the few lines it saves.

## Build

Needs JDK 17 and the Android SDK. Nothing else.

```bash
./gradlew assembleDebug      # app/build/outputs/apk/debug/
./gradlew testDebugUnitTest  # the layout invariants
./gradlew reportApkSize      # prints the size of every APK built
```

A release build is signed only if a keystore is configured — see
[BUILD.md](BUILD.md). Without one it still assembles, but unsigned, and an
unsigned APK installs nowhere.

## Install

The keyboard has to be enabled in the system before it can be used, which is an
Android rule for every input method. Launch the app and it walks through the two
steps: enable it in the input-method list, then pick it as the keyboard to type
with.

## Layout tables

`Keys.java` holds the three pages as data. A key carries a width *weight*, not a
width: ten weight units fill the keyboard, so a 1.5-weight shift key is one and
a half letters wide on any screen. A row totalling ten spans the full width; a
row totalling less — the nine-letter home row — keeps letter width and is
centred. `KeysTest` holds those invariants, because the drawing code assumes
them.

## Ecosystem

This repository is part of the HarithKavish ecosystem. See
[GOVERNANCE.md](GOVERNANCE.md) for what governs it and [AGENTS.md](AGENTS.md)
for how to work in it.
