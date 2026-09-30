# Glass Keyboard

A QWERTY keyboard for Android with translucent keys and no background of its
own. The app behind it shows straight through the gaps, and the keys read as
panes of glass laid over it.

It is deliberately small. There are four Java classes, no libraries, and no
AndroidX — the release APK is around **36 KB**, which is roughly a thousandth of
what a mainstream keyboard installs.

## What it does

- A three-page QWERTY layout: letters, symbols, and a second symbol page.
- Shift, with caps lock on a double tap.
- Backspace, which repeats when held.
- Enter, which runs the field's own action — search in a search box, newline in
  a message box — rather than always sending one or the other.
- Light and dark treatments, chosen from the system theme.

It does not do suggestions, autocorrect, swipe input, emoji, themes, or
settings. Those are what make a keyboard large, and none of them are here.

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
