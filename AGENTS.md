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
is four Java classes with no dependencies, and the release APK is about 36 KB.

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

**Publishing needs the signing secrets.** `publish-store.yml` refuses to run
without them, on purpose: Android identifies an app by its signature, so a build
signed with a different key is a different app and cannot update an installed
one. See [BUILD.md](BUILD.md).
