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

**The transparency is fragile.** It needs all three of `windowBackground`,
`windowIsTranslucent` and `colorBackgroundCacheHint` on the service's theme in
`res/values/styles.xml`, plus `onEvaluateFullscreenMode()` returning false. Drop
any one and the platform paints an opaque panel behind the keys, or replaces the
app with an opaque extracted-text editor in landscape. There is no test for this;
it has to be looked at on a device.

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
